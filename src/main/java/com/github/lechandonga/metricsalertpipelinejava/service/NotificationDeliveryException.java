package com.github.lechandonga.metricsalertpipelinejava.service;

/** Raised by a channel to signal a failed delivery attempt; the outbox retains the message. */
public class NotificationDeliveryException extends RuntimeException {
    public NotificationDeliveryException(String message) {
        super(message);
    }
}
