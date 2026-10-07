package com.github.lechandonga.metricsalertpipelinejava.config;

import com.github.lechandonga.metricsalertpipelinejava.service.AlertService;
import com.github.lechandonga.metricsalertpipelinejava.store.MetricStore;
import com.github.lechandonga.metricsalertpipelinejava.time.SystemTimeSource;
import com.github.lechandonga.metricsalertpipelinejava.time.TimeSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Wires the clock bean and the local periodic tasks: watermark advancement/evaluation
 * and expired-sample pruning. Everything runs in-process with no external dependencies.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(MetricsProperties.class)
public class AppConfig {

    @Bean
    public TimeSource timeSource() {
        return new SystemTimeSource();
    }

    @Bean
    public Scheduler scheduler(AlertService alertService,
                               MetricStore store,
                               TimeSource timeSource,
                               MetricsProperties properties) {
        return new Scheduler(alertService, store, timeSource, properties);
    }

    public static class Scheduler {
        private final AlertService alertService;
        private final MetricStore store;
        private final TimeSource timeSource;
        private final MetricsProperties properties;

        public Scheduler(AlertService alertService,
                         MetricStore store,
                         TimeSource timeSource,
                         MetricsProperties properties) {
            this.alertService = alertService;
            this.store = store;
            this.timeSource = timeSource;
            this.properties = properties;
        }

        @Scheduled(fixedDelayString = "${metrics.pipeline.evaluation-period-millis:1000}")
        public void advanceAndEvaluate() {
            alertService.evaluateAll(timeSource.nowMillis());
        }

        @Scheduled(fixedDelayString = "${metrics.pipeline.retention-millis:3600000}",
                initialDelayString = "${metrics.pipeline.retention-millis:3600000}")
        public void prune() {
            store.removeExpired(timeSource.nowMillis() - properties.getRetentionMillis());
        }
    }
}
