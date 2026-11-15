package br.com.fiap.numberone.billing.budget.application.usecases;

import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetNotFoundException;
import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.application.gateways.ServiceOrderGateway;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.exceptions.CustomerEmailException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetDataException;

import java.util.UUID;

public class ResendBudgetApprovalUseCase {
    private final BudgetGateway budgetGateway;
    private final ServiceOrderGateway serviceOrderGateway;

    public ResendBudgetApprovalUseCase(BudgetGateway budgetGateway, ServiceOrderGateway serviceOrderGateway) {
        this.budgetGateway = budgetGateway;
        this.serviceOrderGateway = serviceOrderGateway;
    }

    public Budget execute(UUID budgetId) {
        if (budgetId == null) {
            throw new InvalidBudgetDataException("O identificador do orçamento é obrigatório");
        }
        Budget budget = budgetGateway.findById(budgetId).orElseThrow(BudgetNotFoundException::new);
        budget.validateApprovalResend();
        var context = serviceOrderGateway.findByServiceOrderId(budget.getServiceOrderId());
        ServiceOrderContextValidation.requireMatching(context, budget.getServiceOrderId());
        if (context.customerEmail() == null || context.customerEmail().isBlank()) {
            throw new CustomerEmailException();
        }
        return budgetGateway.resendForApproval(budgetId, context.customerEmail());
    }
}
