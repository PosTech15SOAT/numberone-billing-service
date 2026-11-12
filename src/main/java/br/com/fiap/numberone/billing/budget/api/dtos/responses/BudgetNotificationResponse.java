package br.com.fiap.numberone.billing.budget.api.dtos.responses;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

public record BudgetNotificationResponse(
        @JsonProperty("status") String status,
        @JsonProperty("tentativas") int attempts,
        @JsonProperty("ultimaTentativaEm") OffsetDateTime lastAttemptAt,
        @JsonProperty("enviadoEm") OffsetDateTime sentAt,
        @JsonProperty("podeReenviar") boolean canResend) {
}
