package com.github.lechandonga.metricsalertpipelinejava.model;

import java.util.Collections;
import java.util.Map;

/**
 * Identity of a single time series: metric name plus an exact tag set.
 * The precomputed canonical string is used both for comparison and debug output.
 */
public record SeriesKey(String metric, Map<String, String> tags) implements Comparable<SeriesKey> {

    private static final String[] SENSITIVE = {"=", ",", "\\"};

    public SeriesKey {
        tags = MetricSample.canonical(tags);
    }

    /** Canonical form: {@code metric{k1=v1,k2=v2}}, keys sorted lexicographically. */
    public String canonical() {
        if (tags.isEmpty()) {
            return metric + "{}";
        }
        StringBuilder sb = new StringBuilder(metric).append('{');
        tags.keySet().stream().sorted().forEach(key -> {
            if (sb.charAt(sb.length() - 1) != '{') {
                sb.append(',');
            }
            sb.append(escape(key)).append('=').append(escape(tags.get(key)));
        });
        return sb.append('}').toString();
    }

    @Override
    public String toString() {
        return canonical();
    }

    @Override
    public int compareTo(SeriesKey other) {
        int byMetric = this.metric.compareTo(other.metric);
        if (byMetric != 0) {
            return byMetric;
        }
        return this.canonical().compareTo(other.canonical());
    }

    private static String escape(String raw) {
        String out = raw;
        for (String s : SENSITIVE) {
            out = out.replace(s, "\\" + s);
        }
        return out;
    }
}
