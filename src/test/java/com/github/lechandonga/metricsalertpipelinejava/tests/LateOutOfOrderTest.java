package com.github.lechandonga.metricsalertpipelinejava.tests;

import com.github.lechandonga.metricsalertpipelinejava.model.AlertRule;
import com.github.lechandonga.metricsalertpipelinejava.model.IngestionResult;
import com.github.lechandonga.metricsalertpipelinejava.model.MetricType;
import com.github.lechandonga.metricsalertpipelinejava.model.RejectReason;
import com.github.lechandonga.metricsalertpipelinejava.model.WindowAggregate;
import com.github.lechandonga.metricsalertpipelinejava.service.AlertService;
import com.github.lechandonga.metricsalertpipelinejava.service.MetricService;
import com.github.lechandonga.metricsalertpipelinejava.support.BusinessModule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@BusinessModule("滚动窗口与迟到语义：迟到归位、超时拒绝、乱序结果一致")
class LateOutOfOrderTest extends AbstractServiceTest {

    @Test
    void lateSamplesWithinLatenessBelongToTheirOwnWindow() {
        register("orders", MetricType.COUNTER);
        clock.set(T0 + WINDOW);

        ingest(sample("orders", MetricType.COUNTER, T0 + 1_000, 5.0));
        ingest(sample("orders", MetricType.COUNTER, T0 + 3_000, 7.0));

        clock.set(T0 + WINDOW + LATENESS - 1);
        ingest(sample("orders", MetricType.COUNTER, T0 + 2_000, 3.0));

        List<WindowAggregate> windows = metricService.aggregateWindows(
                "orders", Map.of(), 0L, clock.nowMillis());
        assertEquals(1, windows.size(), "迟到样本仍属于第 0 个窗口");
        assertEquals(15.0, windows.get(0).sum(), 0.0001, "迟到样本被补入所属窗口的聚合");
        assertEquals(T0, windows.get(0).windowStartMillis());
    }

    @Test
    void samplesBeyondLatenessAreRejectedAndNeverMixedIntoCurrentWindow() {
        register("orders", MetricType.COUNTER);
        clock.set(T0 + 2 * WINDOW);
        ingest(sample("orders", MetricType.COUNTER, T0 + 2 * WINDOW - 1, 1.0));

        clock.set(T0 + 2 * WINDOW + LATENESS);

        long tooLateTs = T0;
        IngestionResult result = metricService.ingestBatch(List.of(
                sample("orders", MetricType.COUNTER, tooLateTs, 99.0),
                sample("orders", MetricType.COUNTER, T0 + 2 * WINDOW - 1, 2.0)));

        assertEquals(1, result.rejected().size());
        assertEquals(RejectReason.SAMPLE_TOO_LATE, result.rejected().get(0).reason());
        assertEquals(1, result.accepted().size());

        List<WindowAggregate> windows = metricService.aggregateWindows(
                "orders", Map.of(), 0L, clock.nowMillis());
        for (WindowAggregate w : windows) {
            assertEquals(0.0, w.sum() % 1.0, 0.0001, "迟到样本绝不能混入任何窗口");
        }
        WindowAggregate oldWindow = windows.stream()
                .filter(w -> w.windowStartMillis() == T0).findAny().orElse(null);
        assertTrue(oldWindow == null || oldWindow.count() == 0,
                "已关闭窗口不得被超限迟到样本污染");
    }

    @Test
    void aggregationIsOrderIndependent() {
        long[] timestamps = {T0 + 1, T0 + WINDOW - 1, T0 + 4_000, T0 + 2L, T0 + 6_000, T0 + 9_999};
        double[] values = {12, 80, 30, 44, 5, 60};
        List<Env> envs = List.of(new Env(), new Env(), new Env());
        List<MetricService.SampleDraft> batches = new ArrayList<>();
        for (int i = 0; i < timestamps.length; i++) {
            batches.add(envs.get(0).sample("latency", MetricType.DISTRIBUTION,
                    timestamps[i], values[i]));
        }
        List<MetricService.SampleDraft> reversed = new ArrayList<>(batches);
        Collections.reverse(reversed);
        List<MetricService.SampleDraft> shuffled = new ArrayList<>(batches);
        Collections.shuffle(shuffled, new java.util.Random(42));

        envs.get(0).register("latency", MetricType.DISTRIBUTION);
        envs.get(1).register("latency", MetricType.DISTRIBUTION);
        envs.get(2).register("latency", MetricType.DISTRIBUTION);
        envs.forEach(e -> e.clock.set(T0 + WINDOW));
        envs.get(0).ingestAll(batches);
        envs.get(1).ingestAll(reversed);
        envs.get(2).ingestAll(shuffled);

        var ascending = envs.get(0).metricService
                .aggregateWindows("latency", Map.of(), 0L, T0 + WINDOW);
        var descending = envs.get(1).metricService
                .aggregateWindows("latency", Map.of(), 0L, T0 + WINDOW);
        var shuffledResult = envs.get(2).metricService
                .aggregateWindows("latency", Map.of(), 0L, T0 + WINDOW);

        assertWindowEquals(ascending, descending);
        assertWindowEquals(ascending, shuffledResult);
        assertEquals(231.0, ascending.get(0).sum(), 0.0001);
        assertEquals(6, ascending.get(0).count());
        assertEquals(5.0, ascending.get(0).min(), 0.0001);
        assertEquals(80.0, ascending.get(0).max(), 0.0001);
    }

    @Test
    void alertDecisionsAreOrderIndependent() {
        var runA = runLifecycleWithOrder(new Env(), List.of(0, 1, 2, 3));
        var runB = runLifecycleWithOrder(new Env(), List.of(3, 1, 0, 2));
        var runC = runLifecycleWithOrder(new Env(), List.of(2, 0, 3, 1));

        assertEquals(runA.states, runB.states);
        assertEquals(runA.states, runC.states);
        assertEquals(runA.notifications, runB.notifications);
        assertEquals(runA.notifications, runC.notifications);
        assertEquals(1, runA.notifications, "乱序不应造成重复通知");
    }

    private LifecycleRun runLifecycleWithOrder(Env env, List<Integer> order) {
        env.register("cpu", MetricType.GAUGE);
        AlertRule rule = env.alertService.createRule(new AlertService.RuleDraft(
                "cpu-high", "cpu", Map.of(), "AVG", "GT", 50.0, 1, 1));

        int[] values = {60, 70, 80, 90};
        long[] timestamps = {
                T0 + 100, T0 + 200,
                T0 + WINDOW + 100, T0 + WINDOW + 200};

        List<MetricService.SampleDraft> drafts = new ArrayList<>();
        for (int idx : order) {
            drafts.add(env.sample("cpu", MetricType.GAUGE, timestamps[idx], values[idx]));
        }
        env.clock.set(T0 + WINDOW + 200);
        env.metricService.ingestBatch(drafts);

        env.clock.set(T0 + 2 * WINDOW + LATENESS);
        env.alertService.evaluateAll(env.clock.nowMillis());

        List<String> states = env.alertService.alertStates(rule.id()).stream()
                .map(s -> s.status().name()).toList();
        long notifications = env.alertService.listNotifications(null).size();
        return new LifecycleRun(states, notifications);
    }

    private void assertWindowEquals(List<WindowAggregate> a, List<WindowAggregate> b) {
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).windowStartMillis(), b.get(i).windowStartMillis());
            assertEquals(a.get(i).count(), b.get(i).count());
            assertEquals(a.get(i).sum(), b.get(i).sum(), 0.0001);
            assertEquals(a.get(i).avg(), b.get(i).avg(), 0.0001);
            assertEquals(a.get(i).min(), b.get(i).min(), 0.0001);
            assertEquals(a.get(i).max(), b.get(i).max(), 0.0001);
        }
    }

    private record LifecycleRun(List<String> states, long notifications) {
    }

    private static final class Env extends AbstractServiceTest {
        void ingestAll(List<MetricService.SampleDraft> drafts) {
            metricService.ingestBatch(drafts);
        }
    }
}
