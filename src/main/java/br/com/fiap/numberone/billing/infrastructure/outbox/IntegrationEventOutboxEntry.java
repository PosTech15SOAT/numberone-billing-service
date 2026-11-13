package br.com.fiap.numberone.billing.infrastructure.outbox;

import java.time.Instant;
import java.util.UUID;

/** Dados persistíveis na Outbox compartilhada; referências comerciais opcionais por domínio. */
public record IntegrationEventOutboxEntry(UUID eventId, String eventType, int schemaVersion, UUID serviceOrderId,
                                         UUID budgetId, Integer budgetVersion, UUID chargeId,
                                         Instant occurredAt, String envelope) {
    @Override public String toString() { return "IntegrationEventOutboxEntry[REDACTED]"; }
}
