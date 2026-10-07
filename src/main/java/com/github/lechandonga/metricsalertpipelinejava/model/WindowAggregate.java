package com.github.lechandonga.metricsalertpipelinejava.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Immutable aggregation of one series within one closed (or queried) window.
 *
 * <p>All statistic fields are carried on every result; {@link #statistic(Statistic)}
 * maps an alert rule to the value it evaluates. Gauge {@code last} is the mean of all
 * samples at the greatest timestamp so the result is order-independent.
 */
public record WindowAggregate(long windowStartMillis,
                              long windowEndMillis,
                              SeriesKey series,
                              long count,
                              double sum,
                              double min,
                              double max,
                              double avg,
                              double last,
                              Map<Double, Double> percentiles) {

    public double statistic(Statistic statistic) {
        return switch (statistic) {
            case COUNT -> count;
            case SUM -> sum;
            case AVG -> avg;
            case MIN -> min;
            case MAX -> max;
            case LAST -> last;
            case P50 -> percentile(50);
            case P90 -> percentile(90);
            case P95 -> percentile(95);
            case P99 -> percentile(99);
        };
    }

    public double percentile(double p) {
        return percentiles.getOrDefault(p, Double.NaN);
    }

    /** Builds an aggregate from samples which all belong to the same series and window. */
    public static WindowAggregate fromSamples(long windowStart,
                                              long windowEnd,
                                              SeriesKey series,
                                              List<MetricSample> samples) {
        long count = samples.size();
        double sum = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        long latestTs = Long.MIN_VALUE;
        double latestSum = 0;
        long latestCount = 0;
        List<Double> values = new ArrayList<>(samples.size());

        for (MetricSample s : samples) {
            double v = s.value();
            sum += v;
            min = Math.min(min, v);
            max = Math.max(max, v);
            values.add(v);
            if (s.timestampMillis() > latestTs) {
                latestTs = s.timestampMillis();
                latestSum = v;
                latestCount = 1;
            } else if (s.timestampMillis() == latestTs) {
                latestSum += v;
                latestCount++;
            }
        }

        values.sort(Comparator.naturalOrder());
        return new WindowAggregate(
                windowStart,
                windowEnd,
                series,
                count,
                sum,
                count == 0 ? Double.NaN : min,
                count == 0 ? Double.NaN : max,
                count == 0 ? Double.NaN : sum / count,
                count == 0 ? Double.NaN : latestSum / latestCount,
                Map.of(
                        50.0, nearestRank(values, 50),
                        90.0, nearestRank(values, 90),
                        95.0, nearestRank(values, 95),
                        99.0, nearestRank(values, 99)));
    }

    /**
     * Nearest-rank percentile with linear interpolation between adjacent ranks
     * (order-independent and deterministic).
     */
    private static double nearestRank(List<Double> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return Double.NaN;
        }
        double rank = percentile / 100.0 * (sorted.size() + 1);
        if (rank <= 1) {
            return sorted.get(0);
        }
        if (rank >= sorted.size()) {
            return sorted.get(sorted.size() - 1);
        }
        int lowIndex = (int) Math.floor(rank) - 1;
        double fraction = rank - Math.floor(rank);
        return sorted.get(lowIndex)
                + fraction * (sorted.get(lowIndex + 1) - sorted.get(lowIndex));
    }
}
