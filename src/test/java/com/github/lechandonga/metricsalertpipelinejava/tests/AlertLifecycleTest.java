package com.github.lechandonga.metricsalertpipelinejava.tests;

import com.github.lechandonga.metricsalertpipelinejava.model.AlertRule;
import com.github.lechandonga.metricsalertpipelinejava.model.AlertStatus;
import com.github.lechandonga.metricsalertpipelinejava.model.DeliveryStatus;
import com.github.lechandonga.metricsalertpipelinejava.model.MetricType;
import com.github.lechandonga.metricsalertpipelinejava.model.NotificationMessage;
import com.github.lechandonga.metricsalertpipelinejava.model.NotificationType;
import com.github.lechandonga.metricsalertpipelinejava.service.AlertService;
import com.github.lechandonga.metricsalertpipelinejava.support.BusinessModule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@BusinessModule("告警生命周期：待定/触发/恢复去抖，抖动不重复通知")
class AlertLifecycleTest extends AbstractServiceTest {

    private AlertRule cpuRule(int trigger, int recover) {
        register("cpu", MetricType.GAUGE);
        return alertService.createRule(new AlertService.RuleDraft(
                "cpu-high", "cpu", Map.of(), "AVG", "GT", 50.0, trigger, recover));
    }

    private AlertStatus status(AlertRule rule) {
        var states = alertService.alertStates(rule.id());
        return states.isEmpty() ? AlertStatus.INACTIVE : states.get(0).status();
    }

    private void valueAtWindow(long windowIndex, double value) {
        clock.set(Math.max(clock.nowMillis(), T0 + windowIndex * WINDOW + 5_000));
        ingest(sample("cpu", MetricType.GAUGE,
                T0 + windowIndex * WINDOW + 1_000, value));
    }

    private void advanceEvaluate(long windowIndex) {
        clock.set(T0 + (windowIndex + 1) * WINDOW + LATENESS);
        evaluate();
    }

    @Test
    void pendingRequiresConsecutiveBreachesBeforeFiring() {
        AlertRule rule = cpuRule(2, 2);

        valueAtWindow(0, 80);
        advanceEvaluate(0);
        assertEquals(AlertStatus.PENDING, status(rule), "单窗口越限只进入待定");
        assertTrue(alertService.listNotifications(null).isEmpty(), "待定不发送通知");

        valueAtWindow(1, 20);
        advanceEvaluate(1);
        assertEquals(AlertStatus.INACTIVE, status(rule), "未连续越限则回落到未激活");

        valueAtWindow(2, 70);
        valueAtWindow(3, 90);
        advanceEvaluate(3);
        assertEquals(AlertStatus.FIRING, status(rule), "连续两个窗口越限才触发");
        List<NotificationMessage> notifications = alertService.listNotifications(null);
        assertEquals(1, notifications.size());
        assertEquals(NotificationType.TRIGGERED, notifications.get(0).type());
    }

    @Test
    void jitterDuringFiringDoesNotRenotifyAndRecoveryNeedsConsecutiveHealthy() {
        AlertRule rule = cpuRule(2, 2);
        valueAtWindow(0, 60);
        valueAtWindow(1, 60);
        advanceEvaluate(1);
        assertEquals(AlertStatus.FIRING, status(rule));

        valueAtWindow(2, 10);
        advanceEvaluate(2);
        assertEquals(AlertStatus.RECOVERING, status(rule), "单个正常窗口只进入恢复待定");
        assertEquals(1, alertService.listNotifications(null).size(), "恢复待定无新通知");

        valueAtWindow(3, 95);
        advanceEvaluate(3);
        assertEquals(AlertStatus.FIRING, status(rule), "抖动：恢复期间再次越限回到触发");
        assertEquals(1, alertService.listNotifications(null).size(), "抖动不得重复触发通知");

        valueAtWindow(4, 5);
        valueAtWindow(5, 5);
        advanceEvaluate(5);
        assertEquals(AlertStatus.INACTIVE, status(rule), "连续两个正常窗口才恢复");
        List<NotificationMessage> notifications = alertService.listNotifications(null);
        assertEquals(2, notifications.size(), "整个生命周期恰好触发+恢复各一次");
        assertEquals(NotificationType.RECOVERED, notifications.get(1).type());
    }

    @Test
    void repeatedEvaluationIsIdempotent() {
        AlertRule rule = cpuRule(1, 1);
        valueAtWindow(0, 99);
        advanceEvaluate(0);
        assertEquals(AlertStatus.FIRING, status(rule));

        evaluate();
        clock.advance(1);
        evaluate();
        evaluate();

        assertEquals(1, alertService.listNotifications(null).size(),
                "水位不回退，重复评估不得重复通知");
    }

    @Test
    void emptyWindowsAreHealthyAndNeverFire() {
        AlertRule rule = cpuRule(1, 1);
        advanceEvaluate(0);
        advanceEvaluate(1);
        assertEquals(AlertStatus.INACTIVE, status(rule), "无数据窗口按正常处理，不得误报");
        assertTrue(alertService.listNotifications(null).isEmpty());

        valueAtWindow(2, 80);
        advanceEvaluate(2);
        assertEquals(AlertStatus.FIRING, status(rule), "随后真实越限仍能正常触发");

        advanceEvaluate(3);
        assertEquals(AlertStatus.INACTIVE, status(rule), "随后空窗口按正常处理完成恢复");
        assertEquals(DeliveryStatus.DELIVERED,
                alertService.notification(
                        alertService.listNotifications(null).get(0).notificationId()).get("status"),
                "触发通知应已成功投递");
    }
}
