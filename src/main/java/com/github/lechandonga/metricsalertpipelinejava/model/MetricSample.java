package com.github.lechandonga.metricsalertpipelinejava.model;

import java.util.Collections;
import java.util.Map;

/**
 * An immutable, validated single observation.
 *
 * <p>Instances are created only by the ingestion service after full validation, therefore
 * readers can never observe a partially updated sample. {@code tags} is an unmodifiable,
 * insertion-ordered copy so iteration order is deterministic.
 */
public record MetricSample(String metric,
                           MetricType type,
                           long timestampMillis,
                           double value,
                           Map<String, String> tags)
        implements Comparable<MetricSample> {

    public MetricSample {
        tags = canonical(tags);
    }

    public SeriesKey seriesKey() {
        return new SeriesKey(metric, tags);
    }

    static Map<String, String> canonical(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new java.util.TreeMap<>(raw));
    }

    /**
     * Total ordering used by range queries: timestamp first, then the fully qualified series.
     * Ties within the same series are broken by value so equal samples stay orderable.
     */
    @Override
    public int compareTo(MetricSample other) {
        int byTime = Long.compare(this.timestampMillis, other.timestampMillis);
        if (byTime != 0) {
            return byTime;
        }
        int bySeries = this.seriesKey().compareTo(other.seriesKey());
        if (bySeries != 0) {
            return bySeries;
        }
        return Double.compare(this.value, other.value);
    }
}
