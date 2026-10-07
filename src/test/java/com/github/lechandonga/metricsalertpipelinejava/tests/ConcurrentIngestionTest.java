package com.github.lechandonga.metricsalertpipelinejava.tests;

import com.github.lechandonga.metricsalertpipelinejava.model.IngestionResult;
import com.github.lechandonga.metricsalertpipelinejava.model.MetricType;
import com.github.lechandonga.metricsalertpipelinejava.model.RejectReason;
import com.github.lechandonga.metricsalertpipelinejava.service.MetricService;
import com.github.lechandonga.metricsalertpipelinejava.support.BusinessModule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@BusinessModule("指标并发上报与校验：无丢失/无重复/无半更新读，拒绝原因可区分")
class ConcurrentIngestionTest extends AbstractServiceTest {

    @Test
    void concurrentWritesNeverLoseOrDuplicateSamples() throws Exception {
        register("requests", MetricType.COUNTER);
        clock.set(T0 + WINDOW - 100);
        int threads = 16;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int worker = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        long ts = T0 + (long) i * threads + worker;
                        ingest(sample("requests", MetricType.COUNTER, ts, 1,
                                Map.of("worker", "w" + worker, "i", String.valueOf(i))));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        List<?> all = metricService.query("requests", Map.of(), 0L, clock.nowMillis());
        assertEquals(threads * perThread, all.size(),
                "并发上报不得丢失或重复计入样本");
        Set<Object> unique = new HashSet<>(all);
        assertEquals(all.size(), unique.size(), "每个样本只能计入一次");

        double totalSum = metricService.aggregateWindows("requests", Map.of(), 0L, clock.nowMillis())
                .stream().mapToDouble(w -> w.sum()).sum();
        assertEquals(threads * perThread, totalSum, 0.0001, "COUNTER 求和必须等于上报总增量");
    }

    @Test
    void concurrentReadsAlwaysSeeConsistentState() throws Exception {
        register("temp", MetricType.GAUGE);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch stop = new CountDownLatch(1);
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();

        for (int t = 0; t < 6; t++) {
            final int worker = t;
            pool.submit(() -> {
                try {
                    int i = 0;
                    while (stop.getCount() > 0) {
                        ingest(sample("temp", MetricType.GAUGE, T0 + i, 20 + (i % 10),
                                Map.of("worker", "w" + worker)));
                        i++;
                    }
                } catch (RuntimeException e) {
                    readerFailure.compareAndSet(null, e);
                }
            });
        }
        pool.submit(() -> {
            try {
                while (stop.getCount() > 0) {
                    var windows = metricService.aggregateWindows(
                            "temp", Map.of(), 0L, clock.nowMillis());
                    for (var w : windows) {
                        assertTrue(w.avg() >= w.min() - 0.0000001
                                        && w.avg() <= w.max() + 0.0000001,
                                "读取到半更新状态：avg 超出 min/max 范围");
                        assertTrue(w.count() > 0, "非空窗口计数必须为正");
                    }
                }
            } catch (RuntimeException | AssertionError e) {
                readerFailure.compareAndSet(null, e);
            }
        });

        Thread.sleep(500);
        stop.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(null, readerFailure.get(),
                readerFailure.get() == null ? "" : readerFailure.get().getMessage());
    }

    @Test
    void invalidSamplesAreRejectedWithDistinctReasons() {
        register("m", MetricType.COUNTER);
        List<MetricService.SampleDraft> drafts = new ArrayList<>();
        drafts.add(badDraft(null, "COUNTER", T0, 1.0, null));
        drafts.add(badDraft("1bad-name", "COUNTER", T0, 1.0, null));
        drafts.add(badDraft("m", null, T0, 1.0, null));
        drafts.add(badDraft("m", "WEATHER", T0, 1.0, null));
        drafts.add(badDraft("m", "COUNTER", T0, null, null));
        drafts.add(badDraft("m", "COUNTER", T0, Double.NaN, null));
        drafts.add(badDraft("m", "COUNTER", null, 1.0, null));
        drafts.add(badDraft("m", "COUNTER", -5L, 1.0, null));
        drafts.add(badDraft("m", "GAUGE", T0, 1.0, null));
        drafts.add(badDraft("m", "COUNTER", T0, 1.0, Map.of("1bad", "v")));
        drafts.add(badDraft("m", "COUNTER", T0, 1.0, Map.of("ok", "")));
        drafts.add(badDraft("m", "COUNTER", T0 + 100, 1.0, null));
        drafts.add(badDraft("m", "COUNTER", T0 - WINDOW - LATENESS - 1, 1.0, null));
        drafts.add(sample("m", MetricType.COUNTER, T0, 1.0));

        IngestionResult result = metricService.ingestBatch(drafts);

        assertEquals(1, result.accepted().size(), "仅一个合法样本被接受");
        assertEquals(13, result.rejected().size(), "非法样本不得静默丢弃");
        Set<RejectReason> reasons = new HashSet<>(result.rejected().stream()
                .map(r -> r.reason()).toList());
        assertEquals(Set.of(
                RejectReason.METRIC_NAME_MISSING,
                RejectReason.METRIC_NAME_ILLEGAL,
                RejectReason.TYPE_MISSING,
                RejectReason.TYPE_UNKNOWN,
                RejectReason.TYPE_MISMATCH,
                RejectReason.VALUE_MISSING,
                RejectReason.VALUE_NOT_FINITE,
                RejectReason.TIMESTAMP_MISSING,
                RejectReason.TIMESTAMP_ILLEGAL,
                RejectReason.TIMESTAMP_IN_FUTURE,
                RejectReason.SAMPLE_TOO_LATE,
                RejectReason.TAG_KEY_ILLEGAL,
                RejectReason.TAG_VALUE_ILLEGAL), reasons, "每种非法情况都有可区分原因");
        for (int i = 0; i < 13; i++) {
            assertEquals(i, result.rejected().get(i).index(), "拒绝记录必须保留批次下标");
            assertTrue(result.rejected().get(i).message() != null
                    && !result.rejected().get(i).message().isBlank(), "拒绝必须带说明信息");
        }
    }

    @Test
    void concurrentIngestionAndRejectionDoNotInterfere() throws Exception {
        register("hits", MetricType.COUNTER);
        clock.set(T0 + WINDOW - 100);
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(12);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        for (int t = 0; t < 8; t++) {
            final int worker = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 300; i++) {
                        ingest(sample("hits", MetricType.COUNTER,
                                T0 + (long) i * 8 + worker, 2.0));
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        for (int t = 0; t < 4; t++) {
            final int worker = t + 8;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 100; i++) {
                        IngestionResult r = metricService.ingestBatch(List.of(
                                badDraft("hits", "DISTRIBUTION",
                                        T0 + (long) i * 8 + worker, 1.0, null)));
                        if (r.rejected().size() != 1
                                || r.rejected().get(0).reason() != RejectReason.TYPE_MISMATCH) {
                            throw new AssertionError("并发拒绝结果不稳定");
                        }
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertTrue(errors.isEmpty(), "并发上报与拒绝互不干扰: " + errors);
        long count = metricService.query("hits", Map.of(), 0L, clock.nowMillis()).size();
        assertEquals(8 * 300L, count, "合法样本计数精确");
    }
}
