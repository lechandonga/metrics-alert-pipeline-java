package com.github.lechandonga.metricsalertpipelinejava.alert;

public enum ComparisonOp {
    GT,
    GTE,
    LT,
    LTE;

    public boolean test(double value, double threshold) {
        return switch (this) {
            case GT -> value > threshold;
            case GTE -> value >= threshold;
            case LT -> value < threshold;
            case LTE -> value <= threshold;
        };
    }
}
