package com.github.lechandonga.metricsalertpipelinejava.window;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 窗口与迟到语义配置。
 */
@Component
@ConfigurationProperties(prefix = "metrics.pipeline")
public class MetricsPipelineProperties {

    /** 滚动窗口大小（毫秒） */
    private long windowSizeMs = 60_000;

    /** 允许的迟到范围（毫秒），超出即拒绝 */
    private long allowedLatenessMs = 30_000;

    public long getWindowSizeMs() {
        return windowSizeMs;
    }

    public void setWindowSizeMs(long windowSizeMs) {
        this.windowSizeMs = windowSizeMs;
    }

    public long getAllowedLatenessMs() {
        return allowedLatenessMs;
    }

    public void setAllowedLatenessMs(long allowedLatenessMs) {
        this.allowedLatenessMs = allowedLatenessMs;
    }
}
