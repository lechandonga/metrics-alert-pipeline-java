package com.github.lechandonga.metricsalertpipelinejava.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期性关闭到期窗口并执行告警判定。
 */
@Component
public class WindowEvaluationScheduler {

    private static final Logger log = LoggerFactory.getLogger(WindowEvaluationScheduler.class);

    private final MetricsPipelineService pipeline;

    public WindowEvaluationScheduler(MetricsPipelineService pipeline) {
        this.pipeline = pipeline;
    }

    @Scheduled(fixedDelayString = "${metrics.pipeline.evaluate-interval-ms:10000}")
    public void evaluate() {
        int closed = pipeline.evaluateWindows().size();
        if (closed > 0) {
            log.info("evaluated {} closed windows", closed);
        }
    }
}
