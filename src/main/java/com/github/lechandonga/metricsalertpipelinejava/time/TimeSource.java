package com.github.lechandonga.metricsalertpipelinejava.time;

/**
 * Abstraction over the wall clock. Production uses {@link SystemTimeSource}; tests use
 * a controllable implementation so late arrivals and window advancement are deterministic.
 */
public interface TimeSource {
    long nowMillis();

    static TimeSource system() {
        return new SystemTimeSource();
    }
}
