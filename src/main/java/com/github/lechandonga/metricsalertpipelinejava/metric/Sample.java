package com.github.lechandonga.metricsalertpipelinejava.metric;

import java.util.Map;
import java.util.TreeMap;

/**
 * 一条指标样本。tags 在构造时排序，保证同一逻辑序列的 key 稳定。
 */
public record Sample(String name, MetricType type, double value, long timestamp,
                     Map<String, String> tags) {

    public Sample {
        tags = tags == null ? Map.of() : Map.copyOf(new TreeMap<>(tags));
    }

    public MetricKey key() {
        return new MetricKey(name, tags);
    }
}
