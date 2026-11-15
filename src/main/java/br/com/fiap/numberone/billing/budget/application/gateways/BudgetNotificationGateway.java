package br.com.fiap.numberone.billing.budget.application.gateways;

import br.com.fiap.numberone.billing.budget.application.results.BudgetNotificationSummary;

import java.util.Optional;
import java.util.UUID;

public interface BudgetNotificationGateway {
    Optional<BudgetNotificationSummary> findLatest(UUID budgetId);
}
