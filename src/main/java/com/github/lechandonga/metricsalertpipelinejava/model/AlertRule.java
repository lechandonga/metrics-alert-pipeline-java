package com.github.lechandonga.metricsalertpipelinejava.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An alert rule evaluated over successive closed windows of one metric/series.
 *
 * @param triggerWindows consecutive breaching windows required to fire (>= 1)
 * @param recoverWindows consecutive healthy windows required to recover (>= 1)
 * @param tagFilter      empty means every series of the metric is evaluated independently
 */
public record AlertRule(String id,
                        String name,
                        String metric,
                        Map<String, String> tagFilter,
                        Statistic statistic,
                        ComparisonOperator operator,
                        double threshold,
                        int triggerWindows,
                        int recoverWindows) {

    public AlertRule {
        tagFilter = tagFilter == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(tagFilter));
    }
}
