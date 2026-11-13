package br.com.fiap.numberone.billing.budget.application.results;

import br.com.fiap.numberone.billing.budget.domain.entities.Budget;

public record BudgetDetails(Budget budget, BudgetNotificationSummary notification, boolean canResend) { }
