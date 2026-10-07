package com.github.lechandonga.metricsalertpipelinejava.model;

/** Immutable payload handed to a {@code NotificationChannel}. */
public record NotificationMessage(String notificationId,
                                  String ruleId,
                                  String ruleName,
                                  String series,
                                  NotificationType type,
                                  long windowStartMillis,
                                  long windowEndMillis,
                                  double observedValue,
                                  double threshold,
                                  long occurredAtMillis) {
}
