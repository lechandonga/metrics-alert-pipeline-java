package com.github.lechandonga.metricsalertpipelinejava.model;

/**
 * Alert lifecycle states.
 *
 * <pre>
 * INACTIVE -- N consecutive breaches --> FIRING   (TRIGGERED notification)
 * INACTIVE --> PENDING -- healthy --> INACTIVE
 * FIRING -- M consecutive healthy --> RECOVERING -- next healthy --> INACTIVE (RECOVERED notification)
 * FIRING --> RECOVERING -- breach --> FIRING
 * </pre>
 *
 * <p>PENDING/RECOVERING therefore absorb single-window jitter; notifications only ever
 * fire on the INACTIVE -> FIRING and RECOVERING -> INACTIVE edges.
 */
public enum AlertStatus {
    INACTIVE,
    PENDING,
    FIRING,
    RECOVERING
}
