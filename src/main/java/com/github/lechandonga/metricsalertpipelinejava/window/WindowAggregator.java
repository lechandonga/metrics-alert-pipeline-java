package com.github.lechandonga.metricsalertpipelinejava.window;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.github.lechandonga.metricsalertpipelinejava.metric.MetricKey;
import com.github.lechandonga.metricsalertpipelinejava.metric.Sample;
import com.github.lechandonga.metricsalertpipelinejava.metric.SampleRejectedException;
import com.github.lechandonga.metricsalertpipelinejava.metric.ValidationReason;
import org.springframework.stereotype.Component;

/**
 * 事件时间滚动窗口聚合器。
 *
 * <p>每个序列维护水位线 watermark = 已接受样本的最大事件时间。
 * 迟到样本若落在 watermark - allowedLateness 之内，归入其所属历史窗口；
 * 超出该范围的样本以 SAMPLE_TOO_LATE 明确拒绝，不会混入当前窗口。
 * 窗口在 windowEnd + allowedLateness <= watermark 时关闭并产出聚合结果。
 */
@Component
public class WindowAggregator {

    private final long windowSizeMs;
    private final long allowedLatenessMs;
    private final ConcurrentHashMap<MetricKey, SeriesWindows> seriesWindows = new ConcurrentHashMap<>();

    public WindowAggregator(MetricsPipelineProperties properties) {
        this.windowSizeMs = properties.getWindowSizeMs();
        this.allowedLatenessMs = properties.getAllowedLatenessMs();
    }

    public long windowSizeMs() {
        return windowSizeMs;
    }

    public long allowedLatenessMs() {
        return allowedLatenessMs;
    }

    public long windowStartOf(long timestamp) {
        return timestamp - Math.floorMod(timestamp, windowSizeMs);
    }

    public void add(Sample sample) {
        SeriesWindows sw = seriesWindows.computeIfAbsent(sample.key(), k -> new SeriesWindows());
        sw.add(sample);
    }

    /**
     * 关闭所有已到期的窗口并返回聚合结果（每个窗口只产出一次）。
     */
    public List<WindowAggregate> drainClosedWindows() {
        List<WindowAggregate> closed = new ArrayList<>();
        for (SeriesWindows sw : seriesWindows.values()) {
            sw.drainClosed(closed);
        }
        closed.sort((a, b) -> {
            int c = Long.compare(a.windowStart(), b.windowStart());
            return c != 0 ? c : a.key().toString().compareTo(b.key().toString());
        });
        return closed;
    }

    private class SeriesWindows {

        private final ConcurrentHashMap<Long, WindowBucket> buckets = new ConcurrentHashMap<>();
        private long watermark = Long.MIN_VALUE;

        void add(Sample sample) {
            long ts = sample.timestamp();
            long wm;
            synchronized (this) {
                wm = watermark;
                if (ts < wm - allowedLatenessMs) {
                    throw new SampleRejectedException(ValidationReason.SAMPLE_TOO_LATE,
                            "sample ts=" + ts + " is beyond allowed lateness, watermark=" + wm);
                }
                if (ts > watermark) {
                    watermark = ts;
                }
            }
            long start = windowStartOf(ts);
            WindowBucket bucket = buckets.computeIfAbsent(start,
                    s -> new WindowBucket(sample.key(), sample.type(), s, s + windowSizeMs));
            bucket.add(sample);
        }

        void drainClosed(List<WindowAggregate> out) {
            long wm;
            synchronized (this) {
                wm = watermark;
            }
            for (Map.Entry<Long, WindowBucket> entry : buckets.entrySet()) {
                long windowEnd = entry.getKey() + windowSizeMs;
                if (windowEnd + allowedLatenessMs <= wm && buckets.remove(entry.getKey(), entry.getValue())) {
                    out.add(entry.getValue().toAggregate());
                }
            }
        }
    }
}
