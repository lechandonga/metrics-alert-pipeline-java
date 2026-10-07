package com.github.lechandonga.metricsalertpipelinejava.alert;

/**
 * 告警生命周期状态机：INACTIVE -> PENDING -> TRIGGERED -> RECOVERING -> INACTIVE。
 */
public enum AlertState {
    INACTIVE,
    PENDING,
    TRIGGERED,
    RECOVERING
}
