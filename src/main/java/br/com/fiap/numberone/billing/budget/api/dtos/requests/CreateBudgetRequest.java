package br.com.fiap.numberone.billing.budget.api.dtos.requests;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CreateBudgetRequest(
        @JsonProperty("valorProposto")
        @Positive(message = "valorProposto deve ser maior que zero")
        BigDecimal quotedAmount) {
}
