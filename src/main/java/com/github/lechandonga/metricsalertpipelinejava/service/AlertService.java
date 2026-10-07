package com.github.lechandonga.metricsalertpipelinejava.service;

import com.github.lechandonga.metricsalertpipelinejava.config.MetricsProperties;
import com.github.lechandonga.metricsalertpipelinejava.model.AlertRule;
import com.github.lechandonga.metricsalertpipelinejava.model.AlertStateSnapshot;
import com.github.lechandonga.metricsalertpipelinejava.model.ComparisonOperator;
import com.github.lechandonga.metricsalertpipelinejava.model.DeliveryStatus;
import com.github.lechandonga.metricsalertpipelinejava.model.MetricSample;
import com.github.lechandonga.metricsalertpipelinejava.model.MetricType;
import com.github.lechandonga.metricsalertpipelinejava.model.NotificationMessage;
import com.github.lechandonga.metricsalertpipelinejava.model.NotificationType;
import com.github.lechandonga.metricsalertpipelinejava.model.RejectReason;
import com.github.lechandonga.metricsalertpipelinejava.model.SeriesKey;
import com.github.lechandonga.metricsalertpipelinejava.model.Statistic;
import com.github.lechandonga.metricsalertpipelinejava.store.AlertState;
import com.github.lechandonga.metricsalertpipelinejava.store.MetricStore;
import com.github.lechandonga.metricsalertpipelinejava.store.NotificationRecord;
import com.github.lechandonga.metricsalertpipelinejava.time.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rule registry, deterministic window evaluation, alert state machine and notification outbox.
 *
 * <p>Evaluation advances each rule's watermark to {@code now - allowedLateness}.
 * Every fully closed window is evaluated exactly once, strictly in window order, so the
 * result is independent of sample arrival order. A lifecycle edge (fire/recover) and its
 * outbox notification are committed together inside the rule lock; delivery happens
 * afterwards and its outcome never mutates the alert state.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final MetricStore store;
    private final MetricService metricService;
    private final NotificationChannel channel;
    private final TimeSource timeSource;
    private final MetricsProperties properties;

    private final ConcurrentHashMap<String, AlertRule> rules = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<SeriesKey, AlertState>> states =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NotificationRecord> outbox = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> watermarks = new ConcurrentHashMap<>();

    private final AtomicLong ruleSequence = new AtomicLong();
    private final AtomicLong notificationSequence = new AtomicLong();

    public AlertService(MetricStore store,
                        MetricService metricService,
                        NotificationChannel channel,
                        TimeSource timeSource,
                        MetricsProperties properties) {
        this.store = store;
        this.metricService = metricService;
        this.channel = channel;
        this.timeSource = timeSource;
        this.properties = properties;
    }

    /** Rule definition as received over the API. */
    public record RuleDraft(String name,
                            String metric,
                            Map<String, String> tagFilter,
                            String statistic,
                            String operator,
                            Double threshold,
                            Integer triggerWindows,
                            Integer recoverWindows) {
    }

    public AlertRule createRule(RuleDraft draft) {
        if (draft == null || draft.metric() == null || draft.metric().isBlank()) {
            throw new AlertRuleException(RejectReason.RULE_FIELD_ILLEGAL, "metric is required");
        }
        MetricType metricType = store.metricType(draft.metric());
        if (metricType == null) {
            throw new AlertRuleException(RejectReason.RULE_FIELD_ILLEGAL,
                    "metric is not registered: " + draft.metric());
        }
        Statistic statistic = Statistic.tryParse(draft.statistic());
        if (statistic == null) {
            throw new AlertRuleException(RejectReason.RULE_FIELD_ILLEGAL,
                    "statistic is invalid: " + draft.statistic());
        }
        ComparisonOperator operator = ComparisonOperator.tryParse(draft.operator());
        if (operator == null) {
            throw new AlertRuleException(RejectReason.RULE_FIELD_ILLEGAL,
                    "operator is invalid: " + draft.operator());
        }
        if (draft.threshold() == null || !Double.isFinite(draft.threshold())) {
            throw new AlertRuleException(RejectReason.RULE_FIELD_ILLEGAL,
                    "threshold must be a finite number");
        }
        int trigger = draft.triggerWindows() == null ? 1 : draft.triggerWindows();
        int recover = draft.recoverWindows() == null ? 1 : draft.recoverWindows();
        if (trigger < 1 || recover < 1) {
            throw new AlertRuleException(RejectReason.RULE_FIELD_ILLEGAL,
                    "triggerWindows and recoverWindows must be >= 1");
        }

        String id = "rule-" + ruleSequence.incrementAndGet();
        String name = draft.name() == null || draft.name().isBlank() ? id : draft.name();
        AlertRule rule = new AlertRule(id, name, draft.metric(),
                draft.tagFilter() == null ? Map.of() : new LinkedHashMap<>(draft.tagFilter()),
                statistic, operator, draft.threshold(), trigger, recover);
        rules.put(id, rule);
        states.put(id, new ConcurrentHashMap<>());
        watermarks.put(id, Long.MIN_VALUE);
        log.info("[RULE:CREATED] id={} name={} metric={} {} {} {} trigger={} recover={}",
                id, name, draft.metric(), statistic, operator, draft.threshold(), trigger, recover);
        return rule;
    }

    public List<AlertRule> listRules() {
        return rules.values().stream().sorted(java.util.Comparator.comparing(AlertRule::id)).toList();
    }

    public AlertRule getRule(String ruleId) {
        AlertRule rule = rules.get(ruleId);
        if (rule == null) {
            throw new AlertRuleException(RejectReason.RULE_NOT_FOUND, "no such rule: " + ruleId);
        }
        return rule;
    }

    /** Advances every rule to the current clock. */
    public int evaluateAll() {
        return evaluateAll(timeSource.nowMillis());
    }

    /** Deterministic variant used by tests; the watermark never moves backwards. */
    public int evaluateAll(long nowMillis) {
        int events = 0;
        for (AlertRule rule : listRules()) {
            events += evaluateRule(rule, nowMillis);
        }
        return events;
    }

    private int evaluateRule(AlertRule rule, long nowMillis) {
        long windowSize = properties.getWindowSizeMillis();
        long lateness = properties.getAllowedLatenessMillis();

        long watermark = nowMillis - lateness;
        long lastClosedStart = Math.floorDiv(watermark, windowSize) * windowSize - windowSize;

        Long previous = watermarks.get(rule.id());
        long fromWindow;
        if (previous == null || previous == Long.MIN_VALUE) {
            fromWindow = store.earliestTimestamp(rule.metric())
                    .stream()
                    .map(ts -> Math.floorDiv(ts, windowSize) * windowSize)
                    .findFirst()
                    .orElse(lastClosedStart);
            if (fromWindow > lastClosedStart) {
                fromWindow = lastClosedStart;
            }
        } else {
            if (lastClosedStart <= previous) {
                return 0;
            }
            fromWindow = previous + windowSize;
        }

        int events = 0;
        if (fromWindow <= lastClosedStart) {
            List<SeriesKey> keys = new ArrayList<>(store.seriesKeys(rule.metric()).stream()
                    .filter(key -> matches(key.tags(), rule.tagFilter()))
                    .toList());
            for (SeriesKey key : keys) {
                events += evaluateSeries(rule, key, fromWindow, lastClosedStart, windowSize);
            }
        }
        watermarks.put(rule.id(), lastClosedStart);
        return events;
    }

    private int evaluateSeries(AlertRule rule,
                                SeriesKey key,
                                long firstWindow,
                                long lastWindow,
                                long windowSize) {
        ConcurrentHashMap<SeriesKey, AlertState> ruleStates = states.get(rule.id());
        AlertState state = ruleStates.computeIfAbsent(key, k -> new AlertState());
        int events = 0;

        synchronized (state) {
            for (long start = firstWindow; start <= lastWindow; start += windowSize) {
                List<MetricSample> samples = store.query(key, start, start + windowSize - 1);
                double observed;
                boolean breached;
                if (samples.isEmpty()) {
                    observed = Double.NaN;
                    breached = false;
                } else {
                    observed = metricService.windowStatistic(samples, rule.statistic());
                    breached = rule.operator().matches(observed, rule.threshold());
                }

                boolean edge = state.acceptWindow(rule, breached, start);
                if (edge) {
                    NotificationType type = state.getStatus() == com.github.lechandonga.metricsalertpipelinejava.model.AlertStatus.INACTIVE
                            ? NotificationType.RECOVERED : NotificationType.TRIGGERED;
                    NotificationRecord record = appendOutbox(rule, key, type, start,
                            start + windowSize, observed);
                    events++;
                    attemptDelivery(record);
                }
            }
        }
        return events;
    }

    private NotificationRecord appendOutbox(AlertRule rule,
                                            SeriesKey key,
                                            NotificationType type,
                                            long windowStart,
                                            long windowEnd,
                                            double observed) {
        String notificationId = "notif-" + notificationSequence.incrementAndGet();
        NotificationMessage message = new NotificationMessage(notificationId, rule.id(), rule.name(),
                key.canonical(), type, windowStart, windowEnd, observed, rule.threshold(),
                timeSource.nowMillis());
        NotificationRecord record = new NotificationRecord(message);
        outbox.put(notificationId, record);
        return record;
    }

    /** Tries to deliver one record; state and delivery outcome stay independently observable. */
    public NotificationRecord attemptDelivery(NotificationRecord record) {
        try {
            channel.send(record.getMessage());
            record.recordAttempt(true, null, timeSource.nowMillis());
        } catch (RuntimeException e) {
            record.recordAttempt(false, e.getMessage(), timeSource.nowMillis());
            log.warn("[NOTIFY:FAILED] id={} error={}", record.getMessage().notificationId(),
                    e.getMessage());
        }
        return record;
    }

    /** Explicit retry; already-delivered and unknown ids are rejected with clear reasons. */
    public NotificationRecord retry(String notificationId) {
        NotificationRecord record = outbox.get(notificationId);
        if (record == null) {
            throw new AlertRuleException(RejectReason.NOTIFICATION_NOT_FOUND,
                    "no such notification: " + notificationId);
        }
        synchronized (record) {
            if (record.getStatus() == DeliveryStatus.DELIVERED) {
                throw new AlertRuleException(RejectReason.NOTIFICATION_ALREADY_DELIVERED,
                        "notification already delivered: " + notificationId);
            }
            attemptDelivery(record);
            return record;
        }
    }

    public List<NotificationRecord> retryPending() {
        List<NotificationRecord> retried = new ArrayList<>();
        for (NotificationRecord record : outbox.values()) {
            synchronized (record) {
                if (record.getStatus() != DeliveryStatus.DELIVERED) {
                    attemptDelivery(record);
                    retried.add(record);
                }
            }
        }
        retried.sort(java.util.Comparator.comparing(r -> r.getMessage().notificationId()));
        return retried;
    }

    public List<AlertStateSnapshot> alertStates(String ruleId) {
        getRule(ruleId);
        List<AlertStateSnapshot> snapshots = new ArrayList<>();
        for (Map.Entry<SeriesKey, AlertState> entry : states.get(ruleId).entrySet()) {
            synchronized (entry.getValue()) {
                snapshots.add(entry.getValue().snapshot(ruleId, entry.getKey().canonical()));
            }
        }
        snapshots.sort(java.util.Comparator.comparing(AlertStateSnapshot::series));
        return snapshots;
    }

    public Map<String, Object> notification(String notificationId) {
        NotificationRecord record = outbox.get(notificationId);
        if (record == null) {
            throw new AlertRuleException(RejectReason.NOTIFICATION_NOT_FOUND,
                    "no such notification: " + notificationId);
        }
        Map<String, Object> view = new HashMap<>();
        view.put("message", record.getMessage());
        view.put("status", record.getStatus());
        view.put("attempts", record.getAttempts());
        view.put("lastError", record.getLastError());
        view.put("deliveredAtMillis", record.getDeliveredAtMillis());
        return view;
    }

    public List<NotificationMessage> listNotifications(String statusFilter) {
        DeliveryStatus filter = statusFilter == null ? null : DeliveryStatus.valueOf(statusFilter);
        return outbox.values().stream()
                .filter(r -> filter == null || r.getStatus() == filter)
                .map(NotificationRecord::getMessage)
                .sorted(java.util.Comparator.comparing(NotificationMessage::notificationId))
                .toList();
    }

    private static boolean matches(Map<String, String> actual, Map<String, String> filter) {
        for (Map.Entry<String, String> e : filter.entrySet()) {
            if (!e.getValue().equals(actual.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }
}
