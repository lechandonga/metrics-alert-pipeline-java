package com.github.lechandonga.metricsalertpipelinejava.model;

import java.util.List;

/**
 * Outcome of a batch ingestion. Accepted samples were appended atomically as a group;
 * rejected samples never entered the store and each carries an explicit reason.
 */
public record IngestionResult(List<MetricSample> accepted, List<RejectedSample> rejected) {
}
