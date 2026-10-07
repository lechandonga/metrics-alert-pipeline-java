package com.github.lechandonga.metricsalertpipelinejava.notify;

import java.time.Instant;

/**
 * 一次投递尝试的记录，成功与失败都可区分地查询。
 */
public record DeliveryRecord(String notificationId, DeliveryStatus status,
                             String detail, Instant attemptedAt) {
}
