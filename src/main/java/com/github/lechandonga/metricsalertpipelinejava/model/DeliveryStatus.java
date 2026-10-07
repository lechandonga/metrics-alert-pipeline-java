package com.github.lechandonga.metricsalertpipelinejava.model;

/**
 * Delivery state of an outbox notification, kept independent of the alert state machine:
 * an alert can be FIRING while its notification is still PENDING (channel failed).
 */
public enum DeliveryStatus {
    PENDING,
    DELIVERED,
    FAILED
}
