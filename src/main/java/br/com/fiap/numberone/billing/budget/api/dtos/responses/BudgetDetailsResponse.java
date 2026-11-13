package br.com.fiap.numberone.billing.budget.api.dtos.responses;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

public record BudgetDetailsResponse(
        @JsonUnwrapped BudgetResponse budget,
        @JsonProperty("notificacao") BudgetNotificationResponse notification) {
}
