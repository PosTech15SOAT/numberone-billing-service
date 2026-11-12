package br.com.fiap.numberone.billing.budget.infrastructure.outbox;

import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.enums.BudgetStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Component
public class BudgetIntegrationEventFactory {

    private final JsonMapper jsonMapper;

    public BudgetIntegrationEventFactory(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public BudgetIntegrationEvent create(Budget budget, UUID correlationId) {
        Objects.requireNonNull(correlationId, "O identificador de correlação é obrigatório");
        String eventType = switch (budget.getStatus()) {
            case SENT -> "OrcamentoEnviado";
            case APPROVED -> "OrcamentoAprovado";
            case REJECTED -> "OrcamentoRejeitado";
            default -> throw new IllegalArgumentException("O estado não corresponde a um evento de orçamento");
        };
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var envelope = jsonMapper.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("schemaVersion", 1);
        envelope.put("occurredAt", occurredAt.toString());
        envelope.put("correlationId", correlationId.toString());
        var payload = envelope.putObject("payload");
        payload.put("serviceOrderId", budget.getServiceOrderId().toString());
        payload.put("budgetId", budget.getBudgetId().toString());
        payload.put("budgetVersion", budget.getBudgetVersion());
        if (budget.getStatus() == BudgetStatus.APPROVED) {
            payload.put("approvedAmount", budget.getApprovedAmount().toPlainString());
        }
        return new BudgetIntegrationEvent(eventId, eventType, 1, budget.getServiceOrderId(), budget.getBudgetId(),
                budget.getBudgetVersion(), occurredAt, jsonMapper.writeValueAsString(envelope));
    }
}
