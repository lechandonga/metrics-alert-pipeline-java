package com.github.lechandonga.metricsalertpipelinejava.model;

import java.util.Locale;

/** Statistics that an alert rule may evaluate. */
public enum Statistic {
    COUNT,
    SUM,
    AVG,
    MIN,
    MAX,
    LAST,
    P50,
    P90,
    P95,
    P99;

    public static Statistic tryParse(String raw) {
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
