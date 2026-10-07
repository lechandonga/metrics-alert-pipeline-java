package com.github.lechandonga.metricsalertpipelinejava.service;

import com.github.lechandonga.metricsalertpipelinejava.model.NotificationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process channel: delivers by logging and records delivered ids in a set.
 *
 * <p>Fault simulation: {@link #failNext(int)} makes the next N deliveries throw,
 * modelling a broken sink; because delivery is idempotent, a later retry of the same
 * notification is logged as a (successful) first delivery only once.
 */
@org.springframework.stereotype.Component
public class InMemoryNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(InMemoryNotificationChannel.class);

    private final Set<String> delivered = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicInteger failuresLeft =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Injects {@code n} consecutive failing delivery attempts. */
    public void failNext(int n) {
        failuresLeft.addAndGet(n);
    }

    @Override
    public void send(NotificationMessage message) {
        if (failuresLeft.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new NotificationDeliveryException(
                    "simulated channel failure for notification " + message.notificationId());
        }
        if (delivered.add(message.notificationId())) {
            log.info("[NOTIFY:DELIVERED] rule={} series={} type={} window=[{},{}) value={} threshold={} id={}",
                    message.ruleName(), message.series(), message.type(),
                    message.windowStartMillis(), message.windowEndMillis(),
                    message.observedValue(), message.threshold(), message.notificationId());
        } else {
            log.info("[NOTIFY:DUPLICATE-SUPPRESSED] id={} type={}",
                    message.notificationId(), message.type());
        }
    }

    public boolean wasDelivered(String notificationId) {
        return delivered.contains(notificationId);
    }

    public int deliveredCount() {
        return delivered.size();
    }

    /** Test/admin helper to clear the channel state. */
    public void reset() {
        delivered.clear();
        failuresLeft.set(0);
    }
}
