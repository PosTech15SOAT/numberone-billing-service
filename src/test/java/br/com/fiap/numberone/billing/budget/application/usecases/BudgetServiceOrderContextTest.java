package br.com.fiap.numberone.billing.budget.application.usecases;

import br.com.fiap.numberone.billing.budget.application.exceptions.ServiceOrderNotFoundException;
import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.application.gateways.InventoryGateway;
import br.com.fiap.numberone.billing.budget.application.gateways.ServiceOrderGateway;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetDataException;
import br.com.fiap.numberone.billing.budget.domain.references.ServiceOrderQuotationContext;
import br.com.fiap.numberone.billing.budget.domain.valueobjects.BudgetComposition;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class BudgetServiceOrderContextTest {

    private final BudgetGateway budgets = mock(BudgetGateway.class);
    private final ServiceOrderGateway serviceOrderGateway = mock(ServiceOrderGateway.class);
    private final InventoryGateway inventoryGateway = mock(InventoryGateway.class);
    private final Budget budget = Budget.createDraft(UUID.randomUUID(), 1, BigDecimal.TEN,
            new BudgetComposition(List.of()));

    @ParameterizedTest
    @CsvSource({"CREATE,false", "CREATE,true", "SEND,false", "SEND,true",
            "RESEND,false", "RESEND,true", "REGISTER_APPROVAL,false", "REGISTER_APPROVAL,true"})
    void shouldRejectNullOrMismatchedContextBeforeStatusOrEmailValidation(Operation operation, boolean mismatched) {
        prepareBudget(operation);
        if (mismatched) {
            when(serviceOrderGateway.findByServiceOrderId(budget.getServiceOrderId()))
                    .thenReturn(new ServiceOrderQuotationContext(UUID.randomUUID(), "COMPLETED", List.of()));
        }

        assertThatThrownBy(() -> execute(operation)).isInstanceOf(InvalidBudgetDataException.class)
                .hasMessage("O contexto técnico não corresponde à ordem de serviço solicitada");

        verifyNoMutation(operation);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void shouldPreserveMissingServiceOrderFailureFromGateway(Operation operation) {
        prepareBudget(operation);
        var failure = new ServiceOrderNotFoundException();
        when(serviceOrderGateway.findByServiceOrderId(budget.getServiceOrderId())).thenThrow(failure);

        assertThatThrownBy(() -> execute(operation)).isSameAs(failure);

        verifyNoMutation(operation);
    }

    private void prepareBudget(Operation operation) {
        if (operation == Operation.RESEND) budget.markAsSent();
        if (operation != Operation.CREATE) {
            when(budgets.findById(budget.getBudgetId())).thenReturn(Optional.of(budget));
        }
    }

    private void execute(Operation operation) {
        switch (operation) {
            case CREATE -> new CreateBudgetUseCase(serviceOrderGateway, inventoryGateway, budgets)
                    .execute(budget.getServiceOrderId(), BigDecimal.TEN);
            case SEND -> new SendBudgetForApprovalUseCase(budgets, serviceOrderGateway).execute(budget.getBudgetId());
            case RESEND -> new ResendBudgetApprovalUseCase(budgets, serviceOrderGateway).execute(budget.getBudgetId());
            case REGISTER_APPROVAL -> new RegisterBudgetApprovalUseCase(budgets, () -> "employee", serviceOrderGateway)
                    .execute(budget.getBudgetId());
        }
    }

    private void verifyNoMutation(Operation operation) {
        verify(serviceOrderGateway).findByServiceOrderId(budget.getServiceOrderId());
        if (operation != Operation.CREATE) verify(budgets).findById(budget.getBudgetId());
        verifyNoMoreInteractions(budgets, serviceOrderGateway);
        verifyNoInteractions(inventoryGateway);
    }

    private enum Operation { CREATE, SEND, RESEND, REGISTER_APPROVAL }
}
