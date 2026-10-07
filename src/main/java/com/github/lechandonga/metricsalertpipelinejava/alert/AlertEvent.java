package com.github.lechandonga.metricsalertpipelinejava.alert;

/**
 * 状态机在一次窗口判定后产生的副作用：需要发送的通知类型，无变化时为 NONE。
 */
public enum AlertEvent {
    NONE,
    TRIGGERED,
    RESOLVED
}
