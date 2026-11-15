package br.com.fiap.numberone.billing.budget.application.results;

import br.com.fiap.numberone.billing.budget.domain.valueobjects.BudgetComposition;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PublicBudgetView(UUID budgetId, int budgetVersion, BigDecimal quotedAmount,
                               Instant expiresAt, BudgetComposition composition) { }
