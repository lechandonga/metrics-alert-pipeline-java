package com.github.lechandonga.metricsalertpipelinejava.window;

import com.github.lechandonga.metricsalertpipelinejava.metric.MetricKey;
import com.github.lechandonga.metricsalertpipelinejava.metric.MetricType;

/**
 * 一个滚动窗口关闭后的聚合结果。
 * 聚合语义与样本到达顺序无关：counter 求和、gauge 取最后值（同刻取最大）、
 * distribution 统计 count/min/max/sum/avg。
 */
public record WindowAggregate(MetricKey key, MetricType type, long windowStart, long windowEnd,
                              long count, double sum, double min, double max, double value) {

    public double avg() {
        return count == 0 ? 0 : sum / count;
    }
}
