package com.github.lechandonga.metricsalertpipelinejava.web;

import com.github.lechandonga.metricsalertpipelinejava.model.AlertRule;
import com.github.lechandonga.metricsalertpipelinejava.model.NotificationMessage;
import com.github.lechandonga.metricsalertpipelinejava.service.AlertService;
import com.github.lechandonga.metricsalertpipelinejava.service.InMemoryNotificationChannel;
import com.github.lechandonga.metricsalertpipelinejava.store.NotificationRecord;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/alerts")
public class AlertsController {

    private final AlertService alertService;
    private final InMemoryNotificationChannel channel;

    public AlertsController(AlertService alertService, InMemoryNotificationChannel channel) {
        this.alertService = alertService;
        this.channel = channel;
    }

    public record EvaluateResponse(int lifecycleEvents, long evaluatedAtMillis) {
    }

    public record FailNextRequest(int count) {
    }

    @PostMapping("/rules")
    public AlertRule createRule(@RequestBody AlertService.RuleDraft draft) {
        return alertService.createRule(draft);
    }

    @GetMapping("/rules")
    public List<AlertRule> rules() {
        return alertService.listRules();
    }

    /** Manually advances the watermark to the current clock and evaluates closed windows. */
    @PostMapping("/evaluate")
    public EvaluateResponse evaluate() {
        return new EvaluateResponse(alertService.evaluateAll(), System.currentTimeMillis());
    }

    @GetMapping("/rules/{ruleId}/states")
    public Object states(@PathVariable String ruleId) {
        return alertService.alertStates(ruleId);
    }

    @GetMapping("/notifications")
    public List<NotificationMessage> notifications(
            @RequestParam(required = false) String status) {
        return alertService.listNotifications(status);
    }

    @GetMapping("/notifications/{notificationId}")
    public Map<String, Object> notification(@PathVariable String notificationId) {
        return alertService.notification(notificationId);
    }

    @PostMapping("/notifications/{notificationId}/retry")
    public Map<String, Object> retry(@PathVariable String notificationId) {
        NotificationRecord record = alertService.retry(notificationId);
        return alertService.notification(record.getMessage().notificationId());
    }

    @PostMapping("/notifications/retry-pending")
    public List<Map<String, Object>> retryPending() {
        return alertService.retryPending().stream()
                .map(r -> alertService.notification(r.getMessage().notificationId()))
                .toList();
    }

    /** Test hook: makes the next N in-process notification deliveries fail. */
    @PostMapping("/test/fail-next")
    public Map<String, Object> failNext(@RequestBody FailNextRequest request) {
        channel.failNext(request.count());
        return Map.of("injectedFailures", request.count());
    }
}
