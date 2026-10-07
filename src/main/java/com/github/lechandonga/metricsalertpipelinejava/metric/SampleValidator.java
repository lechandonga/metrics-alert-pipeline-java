package com.github.lechandonga.metricsalertpipelinejava.metric;

import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class SampleValidator {

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_.-]*");
    private static final Pattern TAG_KEY_PATTERN = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
    private static final int MAX_TAG_VALUE_LENGTH = 128;

    public void validate(Sample sample) {
        if (sample.name() == null || sample.name().isBlank()
                || !NAME_PATTERN.matcher(sample.name()).matches()) {
            throw new SampleRejectedException(ValidationReason.MISSING_NAME,
                    "metric name is missing or illegal: " + sample.name());
        }
        if (sample.type() == null) {
            throw new SampleRejectedException(ValidationReason.MISSING_TYPE, "metric type is required");
        }
        if (Double.isNaN(sample.value()) || Double.isInfinite(sample.value())) {
            throw new SampleRejectedException(ValidationReason.INVALID_VALUE,
                    "value must be a finite number");
        }
        if (sample.type() == MetricType.COUNTER && sample.value() < 0) {
            throw new SampleRejectedException(ValidationReason.INVALID_VALUE,
                    "counter value must be >= 0");
        }
        if (sample.timestamp() <= 0) {
            throw new SampleRejectedException(ValidationReason.INVALID_TIMESTAMP,
                    "timestamp must be a positive epoch millis");
        }
        for (Map.Entry<String, String> tag : sample.tags().entrySet()) {
            if (tag.getKey() == null || !TAG_KEY_PATTERN.matcher(tag.getKey()).matches()) {
                throw new SampleRejectedException(ValidationReason.ILLEGAL_TAG,
                        "illegal tag key: " + tag.getKey());
            }
            if (tag.getValue() == null || tag.getValue().length() > MAX_TAG_VALUE_LENGTH) {
                throw new SampleRejectedException(ValidationReason.ILLEGAL_TAG,
                        "illegal tag value for key: " + tag.getKey());
            }
        }
    }
}
