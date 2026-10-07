package com.github.lechandonga.metricsalertpipelinejava.model;

import java.util.Locale;

/**
 * Supported metric kinds.
 *
 * <ul>
 *   <li>{@link #COUNTER} - monotonically (in practice: additively) reported increments;
 *       windows aggregate by sum.</li>
 *   <li>{@link #GAUGE} - instantaneous values; windows aggregate avg/min/max/latest.</li>
 *   <li>{@link #DISTRIBUTION} - observation samples; windows expose count/sum/avg/min/max/pXX.</li>
 * </ul>
 */
public enum MetricType {
    COUNTER,
    GAUGE,
    DISTRIBUTION;

    /**
     * Case-insensitive parse; {@code null}/unknown values yield {@code null} so callers
     * can report a distinguishable {@code TYPE_MISSING}/{@code TYPE_UNKNOWN} rejection.
     */
    public static MetricType tryParse(String raw) {
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
