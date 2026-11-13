package br.com.fiap.numberone.billing.budget.application.usecases;

import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetNotFoundException;
import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.application.gateways.ServiceOrderGateway;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetDataException;
import br.com.fiap.numberone.billing.budget.domain.references.ServiceOrderQuotationContext;

import java.util.UUID;

public class SendBudgetForApprovalUseCase {

    private final BudgetGateway budgetGateway;
    private final ServiceOrderGateway serviceOrderGateway;

    public SendBudgetForApprovalUseCase(BudgetGateway budgetGateway,
                                       ServiceOrderGateway serviceOrderGateway) {
        this.budgetGateway = budgetGateway;
        this.serviceOrderGateway = serviceOrderGateway;
    }

    public Budget execute(UUID budgetId) {
        if (budgetId == null) {
            throw new InvalidBudgetDataException("O identificador do orçamento é obrigatório");
        }
        Budget budget = budgetGateway.findById(budgetId).orElseThrow(BudgetNotFoundException::new);
        ServiceOrderQuotationContext context = serviceOrderGateway.findByServiceOrderId(budget.getServiceOrderId());
        ServiceOrderContextValidation.requireMatching(context, budget.getServiceOrderId());
        context.validateBudgetSending();
        ServiceOrderContextValidation.requireQuotedComposition(budget, context);
        return budgetGateway.sendForApproval(budgetId, context.customerEmail());
    }
}
