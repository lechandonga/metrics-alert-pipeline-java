package com.github.lechandonga.metricsalertpipelinejava.tests;

import java.util.Map;

import com.github.lechandonga.metricsalertpipelinejava.metric.MetricStore;
import com.github.lechandonga.metricsalertpipelinejava.metric.MetricType;
import com.github.lechandonga.metricsalertpipelinejava.metric.Sample;
import com.github.lechandonga.metricsalertpipelinejava.metric.SampleRejectedException;
import com.github.lechandonga.metricsalertpipelinejava.metric.SampleValidator;
import com.github.lechandonga.metricsalertpipelinejava.metric.ValidationReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 业务覆盖：样本校验 —— 字段缺失 / 类型不合法 / 标签非法必须被可区分地拒绝。
 */
class SampleValidationTest {

    private static final Logger log = LoggerFactory.getLogger(SampleValidationTest.class);

    private final MetricStore store = new MetricStore(new SampleValidator());

    private ValidationReason rejectReason(Sample sample) {
        SampleRejectedException e = assertThrows(SampleRejectedException.class,
                () -> store.ingest(sample));
        log.info("[样本校验] 拒绝样本 name={} 原因={} 详情={}", sample.name(), e.reason(), e.getMessage());
        return e.reason();
    }

    @Test
    @DisplayName("样本校验：缺名字段被拒绝")
    void missingName() {
        assertEquals(ValidationReason.MISSING_NAME,
                rejectReason(new Sample(null, MetricType.COUNTER, 1, 1000L, Map.of())));
        assertEquals(ValidationReason.MISSING_NAME,
                rejectReason(new Sample("非法 name!!", MetricType.COUNTER, 1, 1000L, Map.of())));
    }

    @Test
    @DisplayName("样本校验：缺类型/非法数值/非法时间戳被拒绝")
    void invalidFields() {
        assertEquals(ValidationReason.MISSING_TYPE,
                rejectReason(new Sample("m1", null, 1, 1000L, Map.of())));
        assertEquals(ValidationReason.INVALID_VALUE,
                rejectReason(new Sample("m1", MetricType.GAUGE, Double.NaN, 1000L, Map.of())));
        assertEquals(ValidationReason.INVALID_VALUE,
                rejectReason(new Sample("m1", MetricType.COUNTER, -5, 1000L, Map.of())));
        assertEquals(ValidationReason.INVALID_TIMESTAMP,
                rejectReason(new Sample("m1", MetricType.GAUGE, 1, 0L, Map.of())));
    }

    @Test
    @DisplayName("样本校验：非法标签被拒绝")
    void illegalTags() {
        assertEquals(ValidationReason.ILLEGAL_TAG,
                rejectReason(new Sample("m1", MetricType.GAUGE, 1, 1000L, Map.of("bad key", "v"))));
        assertEquals(ValidationReason.ILLEGAL_TAG,
                rejectReason(new Sample("m1", MetricType.GAUGE, 1, 1000L,
                        Map.of("ok", "x".repeat(200)))));
    }

    @Test
    @DisplayName("样本校验：同名指标类型冲突被拒绝")
    void typeConflict() {
        store.ingest(new Sample("m2", MetricType.COUNTER, 1, 1000L, Map.of()));
        assertEquals(ValidationReason.TYPE_CONFLICT,
                rejectReason(new Sample("m2", MetricType.GAUGE, 1, 1000L, Map.of())));
    }
}
