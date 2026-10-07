package com.github.lechandonga.metricsalertpipelinejava.web;

import com.github.lechandonga.metricsalertpipelinejava.model.IngestionResult;
import com.github.lechandonga.metricsalertpipelinejava.model.MetricSample;
import com.github.lechandonga.metricsalertpipelinejava.model.MetricType;
import com.github.lechandonga.metricsalertpipelinejava.model.WindowAggregate;
import com.github.lechandonga.metricsalertpipelinejava.service.MetricService;
import com.github.lechandonga.metricsalertpipelinejava.store.MetricStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/metrics")
public class MetricsController {

    private final MetricService metricService;
    private final MetricStore store;

    public MetricsController(MetricService metricService, MetricStore store) {
        this.metricService = metricService;
        this.store = store;
    }

    public record RegisterRequest(String metric, String type) {
    }

    public record IngestResponse(int acceptedCount,
                                 int rejectedCount,
                                 List<MetricSample> accepted,
                                 List<?> rejected) {
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegisterRequest request) {
        MetricType type = metricService.register(request.metric(), request.type());
        return Map.of("metric", request.metric().trim(), "type", type.name());
    }

    /** Batch ingestion: 200 when everything is accepted, 207 multi-status otherwise. */
    @PostMapping("/samples/batch")
    public ResponseEntity<IngestResponse> ingestBatch(
            @RequestBody List<MetricService.SampleDraft> drafts) {
        IngestionResult result = metricService.ingestBatch(drafts);
        IngestResponse body = new IngestResponse(result.accepted().size(),
                result.rejected().size(), result.accepted(), result.rejected());
        HttpStatus status = result.rejected().isEmpty()
                ? HttpStatus.OK : HttpStatus.MULTI_STATUS;
        return ResponseEntity.status(status).body(body);
    }

    /** Single sample ingestion; any validation failure surfaces as 400 with its reason. */
    @PostMapping("/samples")
    public MetricSample ingestOne(@RequestBody MetricService.SampleDraft draft) {
        return metricService.ingestOne(draft);
    }

    @GetMapping
    public Map<String, ?> registered() {
        Map<String, String> view = new LinkedHashMap<>();
        store.registeredMetrics().forEach((k, v) -> view.put(k, v.name()));
        return Map.of("metrics", view);
    }

    @GetMapping("/samples")
    public Map<String, Object> querySamples(@RequestParam String metric,
                                            @RequestParam(required = false) Long fromMillis,
                                            @RequestParam(required = false) Long toMillis,
                                            HttpServletRequest request) {
        Map<String, String> tags = tagParams(request);
        List<MetricSample> samples = metricService.query(metric, tags, fromMillis, toMillis);
        return Map.of("metric", metric, "count", samples.size(), "samples", samples);
    }

    @GetMapping("/windows")
    public Map<String, Object> queryWindows(@RequestParam String metric,
                                            @RequestParam(required = false) Long fromMillis,
                                            @RequestParam(required = false) Long toMillis,
                                            HttpServletRequest request) {
        Map<String, String> tags = tagParams(request);
        List<WindowAggregate> windows =
                metricService.aggregateWindows(metric, tags, fromMillis, toMillis);
        return Map.of("metric", metric, "windowCount", windows.size(), "windows", windows);
    }

    /** Tag filters are passed as {@code tag.REGION=us-east} style query parameters. */
    private Map<String, String> tagParams(HttpServletRequest request) {
        Map<String, String> tags = new LinkedHashMap<>();
        request.getParameterMap().forEach((key, values) -> {
            if (key.startsWith("tag.") && values != null && values.length > 0) {
                tags.put(key.substring(4), values[0]);
            }
        });
        return tags;
    }
}
