package com.github.lechandonga.metricsalertpipelinejava.model;

/**
 * Distinguishable, machine-readable reasons for rejecting a sample or rule.
 * Nothing is ever rejected silently: every code carries a human-readable message.
 */
public enum RejectReason {
    METRIC_NAME_MISSING("metric name is missing"),
    METRIC_NAME_ILLEGAL("metric name must match [a-zA-Z_:][a-zA-Z0-9_:]*"),
    TYPE_MISSING("metric type is missing"),
    TYPE_UNKNOWN("metric type is unknown (allowed: COUNTER, GAUGE, DISTRIBUTION)"),
    TYPE_MISMATCH("sample type conflicts with the registered metric type"),
    VALUE_MISSING("sample value is missing"),
    VALUE_NOT_FINITE("sample value must be a finite number"),
    TIMESTAMP_MISSING("timestampMillis is missing"),
    TIMESTAMP_ILLEGAL("timestampMillis must be a positive epoch millisecond value"),
    TIMESTAMP_IN_FUTURE("sample timestamp is later than the ingestion clock"),
    SAMPLE_TOO_LATE("sample belongs to a window that is already closed beyond allowed lateness"),
    TAG_NOT_OBJECT("tags must be an object of string key/value pairs"),
    TAG_KEY_ILLEGAL("tag key must match [a-zA-Z_][a-zA-Z0-9_.-]* and be non-empty"),
    TAG_VALUE_ILLEGAL("tag value must be a non-empty string"),
    RULE_NOT_FOUND("alert rule does not exist"),
    RULE_FIELD_ILLEGAL("alert rule field is illegal"),
    NOTIFICATION_ALREADY_DELIVERED("notification has already been delivered"),
    NOTIFICATION_NOT_FOUND("notification does not exist");

    private final String defaultMessage;

    RejectReason(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
