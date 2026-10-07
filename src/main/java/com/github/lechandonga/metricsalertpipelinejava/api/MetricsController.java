package com.github.lechandonga.metricsalertpipelinejava.api;

import java.util.List;

import com.github.lechandonga.metricsalertpipelinejava.metric.Sample;
import com.github.lechandonga.metricsalertpipelinejava.metric.SampleRejectedException;
import com.github.lechandonga.metricsalertpipelinejava.pipeline.MetricsPipelineService;
import com.github.lechandonga.metricsalertpipelinejava.window.WindowAggregate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final MetricsPipelineService pipeline;

    public MetricsController(MetricsPipelineService pipeline) {
        this.pipeline = pipeline;
    }

    @PostMapping("/report")
    public ResponseEntity<Void> report(@RequestBody ReportRequest request) {
        pipeline.report(request.toSample());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/report/batch")
    public ResponseEntity<Void> reportBatch(@RequestBody List<ReportRequest> requests) {
        for (ReportRequest request : requests) {
            pipeline.report(request.toSample());
        }
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/query")
    public List<Sample> query(@RequestParam String name,
                              @RequestParam long from,
                              @RequestParam long to) {
        return pipeline.query(name, from, to);
    }

    @PostMapping("/evaluate")
    public List<WindowAggregate> evaluate() {
        return pipeline.evaluateWindows();
    }

    @ExceptionHandler(SampleRejectedException.class)
    public ResponseEntity<ErrorResponse> rejected(SampleRejectedException e) {
        return ResponseEntity.unprocessableEntity()
                .body(new ErrorResponse(e.reason(), e.getMessage()));
    }
}
