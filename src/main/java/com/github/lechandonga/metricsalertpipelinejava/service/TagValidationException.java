package com.github.lechandonga.metricsalertpipelinejava.service;

import com.github.lechandonga.metricsalertpipelinejava.model.RejectReason;

/** Internal control signal emitted while normalizing tag maps. */
class TagValidationException extends RuntimeException {
    private final RejectReason reason;

    TagValidationException(RejectReason reason, String message) {
        super(message, null, false, false);
        this.reason = reason;
    }

    RejectReason reason() {
        return reason;
    }
}
