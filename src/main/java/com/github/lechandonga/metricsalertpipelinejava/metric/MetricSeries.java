package com.github.lechandonga.metricsalertpipelinejava.metric;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * 单个指标序列（name+tags）的样本存储。所有写操作在锁内完成，
 * 查询返回快照，读写不会看到半更新状态。
 */
class MetricSeries {

    private final MetricType type;
    private final TreeMap<Long, List<Sample>> samplesByTimestamp = new TreeMap<>();
    private long count;

    MetricSeries(MetricType type) {
        this.type = type;
    }

    MetricType type() {
        return type;
    }

    synchronized void add(Sample sample) {
        samplesByTimestamp.computeIfAbsent(sample.timestamp(), t -> new ArrayList<>()).add(sample);
        count++;
    }

    synchronized List<Sample> query(long fromInclusive, long toInclusive) {
        List<Sample> result = new ArrayList<>();
        for (List<Sample> bucket : samplesByTimestamp.subMap(fromInclusive, true, toInclusive, true).values()) {
            result.addAll(bucket);
        }
        return result;
    }

    synchronized long count() {
        return count;
    }
}
