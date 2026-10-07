package com.github.lechandonga.metricsalertpipelinejava.support;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;

/**
 * Emits human-readable log lines for every covered business case:
 * <pre>
 * [业务模块] xxx
 *   [用例] methodName —— ...
 *   [结果] module summary: passed=n failed=n
 * </pre>
 */
public class BusinessLoggingExtension
        implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback, AfterAllCallback {

    private Logger logger(ExtensionContext context) {
        Class<?> testClass = context.getTestClass().orElseGet(() -> context.getRequiredTestClass());
        return org.slf4j.LoggerFactory.getLogger(testClass);
    }

    private String module(ExtensionContext context) {
        Class<?> testClass = context.getTestClass().orElseGet(() -> context.getRequiredTestClass());
        BusinessModule annotation = testClass
                .getAnnotation(BusinessModule.class);
        return annotation == null ? "未分类业务" : annotation.value();
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        getCounters(context);
        logger(context).info("========== [业务模块] {} ==========", module(context));
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        logger(context).info("  [用例] {} —— 开始", context.getDisplayName());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        Logger log = logger(context);
        boolean failed = context.getExecutionException().isPresent();
        int[] counters = getCounters(context);
        if (failed) {
            counters[1]++;
        } else {
            counters[0]++;
        }
        if (failed) {
            log.warn("  [用例] {} —— 失败: {}", context.getDisplayName(),
                    context.getExecutionException().get().toString());
        } else {
            log.info("  [用例] {} —— 通过", context.getDisplayName());
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        int[] counters = getCounters(context);
        logger(context).info("  [结果] 模块「{}」汇总: 通过={}, 失败={}",
                module(context), counters[0], counters[1]);
    }

    private ExtensionContext.Store store(ExtensionContext context) {
        return context.getStore(ExtensionContext.Namespace.GLOBAL);
    }

    private int[] getCounters(ExtensionContext context) {
        Class<?> testClass = context.getTestClass().orElseGet(() -> context.getRequiredTestClass());
        String key = "counters:" + testClass.getName();
        ExtensionContext.Store store = store(context);
        int[] counters = store.get(key, int[].class);
        if (counters == null) {
            counters = new int[]{0, 0};
            store.put(key, counters);
        }
        return counters;
    }
}
