package br.com.fiap.numberone.billing.budget.application.usecases;

import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetNotFoundException;
import br.com.fiap.numberone.billing.budget.application.exceptions.EmployeeIdentityUnavailableException;
import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.application.gateways.EmployeeIdentityGateway;
import br.com.fiap.numberone.billing.budget.application.gateways.ServiceOrderGateway;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.enums.BudgetStatus;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetDataException;

import java.util.UUID;

public class RegisterBudgetApprovalUseCase {

    private final BudgetGateway budgetGateway;
    private final EmployeeIdentityGateway employeeIdentityGateway;
    private final ServiceOrderGateway serviceOrderGateway;

    public RegisterBudgetApprovalUseCase(BudgetGateway budgetGateway, EmployeeIdentityGateway employeeIdentityGateway,
                                         ServiceOrderGateway serviceOrderGateway) {
        this.budgetGateway = budgetGateway;
        this.employeeIdentityGateway = employeeIdentityGateway;
        this.serviceOrderGateway = serviceOrderGateway;
    }

    public Budget execute(UUID budgetId) {
        if (budgetId == null) {
            throw new InvalidBudgetDataException("O identificador do orçamento é obrigatório");
        }
        String employeeId = employeeIdentityGateway.currentEmployeeId();
        if (employeeId == null || employeeId.isBlank()) {
            throw new EmployeeIdentityUnavailableException();
        }
        Budget budget = budgetGateway.findById(budgetId).orElseThrow(BudgetNotFoundException::new);
        if (budget.getStatus() == BudgetStatus.DRAFT) {
            var context = serviceOrderGateway.findByServiceOrderId(budget.getServiceOrderId());
            ServiceOrderContextValidation.requireMatching(context, budget.getServiceOrderId());
            context.validateBudgetSendingEligibility();
            ServiceOrderContextValidation.requireQuotedComposition(budget, context);
        }
        return budgetGateway.registerApproval(budgetId, employeeId);
    }
}
