package com.github.lechandonga.metricsalertpipelinejava.tests;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.github.lechandonga.metricsalertpipelinejava.metric.MetricType;
import com.github.lechandonga.metricsalertpipelinejava.metric.Sample;
import com.github.lechandonga.metricsalertpipelinejava.metric.SampleRejectedException;
import com.github.lechandonga.metricsalertpipelinejava.metric.ValidationReason;
import com.github.lechandonga.metricsalertpipelinejava.window.MetricsPipelineProperties;
import com.github.lechandonga.metricsalertpipelinejava.window.WindowAggregate;
import com.github.lechandonga.metricsalertpipelinejava.window.WindowAggregator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务覆盖：滚动窗口聚合 —— 乱序到达结果一致、迟到范围内归入所属窗口、
 * 超出允许迟到范围的样本被明确拒绝。
 */
class WindowAggregationTest {

    private static final Logger log = LoggerFactory.getLogger(WindowAggregationTest.class);
    private static final long WINDOW = 1000L;
    private static final long LATENESS = 500L;

    private WindowAggregator aggregator;

    @BeforeEach
    void setUp() {
        MetricsPipelineProperties props = new MetricsPipelineProperties();
        props.setWindowSizeMs(WINDOW);
        props.setAllowedLatenessMs(LATENESS);
        aggregator = new WindowAggregator(props);
    }

    private Sample counter(long ts, double value) {
        return new Sample("hits", MetricType.COUNTER, value, ts, Map.of());
    }

    @Test
    @DisplayName("窗口聚合：同一批样本乱序到达，聚合结果一致")
    void outOfOrderDeterministic() {
        // 本用例样本时间跨度较大，放宽允许迟到范围以聚焦“乱序一致性”
        MetricsPipelineProperties props = new MetricsPipelineProperties();
        props.setWindowSizeMs(WINDOW);
        props.setAllowedLatenessMs(10_000L);
        aggregator = new WindowAggregator(props);

        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            samples.add(counter(100 + i * 10, i + 1));   // 窗口 [0,1000)
            samples.add(counter(1100 + i * 10, i + 1));  // 窗口 [1000,2000)
        }
        Sample advance = counter(2000 + 10_000L, 1); // 推进水位线关闭两个窗口

        WindowAggregator first = aggregator;
        samples.forEach(first::add);
        first.add(advance);
        List<WindowAggregate> ordered = first.drainClosedWindows();

        setUp();
        Collections.shuffle(samples, new java.util.Random(42));
        samples.forEach(aggregator::add);
        aggregator.add(advance);
        List<WindowAggregate> shuffled = aggregator.drainClosedWindows();

        log.info("[窗口聚合] 顺序到达={} 乱序到达={}", ordered, shuffled);
        assertEquals(ordered, shuffled, "同一批样本无论何种顺序到达，聚合值必须一致");
        assertEquals(1275.0, ordered.get(0).sum()); // 1..50 求和
    }

    @Test
    @DisplayName("窗口聚合：允许范围内的迟到样本归入其所属窗口")
    void lateSampleWithinAllowance() {
        aggregator.add(counter(1900, 10)); // 水位线 1900
        // 迟到 800ms 的样本，超出 lateness=500? 1900-500=1400，ts=900 < 1400 -> 拒绝
        // 先验证范围内：ts=1500 属于窗口 [1000,2000)
        aggregator.add(counter(1500, 5));
        aggregator.add(counter(2500, 1)); // 推进水位线关闭 [1000,2000)
        List<WindowAggregate> closed = aggregator.drainClosedWindows();
        assertEquals(1, closed.size());
        assertEquals(15.0, closed.get(0).sum());
        log.info("[窗口聚合] 迟到样本归入所属窗口，窗口聚合={}", closed.get(0));
    }

    @Test
    @DisplayName("窗口聚合：超出允许迟到范围的样本被明确拒绝，不混入当前窗口")
    void tooLateSampleRejected() {
        aggregator.add(counter(5000, 1)); // 水位线 5000
        SampleRejectedException e = assertThrows(SampleRejectedException.class,
                () -> aggregator.add(counter(1000, 999)));
        assertEquals(ValidationReason.SAMPLE_TOO_LATE, e.reason());
        log.info("[窗口聚合] 超迟到样本被拒绝：{}", e.getMessage());

        aggregator.add(counter(5000 + WINDOW + LATENESS, 1));
        List<WindowAggregate> closed = aggregator.drainClosedWindows();
        double sum = closed.stream().mapToDouble(WindowAggregate::sum).sum();
        assertEquals(1.0, sum, "被拒绝的迟到样本不得计入任何窗口");
    }

    @Test
    @DisplayName("窗口聚合：分布型指标统计 count/min/max/avg")
    void distributionAggregate() {
        aggregator.add(new Sample("latency", MetricType.DISTRIBUTION, 10, 100, Map.of()));
        aggregator.add(new Sample("latency", MetricType.DISTRIBUTION, 30, 200, Map.of()));
        aggregator.add(new Sample("latency", MetricType.DISTRIBUTION, 50, 300, Map.of()));
        aggregator.add(new Sample("latency", MetricType.DISTRIBUTION, 1, 2000, Map.of()));
        List<WindowAggregate> closed = aggregator.drainClosedWindows();
        assertEquals(1, closed.size());
        WindowAggregate agg = closed.get(0);
        assertEquals(3, agg.count());
        assertEquals(10, agg.min());
        assertEquals(50, agg.max());
        assertEquals(30.0, agg.avg());
        log.info("[窗口聚合] 分布型聚合={}", agg);
    }

    @Test
    @DisplayName("窗口聚合：瞬时值取窗口内最新值，乱序也一致")
    void gaugeTakesLatest() {
        aggregator.add(new Sample("temp", MetricType.GAUGE, 20, 900, Map.of()));
        aggregator.add(new Sample("temp", MetricType.GAUGE, 25, 100, Map.of())); // 乱序早到
        aggregator.add(new Sample("temp", MetricType.GAUGE, 0, 2000, Map.of()));
        List<WindowAggregate> closed = aggregator.drainClosedWindows();
        assertEquals(20.0, closed.get(0).value());
        assertTrue(closed.get(0).count() == 2);
        log.info("[窗口聚合] 瞬时值聚合={}", closed.get(0));
    }
}
