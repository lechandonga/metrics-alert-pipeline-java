package com.github.lechandonga.metricsalertpipelinejava.tests;

import com.github.lechandonga.metricsalertpipelinejava.config.MetricsProperties;
import com.github.lechandonga.metricsalertpipelinejava.model.MetricType;
import com.github.lechandonga.metricsalertpipelinejava.service.AlertService;
import com.github.lechandonga.metricsalertpipelinejava.service.InMemoryNotificationChannel;
import com.github.lechandonga.metricsalertpipelinejava.service.MetricService;
import com.github.lechandonga.metricsalertpipelinejava.store.MetricStore;
import com.github.lechandonga.metricsalertpipelinejava.support.BusinessLoggingExtension;
import com.github.lechandonga.metricsalertpipelinejava.support.ControllableClock;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight, non-Spring harness wiring the real services against a controllable clock.
 * The clock starts at a window boundary ({@code t0}) for readable window arithmetic.
 */
public abstract class AbstractServiceTest {

    protected static final long WINDOW = 10_000;
    protected static final long LATENESS = 5_000;
    protected static final long T0 = 1_000_000_000_000L;

    @RegisterExtension
    protected final BusinessLoggingExtension logging = new BusinessLoggingExtension();

    protected ControllableClock clock = new ControllableClock(T0);
    protected MetricsProperties properties = properties();
    protected MetricStore store = new MetricStore();
    protected InMemoryNotificationChannel channel = new InMemoryNotificationChannel();
    protected MetricService metricService = new MetricService(store, clock, properties);
    protected AlertService alertService =
            new AlertService(store, metricService, channel, clock, properties);

    private static MetricsProperties properties() {
        MetricsProperties p = new MetricsProperties();
        p.setWindowSizeMillis(WINDOW);
        p.setAllowedLatenessMillis(LATENESS);
        return p;
    }

    protected void register(String metric, MetricType type) {
        metricService.register(metric, type.name());
    }

    protected MetricService.SampleDraft sample(String metric,
                                               MetricType type,
                                               long timestamp,
                                               double value) {
        return new MetricService.SampleDraft(metric, type.name(), timestamp, value, null);
    }

    protected MetricService.SampleDraft sample(String metric,
                                               MetricType type,
                                               long timestamp,
                                               double value,
                                               Map<String, String> tags) {
        return new MetricService.SampleDraft(metric, type.name(), timestamp, value,
                tags == null ? null : new HashMap<>(tags));
    }

    protected MetricService.SampleDraft badDraft(String metric,
                                                 String type,
                                                 Long timestamp,
                                                 Double value,
                                                 Map<String, Object> tags) {
        return new MetricService.SampleDraft(metric, type, timestamp, value, tags);
    }

    protected void ingest(MetricService.SampleDraft... drafts) {
        metricService.ingestBatch(List.of(drafts));
    }

    protected int evaluate() {
        return alertService.evaluateAll(clock.nowMillis());
    }
}
