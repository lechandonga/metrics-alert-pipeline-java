package com.github.lechandonga.metricsalertpipelinejava.alert;

/**
 * 单条规则 + 单个指标序列的告警状态机实例。
 * 连续满足 triggerWindows 次才触发，连续正常 recoverWindows 次才恢复；
 * 中间任何一次反向窗口都会重置计数，短暂抖动不会造成反复触发。
 */
public class AlertInstance {

    private final AlertRule rule;
    private AlertState state = AlertState.INACTIVE;
    private int consecutiveBreaches;
    private int consecutiveRecoveries;

    public AlertInstance(AlertRule rule) {
        this.rule = rule;
    }

    public AlertRule rule() {
        return rule;
    }

    public synchronized AlertState state() {
        return state;
    }

    /**
     * 输入一个已关闭窗口的判定结果，返回需要触发的通知事件。
     */
    public synchronized AlertEvent evaluate(boolean breached) {
        switch (state) {
            case INACTIVE -> {
                if (breached) {
                    consecutiveBreaches = 1;
                    state = AlertState.PENDING;
                    return advanceIfReady();
                }
            }
            case PENDING -> {
                if (breached) {
                    consecutiveBreaches++;
                    return advanceIfReady();
                }
                consecutiveBreaches = 0;
                state = AlertState.INACTIVE;
            }
            case TRIGGERED -> {
                if (!breached) {
                    consecutiveRecoveries = 1;
                    state = AlertState.RECOVERING;
                    return resolveIfReady();
                }
            }
            case RECOVERING -> {
                if (!breached) {
                    consecutiveRecoveries++;
                    return resolveIfReady();
                }
                consecutiveRecoveries = 0;
                state = AlertState.TRIGGERED;
            }
        }
        return AlertEvent.NONE;
    }

    private AlertEvent advanceIfReady() {
        if (consecutiveBreaches >= rule.triggerWindows()) {
            consecutiveBreaches = 0;
            state = AlertState.TRIGGERED;
            return AlertEvent.TRIGGERED;
        }
        return AlertEvent.NONE;
    }

    private AlertEvent resolveIfReady() {
        if (consecutiveRecoveries >= rule.recoverWindows()) {
            consecutiveRecoveries = 0;
            state = AlertState.INACTIVE;
            return AlertEvent.RESOLVED;
        }
        return AlertEvent.NONE;
    }
}
