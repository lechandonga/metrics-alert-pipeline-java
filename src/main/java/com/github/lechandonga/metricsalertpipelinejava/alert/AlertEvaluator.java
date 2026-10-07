package com.github.lechandonga.metricsalertpipelinejava.alert;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.github.lechandonga.metricsalertpipelinejava.metric.MetricKey;
import com.github.lechandonga.metricsalertpipelinejava.notify.Notification;
import com.github.lechandonga.metricsalertpipelinejava.notify.NotificationDispatcher;
import com.github.lechandonga.metricsalertpipelinejava.window.WindowAggregate;
import org.springframework.stereotype.Component;

/**
 * 告警评估器：消费已关闭窗口的聚合结果，驱动状态机并派发通知。
 * 状态跃迁与通知投递解耦：投递失败不会回滚状态，而是进入可重试队列。
 */
@Component
public class AlertEvaluator {

    private final List<AlertRule> rules = new CopyOnWriteArrayList<>();
    private final Map<String, AlertInstance> instances = new ConcurrentHashMap<>();
    private final NotificationDispatcher dispatcher;

    public AlertEvaluator(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public AlertRule register(AlertRule rule) {
        rules.add(rule);
        return rule;
    }

    public List<AlertRule> rules() {
        return List.copyOf(rules);
    }

    public Map<String, AlertState> states() {
        Map<String, AlertState> result = new ConcurrentHashMap<>();
        instances.forEach((k, v) -> result.put(k, v.state()));
        return result;
    }

    public AlertState stateOf(String ruleId, MetricKey key) {
        AlertInstance instance = instances.get(instanceKey(ruleId, key));
        return instance == null ? AlertState.INACTIVE : instance.state();
    }

    public void onWindowClosed(WindowAggregate aggregate) {
        for (AlertRule rule : rules) {
            if (!matches(rule, aggregate.key())) {
                continue;
            }
            AlertInstance instance = instances.computeIfAbsent(
                    instanceKey(rule.id(), aggregate.key()), k -> new AlertInstance(rule));
            boolean breached = rule.op().test(aggregate.value(), rule.threshold());
            AlertEvent event = instance.evaluate(breached);
            if (event != AlertEvent.NONE) {
                Notification notification = new Notification(
                        UUID.randomUUID().toString(), rule.id(), aggregate.key().name(),
                        event, aggregate.value(), aggregate.windowEnd());
                dispatcher.dispatch(notification);
            }
        }
    }

    private boolean matches(AlertRule rule, MetricKey key) {
        return rule.metricName().equals(key.name()) && key.tags().entrySet().containsAll(rule.tags().entrySet());
    }

    private String instanceKey(String ruleId, MetricKey key) {
        return ruleId + "|" + key;
    }
}
