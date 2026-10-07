package com.github.lechandonga.metricsalertpipelinejava.alert;

import java.util.Map;
import java.util.UUID;

/**
 * 告警规则：对某指标的窗口聚合值做阈值判定，
 * 连续 triggerWindows 个窗口满足条件才触发，连续 recoverWindows 个窗口正常才恢复。
 */
public record AlertRule(String id, String metricName, Map<String, String> tags,
                        ComparisonOp op, double threshold,
                        int triggerWindows, int recoverWindows) {

    public AlertRule {
        if (triggerWindows < 1 || recoverWindows < 1) {
            throw new IllegalArgumentException("triggerWindows/recoverWindows must be >= 1");
        }
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }

    public static AlertRule of(String metricName, ComparisonOp op, double threshold,
                               int triggerWindows, int recoverWindows) {
        return new AlertRule(UUID.randomUUID().toString(), metricName, Map.of(),
                op, threshold, triggerWindows, recoverWindows);
    }
}
