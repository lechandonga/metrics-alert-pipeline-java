package com.github.lechandonga.metricsalertpipelinejava.service;

import com.github.lechandonga.metricsalertpipelinejava.model.RejectReason;

/** A single-sample request that cannot be ingested (REST POST /metrics/samples). */
public class MetricValidationException extends RuntimeException {
    private final RejectReason reason;

    public MetricValidationException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason reason() {
        return reason;
    }
}
