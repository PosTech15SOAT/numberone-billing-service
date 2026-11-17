package br.com.fiap.numberone.billing.shared.infrastructure.outbox;

import java.util.UUID;

public record ClaimedIntegrationEvent(UUID eventId, String envelope, UUID leaseToken, int attemptCount) {
}
