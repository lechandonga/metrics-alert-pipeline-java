package com.github.lechandonga.metricsalertpipelinejava.tests;

import com.github.lechandonga.metricsalertpipelinejava.model.AlertRule;
import com.github.lechandonga.metricsalertpipelinejava.model.AlertStatus;
import com.github.lechandonga.metricsalertpipelinejava.model.DeliveryStatus;
import com.github.lechandonga.metricsalertpipelinejava.model.MetricType;
import com.github.lechandonga.metricsalertpipelinejava.model.NotificationMessage;
import com.github.lechandonga.metricsalertpipelinejava.service.AlertRuleException;
import com.github.lechandonga.metricsalertpipelinejava.service.AlertService;
import com.github.lechandonga.metricsalertpipelinejava.model.RejectReason;
import com.github.lechandonga.metricsalertpipelinejava.store.NotificationRecord;
import com.github.lechandonga.metricsalertpipelinejava.support.BusinessModule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@BusinessModule("通知可靠性：状态与投递分离、失败可重试、重试不重复送达")
class NotificationRetryTest extends AbstractServiceTest {

    private AlertRule fireRule() {
        register("qps", MetricType.COUNTER);
        return alertService.createRule(new AlertService.RuleDraft(
                "qps-high", "qps", Map.of(), "SUM", "GTE", 10.0, 1, 1));
    }

    private NotificationMessage fireOnceWith(AlertRule rule) {
        clock.set(T0 + 5_000);
        for (int i = 0; i < 10; i++) {
            ingest(sample("qps", MetricType.COUNTER, T0 + i, 1.0));
        }
        clock.set(T0 + WINDOW + LATENESS);
        assertEquals(1, alertService.evaluateAll(clock.nowMillis()));
        return alertService.listNotifications(null).get(0);
    }

    @Test
    void firingStatePersistsWhileFirstDeliveryFails() {
        AlertRule rule = fireRule();
        channel.failNext(1);

        NotificationMessage message = fireOnceWith(rule);

        assertEquals(AlertStatus.FIRING, alertService.alertStates(rule.id()).get(0).status(),
                "通道故障不影响告警状态：仍为 FIRING");
        Map<String, Object> view = alertService.notification(message.notificationId());
        assertEquals(DeliveryStatus.FAILED, view.get("status"),
                "投递结果独立可查：FAILED");
        assertEquals(1, view.get("attempts"), "故障尝试被计数");
        assertTrue(((String) view.get("lastError")).contains("simulated channel failure"),
                "失败原因可区分");
        assertEquals(0, channel.deliveredCount(), "未成功送达不得记入已送达");
    }

    @Test
    void explicitRetryDeliversExactlyOnceAfterRecovery() {
        AlertRule rule = fireRule();
        channel.failNext(1);
        NotificationMessage message = fireOnceWith(rule);

        NotificationRecord retried = alertService.retry(message.notificationId());
        assertEquals(DeliveryStatus.DELIVERED, retried.getStatus());
        assertEquals(2, retried.getAttempts());
        assertTrue(channel.wasDelivered(message.notificationId()));

        AlertRuleException e = assertThrows(AlertRuleException.class,
                () -> alertService.retry(message.notificationId()));
        assertEquals(RejectReason.NOTIFICATION_ALREADY_DELIVERED, e.reason(),
                "重复重试必须被明确拒绝");
        assertEquals(1, channel.deliveredCount(), "底层通道幂等，只真正送达一次");
    }

    @Test
    void concurrentRetriesDeliverAtMostOnce() throws Exception {
        AlertRule rule = fireRule();
        channel.failNext(1);
        NotificationMessage message = fireOnceWith(rule);

        int threads = 12;
        java.util.concurrent.CountDownLatch start =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.atomic.AtomicInteger delivered =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger rejected =
                new java.util.concurrent.atomic.AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    NotificationRecord r = alertService.retry(message.notificationId());
                    if (r.getStatus() == DeliveryStatus.DELIVERED) {
                        delivered.incrementAndGet();
                    }
                } catch (AlertRuleException ex) {
                    if (ex.reason() == RejectReason.NOTIFICATION_ALREADY_DELIVERED) {
                        rejected.incrementAndGet();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));

        assertEquals(1, delivered.get(), "并发重试下只有一次成功返回");
        assertEquals(1, channel.deliveredCount(), "并发重试绝不重复送达");
        assertEquals(threads - 1, rejected.get(), "其余并发请求得到明确的已投递拒绝");
    }

    @Test
    void retryPendingRetriesOnlyUndeliveredAndBatchSucceedsAfterFaultClears() {
        AlertRule rule = fireRule();
        channel.failNext(2);
        NotificationMessage first = fireOnceWith(rule);

        clock.set(T0 + WINDOW + 5_000);
        for (int i = 0; i < 10; i++) {
            ingest(sample("qps", MetricType.COUNTER, T0 + WINDOW + i, 1.0));
        }
        clock.set(T0 + 2 * WINDOW + 5_000);
        for (int i = 0; i < 10; i++) {
            ingest(sample("qps", MetricType.COUNTER, T0 + 2 * WINDOW + i, 1.0));
        }
        assertEquals(1, alertService.listNotifications("FAILED").size(),
                "故障期触发通知保持 FAILED");

        // Still breaching in windows 1 and 2: no new lifecycle edges.
        clock.set(T0 + 2 * WINDOW + LATENESS);
        alertService.evaluateAll(clock.nowMillis());
        clock.set(T0 + 3 * WINDOW + LATENESS);
        alertService.evaluateAll(clock.nowMillis());
        assertEquals(1, alertService.listNotifications("FAILED").size(),
                "持续越限不产生新通知，故障的触发通知保持 FAILED");

        List<NotificationRecord> retried = alertService.retryPending();
        assertEquals(1, retried.size(), "唯一未投递通知被批量重试");
        // First retry attempt still consumes the second injected failure; retry once more.
        if (retried.get(0).getStatus() != DeliveryStatus.DELIVERED) {
            retried = alertService.retryPending();
        }
        assertEquals(1, retried.size());
        assertTrue(retried.stream().allMatch(r -> r.getStatus() == DeliveryStatus.DELIVERED));
        assertTrue(alertService.retryPending().isEmpty(), "无待投递时批量重试为空");
        assertEquals(1, channel.deliveredCount(), "通知仅送达一次");

        AlertRuleException missing = assertThrows(AlertRuleException.class,
                () -> alertService.retry("notif-404"));
        assertEquals(RejectReason.NOTIFICATION_NOT_FOUND, missing.reason());
    }
}
