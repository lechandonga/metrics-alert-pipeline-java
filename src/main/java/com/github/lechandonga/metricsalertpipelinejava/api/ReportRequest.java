package com.github.lechandonga.metricsalertpipelinejava.api;

import java.util.Map;

import com.github.lechandonga.metricsalertpipelinejava.metric.MetricType;
import com.github.lechandonga.metricsalertpipelinejava.metric.Sample;
import com.github.lechandonga.metricsalertpipelinejava.metric.SampleRejectedException;
import com.github.lechandonga.metricsalertpipelinejava.metric.ValidationReason;

public record ReportRequest(String name, MetricType type, Double value, Long timestamp,
                            Map<String, String> tags) {

    public Sample toSample() {
        if (value == null) {
            throw new SampleRejectedException(ValidationReason.INVALID_VALUE, "value is required");
        }
        if (timestamp == null) {
            throw new SampleRejectedException(ValidationReason.INVALID_TIMESTAMP, "timestamp is required");
        }
        return new Sample(name, type, value, timestamp, tags);
    }
}
