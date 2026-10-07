package com.github.lechandonga.metricsalertpipelinejava.store;

import com.github.lechandonga.metricsalertpipelinejava.model.AlertRule;
import com.github.lechandonga.metricsalertpipelinejava.model.AlertStateSnapshot;
import com.github.lechandonga.metricsalertpipelinejava.model.AlertStatus;

/**
 * Mutable per-series alert state. All access happens while holding the rule-level lock
 * inside {@code AlertService}; the object itself is not shared across lock domains.
 */
public final class AlertState {

    private AlertStatus status = AlertStatus.INACTIVE;
    private int consecutiveBreaches;
    private int consecutiveHealthy;
    private long lastEvaluatedWindowStart = Long.MIN_VALUE;
    private long lastChangedWindowStart = Long.MIN_VALUE;
    private Long triggeredAtMillis;
    private Long recoveredAtMillis;

    /**
     * Feeds one evaluated window. Returns true exactly on an edge that must be notified
     * (INACTIVE -> FIRING = TRIGGERED, RECOVERING -> INACTIVE = RECOVERED).
     */
    public boolean acceptWindow(AlertRule rule, boolean breached, long windowStart) {
        boolean notify = false;
        switch (status) {
            case INACTIVE -> {
                if (breached) {
                    consecutiveBreaches = 1;
                    consecutiveHealthy = 0;
                    if (consecutiveBreaches >= rule.triggerWindows()) {
                        status = AlertStatus.FIRING;
                        triggeredAtMillis = windowStart;
                        lastChangedWindowStart = windowStart;
                        notify = true;
                    } else {
                        status = AlertStatus.PENDING;
                        lastChangedWindowStart = windowStart;
                    }
                }
            }
            case PENDING -> {
                if (breached) {
                    consecutiveBreaches++;
                    if (consecutiveBreaches >= rule.triggerWindows()) {
                        status = AlertStatus.FIRING;
                        triggeredAtMillis = windowStart;
                        lastChangedWindowStart = windowStart;
                        notify = true;
                    }
                } else {
                    consecutiveBreaches = 0;
                    consecutiveHealthy = 1;
                    status = AlertStatus.INACTIVE;
                    lastChangedWindowStart = windowStart;
                }
            }
            case FIRING -> {
                if (breached) {
                    consecutiveBreaches++;
                    consecutiveHealthy = 0;
                } else {
                    consecutiveHealthy = 1;
                    consecutiveBreaches = 0;
                    if (1 >= rule.recoverWindows()) {
                        status = AlertStatus.INACTIVE;
                        recoveredAtMillis = windowStart;
                        lastChangedWindowStart = windowStart;
                        notify = true;
                    } else {
                        status = AlertStatus.RECOVERING;
                        lastChangedWindowStart = windowStart;
                    }
                }
            }
            case RECOVERING -> {
                if (breached) {
                    consecutiveBreaches = 1;
                    consecutiveHealthy = 0;
                    status = AlertStatus.FIRING;
                    lastChangedWindowStart = windowStart;
                } else {
                    consecutiveHealthy++;
                    if (consecutiveHealthy >= rule.recoverWindows()) {
                        status = AlertStatus.INACTIVE;
                        recoveredAtMillis = windowStart;
                        lastChangedWindowStart = windowStart;
                        notify = true;
                    }
                }
            }
        }
        lastEvaluatedWindowStart = windowStart;
        return notify;
    }

    public AlertStateSnapshot snapshot(String ruleId, String series) {
        return new AlertStateSnapshot(ruleId, series, status, consecutiveBreaches,
                consecutiveHealthy, lastEvaluatedWindowStart, lastChangedWindowStart,
                triggeredAtMillis, recoveredAtMillis);
    }

    public AlertStatus getStatus() {
        return status;
    }
}
