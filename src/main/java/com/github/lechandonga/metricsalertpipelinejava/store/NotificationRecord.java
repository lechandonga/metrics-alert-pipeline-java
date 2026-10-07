package com.github.lechandonga.metricsalertpipelinejava.store;

import com.github.lechandonga.metricsalertpipelinejava.model.DeliveryStatus;
import com.github.lechandonga.metricsalertpipelinejava.model.NotificationMessage;

/**
 * Mutable outbox entry. State transitions and outbox append happen inside the same
 * rule lock, so a lifecycle edge can never exist without its notification record.
 * Delivery attempts happen later and mutate only the delivery fields.
 */
public final class NotificationRecord {

    private final NotificationMessage message;
    private DeliveryStatus status = DeliveryStatus.PENDING;
    private int attempts;
    private String lastError;
    private Long deliveredAtMillis;

    public NotificationRecord(NotificationMessage message) {
        this.message = message;
    }

    /** Marks a delivery attempt; returns true if this attempt succeeded. */
    public synchronized boolean recordAttempt(boolean success, String error, long nowMillis) {
        attempts++;
        if (success) {
            status = DeliveryStatus.DELIVERED;
            lastError = null;
            deliveredAtMillis = nowMillis;
        } else {
            status = DeliveryStatus.FAILED;
            lastError = error;
        }
        return success;
    }

    public NotificationMessage getMessage() {
        return message;
    }

    public synchronized DeliveryStatus getStatus() {
        return status;
    }

    public synchronized int getAttempts() {
        return attempts;
    }

    public synchronized String getLastError() {
        return lastError;
    }

    public synchronized Long getDeliveredAtMillis() {
        return deliveredAtMillis;
    }
}
