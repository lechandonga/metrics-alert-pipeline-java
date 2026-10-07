package com.github.lechandonga.metricsalertpipelinejava.pipeline;

import java.util.List;

import com.github.lechandonga.metricsalertpipelinejava.alert.AlertEvaluator;
import com.github.lechandonga.metricsalertpipelinejava.metric.MetricStore;
import com.github.lechandonga.metricsalertpipelinejava.metric.Sample;
import com.github.lechandonga.metricsalertpipelinejava.window.WindowAggregate;
import com.github.lechandonga.metricsalertpipelinejava.window.WindowAggregator;
import org.springframework.stereotype.Service;

/**
 * 指标管道门面：上报 -> 校验入库 -> 窗口聚合 -> 关闭窗口 -> 告警判定。
 */
@Service
public class MetricsPipelineService {

    private final MetricStore store;
    private final WindowAggregator aggregator;
    private final AlertEvaluator alertEvaluator;

    public MetricsPipelineService(MetricStore store, WindowAggregator aggregator,
                                  AlertEvaluator alertEvaluator) {
        this.store = store;
        this.aggregator = aggregator;
        this.alertEvaluator = alertEvaluator;
    }

    public void report(Sample sample) {
        store.ingest(sample);
        aggregator.add(sample);
    }

    public List<Sample> query(String name, long from, long to) {
        return store.query(name, from, to);
    }

    /**
     * 关闭到期窗口并对每个窗口执行规则判定，返回本次关闭的窗口聚合。
     */
    public List<WindowAggregate> evaluateWindows() {
        List<WindowAggregate> closed = aggregator.drainClosedWindows();
        for (WindowAggregate aggregate : closed) {
            alertEvaluator.onWindowClosed(aggregate);
        }
        return closed;
    }
}
