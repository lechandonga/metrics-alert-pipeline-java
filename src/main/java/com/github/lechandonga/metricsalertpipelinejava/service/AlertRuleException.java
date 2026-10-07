package com.github.lechandonga.metricsalertpipelinejava.service;

import com.github.lechandonga.metricsalertpipelinejava.model.RejectReason;

/** Invalid alert rule definition. */
public class AlertRuleException extends RuntimeException {
    private final RejectReason reason;

    public AlertRuleException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason reason() {
        return reason;
    }
}
