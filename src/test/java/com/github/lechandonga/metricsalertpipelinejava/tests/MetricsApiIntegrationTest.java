package com.github.lechandonga.metricsalertpipelinejava.tests;

import com.github.lechandonga.metricsalertpipelinejava.support.BusinessLoggingExtension;
import com.github.lechandonga.metricsalertpipelinejava.support.BusinessModule;
import com.github.lechandonga.metricsalertpipelinejava.time.TimeSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@BusinessModule("REST 端到端：注册/上报拒绝码/窗口查询/规则生命周期/故障注入重试")
@ExtendWith(BusinessLoggingExtension.class)
@SpringBootTest
@AutoConfigureMockMvc
class MetricsApiIntegrationTest {

    static final long T0 = 2_000_000_000_000L;

    /** Settable clock overriding the production system clock for deterministic windows. */
    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        TimeSource controllableClock() {
            return new SettableClock(T0);
        }
    }

    static final class SettableClock implements TimeSource {
        private final AtomicLong now;

        SettableClock(long start) {
            this.now = new AtomicLong(start);
        }

        @Override
        public long nowMillis() {
            return now.get();
        }

        void set(long millis) {
            now.set(millis);
        }
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TimeSource timeSource;

    private SettableClock clock() {
        return (SettableClock) timeSource;
    }

    @BeforeEach
    void resetClock() {
        clock().set(T0 + 10_000);
    }

    @Test
    void registerIngestRejectAndQueryOverHttp() throws Exception {
        mockMvc.perform(post("/metrics/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metric\":\"http_hits\",\"type\":\"COUNTER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("COUNTER"));

        mockMvc.perform(post("/metrics/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metric\":\"http_hits\",\"type\":\"GAUGE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("TYPE_MISMATCH"));

        mockMvc.perform(post("/metrics/samples/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("["
                                + "{\"metric\":\"http_hits\",\"type\":\"COUNTER\","
                                + "\"timestampMillis\":" + (T0 + 1000) + ",\"value\":3,"
                                + "\"tags\":{\"path\":\"/a\"}},"
                                + "{\"metric\":\"http_hits\",\"type\":\"COUNTER\","
                                + "\"timestampMillis\":null,\"value\":1},"
                                + "{\"metric\":\"http_hits\",\"type\":\"COUNTER\","
                                + "\"timestampMillis\":" + (T0 + 1000)
                                + ",\"value\":1,\"tags\":{\"1bad\":\"v\"}}"
                                + "]"))
                .andExpect(status().isMultiStatus())
                .andExpect(jsonPath("$.acceptedCount").value(1))
                .andExpect(jsonPath("$.rejectedCount").value(2))
                .andExpect(jsonPath("$.rejected[0].reason").value("TIMESTAMP_MISSING"))
                .andExpect(jsonPath("$.rejected[1].reason").value("TAG_KEY_ILLEGAL"));

        mockMvc.perform(get("/metrics/windows").param("metric", "http_hits")
                        .param("tag.path", "/a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowCount").value(1))
                .andExpect(jsonPath("$.windows[0].sum").value(3.0));

        mockMvc.perform(get("/metrics/samples").param("metric", "http_hits"))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void ruleLifecycleAndNotificationRetryOverHttp() throws Exception {
        mockMvc.perform(post("/metrics/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"metric\":\"mem\",\"type\":\"GAUGE\"}")).andExpect(status().isOk());

        mockMvc.perform(post("/metrics/samples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metric\":\"mem\",\"type\":\"GAUGE\","
                                + "\"timestampMillis\":" + (T0 + 2000)
                                + ",\"value\":90,\"tags\":{\"host\":\"h1\"}}"))
                .andExpect(status().isOk());

        clock().set(T0 + 15_000 + 5_000);
        MvcResult ruleResult = mockMvc.perform(post("/alerts/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"mem-high\",\"metric\":\"mem\","
                                + "\"statistic\":\"AVG\",\"operator\":\"GT\","
                                + "\"threshold\":80,\"triggerWindows\":1,\"recoverWindows\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn();
        String ruleId = objectMapper.readTree(
                ruleResult.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/alerts/test/fail-next")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":1}"))
                .andExpect(status().isOk());

        clock().set(T0 + 15_000 + 5_000);
        mockMvc.perform(post("/alerts/evaluate")).andExpect(status().isOk());

        mockMvc.perform(get("/alerts/rules/" + ruleId + "/states"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("FIRING"));

        MvcResult failed = mockMvc.perform(get("/alerts/notifications").param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("TRIGGERED"))
                .andReturn();
        JsonNode failedNode = objectMapper.readTree(failed.getResponse().getContentAsString());
        String notificationId = failedNode.get(0).get("notificationId").asText();

        mockMvc.perform(post("/alerts/notifications/" + notificationId + "/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));

        mockMvc.perform(get("/alerts/notifications").param("status", "DELIVERED"))
                .andExpect(jsonPath("$[0].notificationId").value(notificationId));
    }
}
