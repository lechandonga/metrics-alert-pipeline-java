package com.github.lechandonga.metricsalertpipelinejava.notify;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

/**
 * 通知派发器：
 * - 每个通知只投递成功一次（按 id 去重，不会重复通知）；
 * - 投递失败被记录为 FAILED 并进入待重试集合，可显式重试；
 * - 投递结果与告警状态相互独立记录，不会互相矛盾。
 */
@Component
public class NotificationDispatcher {

    private final NotificationChannel channel;
    private final List<DeliveryRecord> records = new CopyOnWriteArrayList<>();
    private final Set<String> deliveredIds = ConcurrentHashMap.newKeySet();
    private final Map<String, Notification> pendingRetry = new ConcurrentHashMap<>();

    public NotificationDispatcher(NotificationChannel channel) {
        this.channel = channel;
    }

    /**
     * 派发通知。失败时返回 false 并登记待重试，绝不静默丢失。
     */
    public boolean dispatch(Notification notification) {
        if (deliveredIds.contains(notification.id())) {
            return true;
        }
        try {
            channel.send(notification);
        } catch (RuntimeException e) {
            records.add(new DeliveryRecord(notification.id(), DeliveryStatus.FAILED,
                    e.getMessage(), Instant.now()));
            pendingRetry.put(notification.id(), notification);
            return false;
        }
        deliveredIds.add(notification.id());
        pendingRetry.remove(notification.id());
        records.add(new DeliveryRecord(notification.id(), DeliveryStatus.DELIVERED, "ok", Instant.now()));
        return true;
    }

    /**
     * 重试所有投递失败的通知，返回仍失败的数量。
     */
    public int retryFailed() {
        int stillFailing = 0;
        for (Notification n : new ArrayList<>(pendingRetry.values())) {
            if (!dispatch(n)) {
                stillFailing++;
            }
        }
        return stillFailing;
    }

    public List<DeliveryRecord> records() {
        return List.copyOf(records);
    }

    public List<Notification> pendingRetries() {
        return List.copyOf(pendingRetry.values());
    }

    public boolean isDelivered(String notificationId) {
        return deliveredIds.contains(notificationId);
    }
}
