package br.com.fiap.numberone.billing.budget.application.usecases;

import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetNotFoundException;
import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.application.gateways.BudgetNotificationGateway;
import br.com.fiap.numberone.billing.budget.application.results.BudgetDetails;
import br.com.fiap.numberone.billing.budget.domain.enums.BudgetStatus;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetDataException;

import java.util.UUID;

public class GetBudgetUseCase {

    private final BudgetGateway budgetGateway;
    private final BudgetNotificationGateway notificationGateway;

    public GetBudgetUseCase(BudgetGateway budgetGateway, BudgetNotificationGateway notificationGateway) {
        this.budgetGateway = budgetGateway;
        this.notificationGateway = notificationGateway;
    }

    public BudgetDetails execute(UUID budgetId) {
        if (budgetId == null) {
            throw new InvalidBudgetDataException("O identificador do orçamento é obrigatório");
        }
        Budget budget = budgetGateway.findById(budgetId).orElseThrow(BudgetNotFoundException::new);
        var notification = notificationGateway.findLatest(budgetId).orElse(null);
        boolean canResend = budget.getStatus() == BudgetStatus.SENT && budgetGateway.isCurrentSentRevision(budgetId)
                && (notification == null || notification.resendIntervalElapsed());
        return new BudgetDetails(budget, notification, canResend);
    }
}
