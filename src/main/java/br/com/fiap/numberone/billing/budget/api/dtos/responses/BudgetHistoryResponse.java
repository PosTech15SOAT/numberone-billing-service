package br.com.fiap.numberone.billing.budget.api.dtos.responses;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record BudgetHistoryResponse(
        @JsonProperty("idOrdemServico") UUID serviceOrderId,
        @JsonProperty("orcamentos") List<RevisionResponse> budgets,
        @JsonProperty("idOrcamentoReferencia") UUID referenceBudgetId) {

    public record RevisionResponse(
            @JsonProperty("id") UUID id,
            @JsonProperty("versao") int version,
            @JsonProperty("valorProposto") BigDecimal quotedAmount,
            @JsonProperty("valorAprovado") BigDecimal approvedAmount,
            @JsonProperty("status") BudgetStatusResponse status,
            @JsonProperty("enviadoEm") LocalDateTime sentAt,
            @JsonProperty("aprovadoEm") LocalDateTime approvedAt) {
    }
}
