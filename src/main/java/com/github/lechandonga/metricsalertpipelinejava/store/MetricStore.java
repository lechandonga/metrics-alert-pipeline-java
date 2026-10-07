package com.github.lechandonga.metricsalertpipelinejava.store;

import com.github.lechandonga.metricsalertpipelinejava.model.MetricSample;
import com.github.lechandonga.metricsalertpipelinejava.model.MetricType;
import com.github.lechandonga.metricsalertpipelinejava.model.SeriesKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process metric registry and sample store.
 *
 * <p>Concurrency model: the metric registry is a {@link ConcurrentHashMap}; each series
 * owns its own lock and a timestamp-ordered {@link TreeMap} of samples. Writes append
 * under the series lock (no loss, no duplication), while reads take a snapshot of the
 * submap under the same lock and never observe a half-applied state. Distinct series are
 * fully parallel.
 */
@org.springframework.stereotype.Component
public class MetricStore {

    private final ConcurrentHashMap<String, MetricType> metricTypes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SeriesKey, Series> series = new ConcurrentHashMap<>();

    /**
     * Registers a metric. A duplicate registration with the same type is a no-op;
     * a conflicting type returns the existing type so the caller can reject the request.
     */
    public MetricType register(String metric, MetricType type) {
        MetricType existing = metricTypes.putIfAbsent(metric, type);
        return existing != null ? existing : type;
    }

    public MetricType metricType(String metric) {
        return metricTypes.get(metric);
    }

    public Map<String, MetricType> registeredMetrics() {
        return Map.copyOf(metricTypes);
    }

    /** Appends a validated sample; identity (equals on immutable records) prevents doubles. */
    public void append(MetricSample sample) {
        seriesFor(sample.seriesKey()).append(sample);
    }

    /** Reads a single series over [fromMillis, toMillis] in timestamp/value order. */
    public List<MetricSample> query(SeriesKey key, long fromMillis, long toMillis) {
        Series existing = series.get(key);
        if (existing == null) {
            return List.of();
        }
        return existing.range(fromMillis, toMillis);
    }

    /** Reads every series of a metric matching the exact tag filter over the range. */
    public List<MetricSample> query(String metric,
                                   Map<String, String> tagFilter,
                                   long fromMillis,
                                   long toMillis) {
        List<MetricSample> out = new ArrayList<>();
        for (Map.Entry<SeriesKey, Series> entry : series.entrySet()) {
            SeriesKey key = entry.getKey();
            if (key.metric().equals(metric) && matches(key.tags(), tagFilter)) {
                out.addAll(entry.getValue().range(fromMillis, toMillis));
            }
        }
        out.sort(null);
        return out;
    }

    public List<SeriesKey> seriesKeys(String metric) {
        return series.keySet().stream()
                .filter(k -> k.metric().equals(metric))
                .sorted()
                .map(k -> (SeriesKey) k)
                .toList();
    }

    /** Earliest stored timestamp across all series of the metric, or empty. */
    public java.util.OptionalLong earliestTimestamp(String metric) {
        long earliest = Long.MAX_VALUE;
        for (Map.Entry<SeriesKey, Series> entry : series.entrySet()) {
            if (!entry.getKey().metric().equals(metric)) {
                continue;
            }
            Long first = entry.getValue().firstTimestamp();
            if (first != null && first < earliest) {
                earliest = first;
            }
        }
        return earliest == Long.MAX_VALUE
                ? java.util.OptionalLong.empty()
                : java.util.OptionalLong.of(earliest);
    }

    /** Removes samples strictly older than {@code olderThanMillis}; returns removed count. */
    public int removeExpired(long olderThanMillis) {
        int removed = 0;
        for (Series s : series.values()) {
            removed += s.removeBefore(olderThanMillis);
        }
        return removed;
    }

    private Series seriesFor(SeriesKey key) {
        return series.computeIfAbsent(key, k -> new Series());
    }

    private static boolean matches(Map<String, String> actual, Map<String, String> filter) {
        for (Map.Entry<String, String> e : filter.entrySet()) {
            if (!e.getValue().equals(actual.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** One series: timestamp -> samples observed at that timestamp. */
    private static final class Series {
        private final TreeMap<Long, List<MetricSample>> byTimestamp = new TreeMap<>();

        synchronized void append(MetricSample sample) {
            byTimestamp.computeIfAbsent(sample.timestampMillis(), t -> new ArrayList<>())
                    .add(sample);
        }

        synchronized List<MetricSample> range(long fromMillis, long toMillis) {
            NavigableMap<Long, List<MetricSample>> sub =
                    byTimestamp.subMap(fromMillis, true, toMillis, true);
            List<MetricSample> out = new ArrayList<>();
            for (List<MetricSample> bucket : sub.values()) {
                out.addAll(bucket);
            }
            return out;
        }

        synchronized int removeBefore(long olderThanMillis) {
            NavigableMap<Long, List<MetricSample>> dead = byTimestamp.headMap(olderThanMillis, false);
            int count = dead.values().stream().mapToInt(List::size).sum();
            dead.clear();
            return count;
        }

        synchronized Long firstTimestamp() {
            return byTimestamp.isEmpty() ? null : byTimestamp.firstKey();
        }
    }
}
