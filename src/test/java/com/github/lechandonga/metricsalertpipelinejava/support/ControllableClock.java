package com.github.lechandonga.metricsalertpipelinejava.support;

import com.github.lechandonga.metricsalertpipelinejava.time.TimeSource;

import java.util.concurrent.atomic.AtomicLong;

/** Manually driven clock giving tests deterministic control over time and lateness. */
public class ControllableClock implements TimeSource {

    private final AtomicLong now;

    public ControllableClock(long startMillis) {
        this.now = new AtomicLong(startMillis);
    }

    @Override
    public long nowMillis() {
        return now.get();
    }

    public void set(long millis) {
        now.set(millis);
    }

    public void advance(long deltaMillis) {
        now.addAndGet(deltaMillis);
    }
}
