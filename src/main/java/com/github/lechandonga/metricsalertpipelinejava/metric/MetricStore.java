package com.github.lechandonga.metricsalertpipelinejava.metric;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 进程内指标存储。并发上报通过 compute 原子建序列 + 序列内锁保证不丢不重。
 */
@Component
public class MetricStore {

    private final ConcurrentHashMap<MetricKey, MetricSeries> series = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MetricType> typesByName = new ConcurrentHashMap<>();
    private final SampleValidator validator;

    public MetricStore(SampleValidator validator) {
        this.validator = validator;
    }

    public Sample ingest(Sample sample) {
        validator.validate(sample);
        MetricType existing = typesByName.putIfAbsent(sample.name(), sample.type());
        if (existing != null && existing != sample.type()) {
            throw new SampleRejectedException(ValidationReason.TYPE_CONFLICT,
                    "metric " + sample.name() + " already registered as " + existing);
        }
        MetricSeries s = series.computeIfAbsent(sample.key(), k -> new MetricSeries(sample.type()));
        s.add(sample);
        return sample;
    }

    public List<Sample> query(String name, long fromInclusive, long toInclusive) {
        List<Sample> result = new ArrayList<>();
        for (Map.Entry<MetricKey, MetricSeries> entry : series.entrySet()) {
            if (entry.getKey().name().equals(name)) {
                result.addAll(entry.getValue().query(fromInclusive, toInclusive));
            }
        }
        return result;
    }

    public long sampleCount() {
        long total = 0;
        for (MetricSeries s : series.values()) {
            total += s.count();
        }
        return total;
    }
}
