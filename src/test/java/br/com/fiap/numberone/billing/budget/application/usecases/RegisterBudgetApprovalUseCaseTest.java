package br.com.fiap.numberone.billing.budget.application.usecases;

import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.application.gateways.ServiceOrderGateway;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidServiceOrderStatusException;
import br.com.fiap.numberone.billing.budget.domain.references.ServiceOrderQuotationContext;
import br.com.fiap.numberone.billing.budget.domain.valueobjects.BudgetComposition;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegisterBudgetApprovalUseCaseTest {

    private final BudgetGateway gateway = mock(BudgetGateway.class);
    private final ServiceOrderGateway serviceOrderGateway = mock(ServiceOrderGateway.class);
    private final RegisterBudgetApprovalUseCase useCase = new RegisterBudgetApprovalUseCase(gateway, () -> "validated-subject", serviceOrderGateway);

    @Test
    void shouldPreserveOrderSendingEligibilityForDraftWithoutRequiringEmail() {
        Budget budget = draft();
        when(gateway.findById(budget.getBudgetId())).thenReturn(Optional.of(budget));
        when(serviceOrderGateway.findByServiceOrderId(budget.getServiceOrderId())).thenReturn(
                new ServiceOrderQuotationContext(budget.getServiceOrderId(), "COMPLETED", List.of()));
        assertThatThrownBy(() -> useCase.execute(budget.getBudgetId())).isInstanceOf(InvalidServiceOrderStatusException.class);
        verify(gateway, never()).registerApproval(any(), anyString());
        when(serviceOrderGateway.findByServiceOrderId(budget.getServiceOrderId())).thenReturn(
                new ServiceOrderQuotationContext(budget.getServiceOrderId(), "IN_DIAGNOSIS", List.of()));
        when(gateway.registerApproval(budget.getBudgetId(), "validated-subject")).thenReturn(budget);
        assertThat(useCase.execute(budget.getBudgetId())).isSameAs(budget);
        verify(gateway).registerApproval(budget.getBudgetId(), "validated-subject");
    }

    @Test
    void shouldNotConsultOrderAgainForAnAlreadySentBudget() {
        Budget budget = draft();
        budget.markAsSent();
        when(gateway.findById(budget.getBudgetId())).thenReturn(Optional.of(budget));
        when(gateway.registerApproval(budget.getBudgetId(), "validated-subject")).thenReturn(budget);
        useCase.execute(budget.getBudgetId());
        verifyNoInteractions(serviceOrderGateway);
        verify(gateway).registerApproval(budget.getBudgetId(), "validated-subject");
    }

    private Budget draft() {
        return Budget.createDraft(UUID.randomUUID(), 1, BigDecimal.TEN, new BudgetComposition(List.of()));
    }
}
