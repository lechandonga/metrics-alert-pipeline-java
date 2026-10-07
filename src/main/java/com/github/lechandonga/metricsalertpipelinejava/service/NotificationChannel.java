package com.github.lechandonga.metricsalertpipelinejava.service;

import com.github.lechandonga.metricsalertpipelinejava.model.NotificationMessage;

/**
 * Local notification sink. Implementations must be idempotent on
 * {@link NotificationMessage#notificationId()} so retries never double-deliver.
 * Throwing (or returning false) signals delivery failure; the outbox keeps the
 * notification PENDING/FAILED for an explicit retry.
 */
public interface NotificationChannel {
    void send(NotificationMessage message);
}
