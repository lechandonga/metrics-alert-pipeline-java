package com.github.lechandonga.metricsalertpipelinejava.notify;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

/**
 * 本地内存通知通道，可通过 setFailing 模拟通道故障。
 */
@Component
public class InMemoryNotificationChannel implements NotificationChannel {

    private final List<Notification> sent = new CopyOnWriteArrayList<>();
    private final AtomicBoolean failing = new AtomicBoolean(false);

    @Override
    public void send(Notification notification) {
        if (failing.get()) {
            throw new NotificationDeliveryException("notification channel is down");
        }
        sent.add(notification);
    }

    public void setFailing(boolean failing) {
        this.failing.set(failing);
    }

    public List<Notification> sent() {
        return List.copyOf(sent);
    }
}
