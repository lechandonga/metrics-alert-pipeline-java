package com.github.lechandonga.metricsalertpipelinejava.model;

/** Immutable point-in-time view of one series' alert state. */
public record AlertStateSnapshot(String ruleId,
                                 String series,
                                 AlertStatus status,
                                 int consecutiveBreaches,
                                 int consecutiveHealthy,
                                 long lastEvaluatedWindowStart,
                                 long lastChangedWindowStart,
                                 Long triggeredAtMillis,
                                 Long recoveredAtMillis) {
}
