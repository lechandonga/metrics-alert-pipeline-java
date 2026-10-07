package com.github.lechandonga.metricsalertpipelinejava.notify;

import com.github.lechandonga.metricsalertpipelinejava.alert.AlertEvent;

/**
 * 一次告警状态跃迁对应的通知。id 全局唯一，用于去重与重试。
 */
public record Notification(String id, String ruleId, String metricName,
                           AlertEvent event, double value, long occurredAt) {
}
