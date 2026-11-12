package br.com.fiap.numberone.billing.budget.application.results;

import java.time.OffsetDateTime;

public record BudgetNotificationSummary(Status status, int attempts, OffsetDateTime lastAttemptAt,
                                        OffsetDateTime sentAt, boolean resendIntervalElapsed) {
    public enum Status { PENDING, SENT, FAILED, CANCELLED }
}
