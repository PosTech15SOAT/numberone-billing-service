package br.com.fiap.numberone.billing.budget.application.gateways;

import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.valueobjects.BudgetComposition;
import br.com.fiap.numberone.billing.budget.application.results.PublicBudgetView;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BudgetGateway {

    Budget createDraft(UUID serviceOrderId, BigDecimal quotedAmount, BudgetComposition composition);

    Budget sendForApproval(UUID budgetId, String customerEmail);

    Budget resendForApproval(UUID budgetId, String customerEmail);

    boolean isCurrentSentRevision(UUID budgetId);

    Budget approve(UUID budgetId);

    Budget registerApproval(UUID budgetId, String employeeId);

    Budget reject(UUID budgetId);

    PublicBudgetView getAuthorizedBudget(String token);

    Budget approveRemotely(String token);

    Budget rejectRemotely(String token);

    Optional<Budget> findById(UUID budgetId);

    Optional<Budget> findByServiceOrderIdAndBudgetVersion(UUID serviceOrderId, int budgetVersion);

    List<Budget> findAllByServiceOrderId(UUID serviceOrderId);
}
