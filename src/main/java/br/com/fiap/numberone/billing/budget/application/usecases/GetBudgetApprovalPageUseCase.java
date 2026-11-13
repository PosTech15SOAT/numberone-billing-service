package br.com.fiap.numberone.billing.budget.application.usecases;

import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.application.results.PublicBudgetView;

public class GetBudgetApprovalPageUseCase {
    private final BudgetGateway gateway;
    public GetBudgetApprovalPageUseCase(BudgetGateway gateway) { this.gateway = gateway; }
    public PublicBudgetView execute(String token) { return gateway.getAuthorizedBudget(token); }
}
