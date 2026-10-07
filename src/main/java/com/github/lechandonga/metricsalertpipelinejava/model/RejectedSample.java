package com.github.lechandonga.metricsalertpipelinejava.model;

import java.util.Map;

/**
 * One rejected batch entry together with its explicit, distinguishable failure reason.
 */
public record RejectedSample(int index,
                             String metric,
                             String type,
                             Long timestampMillis,
                             Double value,
                             Map<String, String> tags,
                             RejectReason reason,
                             String message) {
}
