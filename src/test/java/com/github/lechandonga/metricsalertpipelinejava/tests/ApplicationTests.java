package com.github.lechandonga.metricsalertpipelinejava.tests;

import com.github.lechandonga.metricsalertpipelinejava.support.BusinessLoggingExtension;
import com.github.lechandonga.metricsalertpipelinejava.support.BusinessModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;

@BusinessModule("应用装配：Spring 上下文与本地 Bean 全部就绪")
@ExtendWith(BusinessLoggingExtension.class)
@SpringBootTest(properties = {
        "metrics.pipeline.window-size-millis=10000",
        "metrics.pipeline.allowed-lateness-millis=5000"
})
class ApplicationTests {

    @Test
    void contextLoads() {
    }
}
