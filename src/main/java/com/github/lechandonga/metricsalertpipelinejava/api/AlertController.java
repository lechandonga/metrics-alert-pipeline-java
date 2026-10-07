package com.github.lechandonga.metricsalertpipelinejava.api;

import java.util.List;
import java.util.Map;

import com.github.lechandonga.metricsalertpipelinejava.alert.AlertEvaluator;
import com.github.lechandonga.metricsalertpipelinejava.alert.AlertRule;
import com.github.lechandonga.metricsalertpipelinejava.alert.AlertState;
import com.github.lechandonga.metricsalertpipelinejava.notify.DeliveryRecord;
import com.github.lechandonga.metricsalertpipelinejava.notify.Notification;
import com.github.lechandonga.metricsalertpipelinejava.notify.NotificationDispatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertEvaluator evaluator;
    private final NotificationDispatcher dispatcher;

    public AlertController(AlertEvaluator evaluator, NotificationDispatcher dispatcher) {
        this.evaluator = evaluator;
        this.dispatcher = dispatcher;
    }

    @PostMapping("/rules")
    public AlertRule register(@RequestBody AlertRule rule) {
        return evaluator.register(rule);
    }

    @GetMapping("/rules")
    public List<AlertRule> rules() {
        return evaluator.rules();
    }

    @GetMapping("/states")
    public Map<String, AlertState> states() {
        return evaluator.states();
    }

    @GetMapping("/notifications")
    public List<DeliveryRecord> deliveries() {
        return dispatcher.records();
    }

    @GetMapping("/notifications/pending")
    public List<Notification> pending() {
        return dispatcher.pendingRetries();
    }

    @PostMapping("/notifications/retry")
    public Map<String, Integer> retry() {
        return Map.of("stillFailing", dispatcher.retryFailed());
    }
}
