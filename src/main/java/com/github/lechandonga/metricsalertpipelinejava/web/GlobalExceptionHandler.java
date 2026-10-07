package com.github.lechandonga.metricsalertpipelinejava.web;

import com.github.lechandonga.metricsalertpipelinejava.service.AlertRuleException;
import com.github.lechandonga.metricsalertpipelinejava.service.MetricValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/** Turns domain validation failures into 400 responses carrying the reject reason. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MetricValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> metricFailure(MetricValidationException e) {
        return Map.of(
                "timestamp", Instant.now().toString(),
                "reason", e.reason().name(),
                "message", e.getMessage());
    }

    @ExceptionHandler(AlertRuleException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> ruleFailure(AlertRuleException e) {
        return Map.of(
                "timestamp", Instant.now().toString(),
                "reason", e.reason().name(),
                "message", e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> unreadable(HttpMessageNotReadableException e) {
        return Map.of(
                "timestamp", Instant.now().toString(),
                "reason", "BODY_UNREADABLE",
                "message", "request body is missing or not valid JSON");
    }
}
