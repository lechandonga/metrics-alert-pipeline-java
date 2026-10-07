package com.github.lechandonga.metricsalertpipelinejava.window;

import com.github.lechandonga.metricsalertpipelinejava.metric.MetricKey;
import com.github.lechandonga.metricsalertpipelinejava.metric.MetricType;
import com.github.lechandonga.metricsalertpipelinejava.metric.Sample;

/**
 * 窗口内的累加器，所有更新在锁内完成。
 */
class WindowBucket {

    private final MetricKey key;
    private final MetricType type;
    private final long windowStart;
    private final long windowEnd;
    private long count;
    private double sum;
    private double min = Double.POSITIVE_INFINITY;
    private double max = Double.NEGATIVE_INFINITY;
    private long lastTimestamp = Long.MIN_VALUE;
    private double lastValue;

    WindowBucket(MetricKey key, MetricType type, long windowStart, long windowEnd) {
        this.key = key;
        this.type = type;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
    }

    synchronized void add(Sample sample) {
        count++;
        sum += sample.value();
        min = Math.min(min, sample.value());
        max = Math.max(max, sample.value());
        // gauge 取“最新值”，时间戳相同取较大值，保证与到达顺序无关
        if (sample.timestamp() > lastTimestamp
                || (sample.timestamp() == lastTimestamp && sample.value() > lastValue)) {
            lastTimestamp = sample.timestamp();
            lastValue = sample.value();
        }
    }

    synchronized WindowAggregate toAggregate() {
        double value = switch (type) {
            case COUNTER -> sum;
            case GAUGE -> lastValue;
            case DISTRIBUTION -> count == 0 ? 0 : sum / count;
        };
        return new WindowAggregate(key, type, windowStart, windowEnd, count, sum, min, max, value);
    }
}
