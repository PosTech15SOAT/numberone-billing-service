package br.com.fiap.numberone.billing.budget.infrastructure.notifications;

import java.util.UUID;

public record ClaimedBudgetNotification(UUID notificationId, UUID authorizationId, UUID leaseToken,
                                        int attemptCount, byte[] protectedContent) {
    @Override
    public String toString() { return "ClaimedBudgetNotification[id=" + notificationId + "]"; }
}
