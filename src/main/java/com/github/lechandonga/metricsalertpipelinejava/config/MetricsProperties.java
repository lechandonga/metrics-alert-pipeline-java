package com.github.lechandonga.metricsalertpipelinejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Local pipeline configuration, all prefixed {@code metrics.pipeline}.
 *
 * @param windowSizeMillis   tumbling window length (event-time aligned to the epoch)
 * @param allowedLatenessMillis samples up to this far behind the watermark close time
 *                          still belong to their original window; later ones are rejected
 * @param retentionMillis    samples older than now - retention are pruned periodically
 * @param evaluationPeriodMillis how often the watermark advances and windows are evaluated
 */
@ConfigurationProperties(prefix = "metrics.pipeline")
public class MetricsProperties {

    private long windowSizeMillis = 10_000;
    private long allowedLatenessMillis = 5_000;
    private long retentionMillis = 3_600_000;
    private long evaluationPeriodMillis = 1_000;

    public long getWindowSizeMillis() {
        return windowSizeMillis;
    }

    public void setWindowSizeMillis(long windowSizeMillis) {
        this.windowSizeMillis = windowSizeMillis;
    }

    public long getAllowedLatenessMillis() {
        return allowedLatenessMillis;
    }

    public void setAllowedLatenessMillis(long allowedLatenessMillis) {
        this.allowedLatenessMillis = allowedLatenessMillis;
    }

    public long getRetentionMillis() {
        return retentionMillis;
    }

    public void setRetentionMillis(long retentionMillis) {
        this.retentionMillis = retentionMillis;
    }

    public long getEvaluationPeriodMillis() {
        return evaluationPeriodMillis;
    }

    public void setEvaluationPeriodMillis(long evaluationPeriodMillis) {
        this.evaluationPeriodMillis = evaluationPeriodMillis;
    }
}
