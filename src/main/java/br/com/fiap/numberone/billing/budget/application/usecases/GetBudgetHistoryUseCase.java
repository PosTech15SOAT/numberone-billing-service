package br.com.fiap.numberone.billing.budget.application.usecases;

import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.application.results.BudgetHistory;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetDataException;

import java.util.Comparator;
import java.util.UUID;

public class GetBudgetHistoryUseCase {

    private final BudgetGateway budgetGateway;

    public GetBudgetHistoryUseCase(BudgetGateway budgetGateway) {
        this.budgetGateway = budgetGateway;
    }

    public BudgetHistory execute(UUID serviceOrderId) {
        if (serviceOrderId == null) {
            throw new InvalidBudgetDataException("O identificador da ordem de serviço é obrigatório");
        }
        var budgets = budgetGateway.findAllByServiceOrderId(serviceOrderId);
        var referenceId = budgets.stream()
                .filter(budget -> Budget.sentRevisionStatuses().contains(budget.getStatus()))
                .max(Comparator.comparingInt(Budget::getBudgetVersion))
                .map(Budget::getBudgetId).orElse(null);
        return new BudgetHistory(serviceOrderId, budgets, referenceId);
    }
}
