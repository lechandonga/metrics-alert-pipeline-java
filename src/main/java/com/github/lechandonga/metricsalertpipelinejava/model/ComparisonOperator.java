package com.github.lechandonga.metricsalertpipelinejava.model;

import java.util.Locale;

/** Comparison applied as {@code statistic(operator)threshold}. */
public enum ComparisonOperator {
    GT,
    GTE,
    LT,
    LTE,
    EQ;

    public boolean matches(double actual, double threshold) {
        return switch (this) {
            case GT -> actual > threshold;
            case GTE -> actual >= threshold;
            case LT -> actual < threshold;
            case LTE -> actual <= threshold;
            case EQ -> actual == threshold;
        };
    }

    public static ComparisonOperator tryParse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
