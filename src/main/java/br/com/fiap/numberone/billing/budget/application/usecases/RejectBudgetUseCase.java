package br.com.fiap.numberone.billing.budget.application.usecases;

import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetDataException;

import java.util.UUID;

public class RejectBudgetUseCase {

    private final BudgetGateway budgetGateway;

    public RejectBudgetUseCase(BudgetGateway budgetGateway) {
        this.budgetGateway = budgetGateway;
    }

    public Budget execute(UUID budgetId) {
        if (budgetId == null) {
            throw new InvalidBudgetDataException("O identificador do orçamento é obrigatório");
        }
        return budgetGateway.reject(budgetId);
    }
}
