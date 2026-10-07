package com.github.lechandonga.metricsalertpipelinejava.api;

import com.github.lechandonga.metricsalertpipelinejava.metric.ValidationReason;

public record ErrorResponse(ValidationReason reason, String message) {
}
