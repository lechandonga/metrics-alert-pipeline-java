package com.github.lechandonga.metricsalertpipelinejava.metric;

/**
 * 样本被拒绝的可区分原因。
 */
public enum ValidationReason {
    MISSING_NAME,
    MISSING_TYPE,
    INVALID_VALUE,
    INVALID_TIMESTAMP,
    ILLEGAL_TAG,
    TYPE_CONFLICT,
    SAMPLE_TOO_LATE
}
