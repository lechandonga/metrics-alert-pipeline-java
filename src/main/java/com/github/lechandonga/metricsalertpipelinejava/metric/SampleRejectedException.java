package com.github.lechandonga.metricsalertpipelinejava.metric;

/**
 * 样本被拒绝时抛出，携带可区分的拒绝原因，绝不静默丢弃。
 */
public class SampleRejectedException extends RuntimeException {

    private final ValidationReason reason;

    public SampleRejectedException(ValidationReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ValidationReason reason() {
        return reason;
    }
}
