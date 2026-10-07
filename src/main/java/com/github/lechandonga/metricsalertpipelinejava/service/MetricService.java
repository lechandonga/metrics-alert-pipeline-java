package com.github.lechandonga.metricsalertpipelinejava.service;

import com.github.lechandonga.metricsalertpipelinejava.config.MetricsProperties;
import com.github.lechandonga.metricsalertpipelinejava.model.IngestionResult;
import com.github.lechandonga.metricsalertpipelinejava.model.MetricSample;
import com.github.lechandonga.metricsalertpipelinejava.model.MetricType;
import com.github.lechandonga.metricsalertpipelinejava.model.RejectedSample;
import com.github.lechandonga.metricsalertpipelinejava.model.RejectReason;
import com.github.lechandonga.metricsalertpipelinejava.model.SeriesKey;
import com.github.lechandonga.metricsalertpipelinejava.model.Statistic;
import com.github.lechandonga.metricsalertpipelinejava.model.WindowAggregate;
import com.github.lechandonga.metricsalertpipelinejava.store.MetricStore;
import com.github.lechandonga.metricsalertpipelinejava.time.TimeSource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Metric registration, validation, ingestion, range queries and window aggregation.
 *
 * <p>Window/lateness semantics (event time):
 * <pre>
 * windowStart = floor(ts / windowSize)
 * a sample is accepted only if ts > now - windowSize - allowedLateness
 *   (i.e. its window must not have passed its close + lateness deadline)
 * and ts <= now (no future samples).
 * </pre>
 * Accepted late samples land in their own window, never in the current window.
 */
@Service
public class MetricService {

    private static final Pattern METRIC_NAME = Pattern.compile("[a-zA-Z_:][a-zA-Z0-9_:]*");
    private static final Pattern TAG_KEY = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_.\\-]*");

    private final MetricStore store;
    private final TimeSource timeSource;
    private final MetricsProperties properties;

    public MetricService(MetricStore store, TimeSource timeSource, MetricsProperties properties) {
        this.store = store;
        this.timeSource = timeSource;
        this.properties = properties;
    }

    /** Raw, unvalidated sample draft arriving over the API. */
    public record SampleDraft(String metric,
                              String type,
                              Long timestampMillis,
                              Double value,
                              Map<String, Object> tags) {
    }

    public MetricType register(String metric, String rawType) {
        if (metric == null || metric.isBlank()) {
            throw new MetricValidationException(RejectReason.METRIC_NAME_MISSING,
                    RejectReason.METRIC_NAME_MISSING.defaultMessage());
        }
        if (!METRIC_NAME.matcher(metric.trim()).matches()) {
            throw new MetricValidationException(RejectReason.METRIC_NAME_ILLEGAL,
                    RejectReason.METRIC_NAME_ILLEGAL.defaultMessage());
        }
        MetricType type = MetricType.tryParse(rawType);
        if (rawType == null || rawType.isBlank()) {
            throw new MetricValidationException(RejectReason.TYPE_MISSING,
                    RejectReason.TYPE_MISSING.defaultMessage());
        }
        if (type == null) {
            throw new MetricValidationException(RejectReason.TYPE_UNKNOWN,
                    RejectReason.TYPE_UNKNOWN.defaultMessage() + ": " + rawType);
        }
        MetricType existing = store.register(metric.trim(), type);
        if (existing != type) {
            throw new MetricValidationException(RejectReason.TYPE_MISMATCH,
                    "metric " + metric + " already registered as " + existing);
        }
        return type;
    }

    /**
     * Validates and ingests a batch. Valid samples are appended; invalid samples are
     * returned with an explicit index and reason. A batch never partially "loses" data:
     * every input appears in exactly one of the two lists.
     */
    public IngestionResult ingestBatch(List<SampleDraft> drafts) {
        List<MetricSample> accepted = new ArrayList<>();
        List<RejectedSample> rejected = new ArrayList<>();
        if (drafts == null) {
            return new IngestionResult(accepted, rejected);
        }

        for (int i = 0; i < drafts.size(); i++) {
            SampleDraft draft = drafts.get(i);
            ValidationFailure failure = validate(draft);
            if (failure != null) {
                rejected.add(new RejectedSample(i,
                        draft == null ? null : draft.metric(),
                        draft == null ? null : draft.type(),
                        draft == null ? null : draft.timestampMillis(),
                        draft == null ? null : draft.value(),
                        draft == null ? null : safeTags(draft.tags()),
                        failure.reason(), failure.message()));
                continue;
            }
            MetricType type = MetricType.tryParse(draft.type());
            Map<String, String> normalizedTags = normalizeTags(draft.tags()).tags();
            MetricSample sample = new MetricSample(draft.metric().trim(), type,
                    draft.timestampMillis(), draft.value(), normalizedTags);
            store.append(sample);
            accepted.add(sample);
        }
        return new IngestionResult(accepted, rejected);
    }

    /** Convenience ingestion for the single-sample endpoint; throws on any rejection. */
    public MetricSample ingestOne(SampleDraft draft) {
        IngestionResult result = ingestBatch(List.of(draft));
        if (!result.accepted().isEmpty()) {
            return result.accepted().get(0);
        }
        RejectedSample r = result.rejected().get(0);
        throw new MetricValidationException(r.reason(), r.message());
    }

    public List<MetricSample> query(String metric,
                                    Map<String, String> tagFilter,
                                    Long fromMillis,
                                    Long toMillis) {
        if (store.metricType(metric) == null) {
            throw new MetricValidationException(RejectReason.METRIC_NAME_MISSING,
                    "metric not registered: " + metric);
        }
        long now = timeSource.nowMillis();
        long from = fromMillis == null ? 0 : fromMillis;
        long to = toMillis == null ? now : toMillis;
        Map<String, String> filter = tagFilter == null ? Map.of() : tagFilter;
        return store.query(metric, filter, from, to);
    }

    /**
     * Aggregates samples of the metric into tumbling windows, one result per
     * (window, series). Windows with no samples are omitted; aggregation depends only
     * on event-time membership so arrival order cannot change the outcome.
     */
    public List<WindowAggregate> aggregateWindows(String metric,
                                                  Map<String, String> tagFilter,
                                                  Long fromMillis,
                                                  Long toMillis) {
        List<MetricSample> samples = query(metric, tagFilter, fromMillis, toMillis);
        long windowSize = properties.getWindowSizeMillis();

        Map<SeriesKey, TreeMap<Long, List<MetricSample>>> grouped = new TreeMap<>();
        for (MetricSample sample : samples) {
            long start = Math.floorDiv(sample.timestampMillis(), windowSize) * windowSize;
            grouped.computeIfAbsent(sample.seriesKey(), k -> new TreeMap<>())
                    .computeIfAbsent(start, t -> new ArrayList<>())
                    .add(sample);
        }

        List<WindowAggregate> out = new ArrayList<>();
        for (Map.Entry<SeriesKey, TreeMap<Long, List<MetricSample>>> seriesEntry : grouped.entrySet()) {
            for (Map.Entry<Long, List<MetricSample>> window : seriesEntry.getValue().entrySet()) {
                long start = window.getKey();
                out.add(WindowAggregate.fromSamples(start, start + windowSize,
                        seriesEntry.getKey(), window.getValue()));
            }
        }
        return out;
    }

    public double windowStatistic(List<MetricSample> samples, Statistic statistic) {
        if (samples.isEmpty()) {
            return Double.NaN;
        }
        long start = Math.floorDiv(samples.get(0).timestampMillis(),
                properties.getWindowSizeMillis()) * properties.getWindowSizeMillis();
        WindowAggregate aggregate = WindowAggregate.fromSamples(start,
                start + properties.getWindowSizeMillis(),
                new SeriesKey(samples.get(0).metric(), samples.get(0).tags()), samples);
        return aggregate.statistic(statistic);
    }

    public MetricStore store() {
        return store;
    }

    // ---- validation -------------------------------------------------------

    private record ValidationFailure(RejectReason reason, String message) {
    }

    private record NormalizedTags(Map<String, String> tags) {
    }

    private ValidationFailure validate(SampleDraft draft) {
        if (draft == null) {
            return new ValidationFailure(RejectReason.METRIC_NAME_MISSING, "sample is null");
        }
        if (draft.metric() == null || draft.metric().isBlank()) {
            return new ValidationFailure(RejectReason.METRIC_NAME_MISSING,
                    RejectReason.METRIC_NAME_MISSING.defaultMessage());
        }
        if (!METRIC_NAME.matcher(draft.metric().trim()).matches()) {
            return new ValidationFailure(RejectReason.METRIC_NAME_ILLEGAL,
                    RejectReason.METRIC_NAME_ILLEGAL.defaultMessage() + ": " + draft.metric());
        }

        if (draft.type() == null || draft.type().isBlank()) {
            return new ValidationFailure(RejectReason.TYPE_MISSING,
                    RejectReason.TYPE_MISSING.defaultMessage());
        }
        MetricType type = MetricType.tryParse(draft.type());
        if (type == null) {
            return new ValidationFailure(RejectReason.TYPE_UNKNOWN,
                    RejectReason.TYPE_UNKNOWN.defaultMessage() + ": " + draft.type());
        }
        MetricType registered = store.metricType(draft.metric().trim());
        if (registered != null && registered != type) {
            return new ValidationFailure(RejectReason.TYPE_MISMATCH,
                    "metric " + draft.metric() + " registered as " + registered
                            + " but sample declared " + type);
        }

        if (draft.value() == null) {
            return new ValidationFailure(RejectReason.VALUE_MISSING,
                    RejectReason.VALUE_MISSING.defaultMessage());
        }
        if (!Double.isFinite(draft.value())) {
            return new ValidationFailure(RejectReason.VALUE_NOT_FINITE,
                    RejectReason.VALUE_NOT_FINITE.defaultMessage());
        }
        if (draft.timestampMillis() == null) {
            return new ValidationFailure(RejectReason.TIMESTAMP_MISSING,
                    RejectReason.TIMESTAMP_MISSING.defaultMessage());
        }
        if (draft.timestampMillis() <= 0) {
            return new ValidationFailure(RejectReason.TIMESTAMP_ILLEGAL,
                    RejectReason.TIMESTAMP_ILLEGAL.defaultMessage());
        }

        NormalizedTags tagsResult;
        try {
            tagsResult = normalizeTags(draft.tags());
        } catch (TagValidationException e) {
            return new ValidationFailure(e.reason(), e.getMessage());
        }
        if (tagsResult == null) {
            return new ValidationFailure(RejectReason.TAG_NOT_OBJECT,
                    RejectReason.TAG_NOT_OBJECT.defaultMessage());
        }

        long now = timeSource.nowMillis();
        if (draft.timestampMillis() > now) {
            return new ValidationFailure(RejectReason.TIMESTAMP_IN_FUTURE,
                    "timestamp " + draft.timestampMillis() + " is later than now " + now);
        }
        long closeDeadline = now - properties.getWindowSizeMillis()
                - properties.getAllowedLatenessMillis();
        if (draft.timestampMillis() <= closeDeadline) {
            long windowStart = Math.floorDiv(draft.timestampMillis(),
                    properties.getWindowSizeMillis()) * properties.getWindowSizeMillis();
            return new ValidationFailure(RejectReason.SAMPLE_TOO_LATE,
                    "window starting at " + windowStart + " closed after lateness "
                            + properties.getAllowedLatenessMillis() + "ms (now=" + now + ")");
        }
        return null;
    }

    private NormalizedTags normalizeTags(Map<String, Object> rawTags) {
        if (rawTags == null) {
            return new NormalizedTags(Map.of());
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawTags.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || !TAG_KEY.matcher(key).matches()) {
                throw new TagValidationException(RejectReason.TAG_KEY_ILLEGAL,
                        RejectReason.TAG_KEY_ILLEGAL.defaultMessage() + ": " + key);
            }
            if (!(value instanceof String s) || s.isBlank()) {
                throw new TagValidationException(RejectReason.TAG_VALUE_ILLEGAL,
                        RejectReason.TAG_VALUE_ILLEGAL.defaultMessage() + ": " + key + "=" + value);
            }
            normalized.put(key, s);
        }
        return new NormalizedTags(normalized);
    }

    private Map<String, String> safeTags(Map<String, Object> raw) {
        try {
            NormalizedTags normalized = normalizeTags(raw);
            return normalized == null ? null : normalized.tags();
        } catch (TagValidationException e) {
            Map<String, String> bestEffort = new LinkedHashMap<>();
            if (raw != null) {
                raw.forEach((k, v) -> {
                    if (k != null && v instanceof String s) {
                        bestEffort.put(k, s);
                    }
                });
            }
            return bestEffort;
        }
    }
}
