package br.com.fiap.numberone.billing.budget.infrastructure.outbox;

import java.time.Instant;
import java.util.UUID;

public record BudgetIntegrationEvent(UUID eventId, String eventType, int schemaVersion,
                                     UUID serviceOrderId, UUID budgetId, int budgetVersion,
                                     Instant occurredAt, String envelope) {
}
