package com.github.lechandonga.metricsalertpipelinejava.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.github.lechandonga.metricsalertpipelinejava.metric.MetricStore;
import com.github.lechandonga.metricsalertpipelinejava.metric.MetricType;
import com.github.lechandonga.metricsalertpipelinejava.metric.Sample;
import com.github.lechandonga.metricsalertpipelinejava.metric.SampleValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务覆盖：并发上报与查询 —— 不丢样本、不重复计入、读不到半更新状态。
 */
class ConcurrentReportTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentReportTest.class);

    @Test
    @DisplayName("并发上报：多线程写入计数器，总数精确且范围查询一致")
    void concurrentIngestIsLossless() throws Exception {
        MetricStore store = new MetricStore(new SampleValidator());
        int threads = 8;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                for (int i = 0; i < perThread; i++) {
                    long ts = 1_000_000L + (i % 100) * 10L;
                    store.ingest(new Sample("requests", MetricType.COUNTER, 1, ts,
                            Map.of("thread", "t" + threadId)));
                }
                return null;
            }));
        }
        // 并发查询线程：读到的总数必须单调且永不超过最终值
        Future<?> reader = pool.submit(() -> {
            long last = -1;
            while (go.getCount() > 0 || !futuresDone(futures)) {
                long count = store.query("requests", 0, Long.MAX_VALUE).size();
                assertTrue(count >= last && count <= (long) threads * perThread,
                        "read must never see torn state");
                last = count;
            }
            return null;
        });

        ready.await();
        go.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        reader.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        long total = store.sampleCount();
        log.info("[并发上报] {} 线程 x {} 样本，最终总数={}（期望 {}）",
                threads, perThread, total, (long) threads * perThread);
        assertEquals((long) threads * perThread, total);
        assertEquals(threads * perThread, store.query("requests", 0, Long.MAX_VALUE).size());
    }

    private static boolean futuresDone(List<Future<?>> futures) {
        return futures.stream().allMatch(Future::isDone);
    }
}
