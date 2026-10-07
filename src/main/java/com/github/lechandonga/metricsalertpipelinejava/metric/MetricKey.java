package com.github.lechandonga.metricsalertpipelinejava.metric;

import java.util.Map;
import java.util.TreeMap;

public record MetricKey(String name, Map<String, String> tags) {

    public MetricKey {
        tags = tags == null ? Map.of() : Map.copyOf(new TreeMap<>(tags));
    }
}
