package com.github.lechandonga.metricsalertpipelinejava.notify;

/**
 * 通知投递通道。实现可模拟故障：投递失败时抛出 NotificationDeliveryException。
 */
public interface NotificationChannel {

    void send(Notification notification);
}
