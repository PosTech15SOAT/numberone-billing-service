package br.com.fiap.numberone.billing.budget.application.usecases;

import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetAuthorizationException;
import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;

public class ApproveBudgetRemotelyUseCase {
    private final BudgetGateway gateway;
    public ApproveBudgetRemotelyUseCase(BudgetGateway gateway) { this.gateway = gateway; }
    public Budget execute(String token, boolean confirmed) {
        if (!confirmed) throw new BudgetAuthorizationException(BudgetAuthorizationException.Reason.CONFIRMATION_REQUIRED);
        return gateway.approveRemotely(token);
    }
}
