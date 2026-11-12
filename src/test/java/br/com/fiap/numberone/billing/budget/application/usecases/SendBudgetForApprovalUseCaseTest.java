package br.com.fiap.numberone.billing.budget.application.usecases;

import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetNotFoundException;
import br.com.fiap.numberone.billing.budget.application.exceptions.ServiceOrderNotFoundException;
import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.application.gateways.ServiceOrderGateway;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.enums.BudgetStatus;
import br.com.fiap.numberone.billing.budget.domain.exceptions.CustomerEmailException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidServiceOrderStatusException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetDataException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetStatusException;
import br.com.fiap.numberone.billing.budget.domain.references.ServiceOrderQuotationContext;
import br.com.fiap.numberone.billing.budget.domain.valueobjects.BudgetComposition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SendBudgetForApprovalUseCaseTest {

    private final BudgetGateway budgets = mock(BudgetGateway.class);
    private final ServiceOrderGateway serviceOrderGateway = mock(ServiceOrderGateway.class);
    private final SendBudgetForApprovalUseCase useCase = new SendBudgetForApprovalUseCase(budgets, serviceOrderGateway);
    private final Budget budget = Budget.createDraft(UUID.randomUUID(), 3, new BigDecimal("123.45"),
            new BudgetComposition(List.of(new BudgetComposition.ServiceItem(UUID.randomUUID(), UUID.randomUUID(),
                    null, null, List.of()))));

    @Test
    void shouldSendDraftWithoutRecalculatingOrChangingIdentityAndComposition() {
        stubContext("IN_DIAGNOSIS", "cliente@example.com");
        var composition = budget.getComposition();
        var id = budget.getBudgetId();
        var createdAt = budget.getCreatedAt();

        Budget result = useCase.execute(id);

        assertThat(result.getStatus()).isEqualTo(BudgetStatus.SENT);
        assertThat(result.getSentAt()).isNotNull();
        assertThat(result.getUpdatedAt()).isEqualTo(result.getSentAt());
        assertThat(result.getQuotedAmount()).isEqualByComparingTo("123.45");
        assertThat(result.getComposition()).isSameAs(composition);
        assertThat(result.getBudgetId()).isEqualTo(id);
        assertThat(result.getBudgetVersion()).isEqualTo(3);
        assertThat(result.getCreatedAt()).isEqualTo(createdAt);
        verify(budgets).sendForApproval(budget.getBudgetId(), "cliente@example.com");
    }

    @ParameterizedTest
    @EnumSource(value = BudgetStatus.class, names = {"SENT", "APPROVED", "REJECTED"})
    void shouldNotResendNonDraftBudget(BudgetStatus status) {
        budget.markAsSent();
        if (status == BudgetStatus.APPROVED) budget.approve();
        if (status == BudgetStatus.REJECTED) budget.reject();
        var sentAt = budget.getSentAt();
        stubContext("IN_DIAGNOSIS", "cliente@example.com");

        assertThatThrownBy(() -> useCase.execute(budget.getBudgetId())).isInstanceOf(InvalidBudgetStatusException.class);
        assertThat(budget.getSentAt()).isEqualTo(sentAt);
        verify(budgets).sendForApproval(budget.getBudgetId(), "cliente@example.com");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void shouldRejectMissingEmailBeforeChangingBudget(String email) {
        stubContext("IN_DIAGNOSIS", email);

        assertThatThrownBy(() -> useCase.execute(budget.getBudgetId())).isInstanceOf(CustomerEmailException.class);
        assertThat(budget.getStatus()).isEqualTo(BudgetStatus.DRAFT);
        assertThat(budget.getSentAt()).isNull();
        verify(budgets, never()).sendForApproval(any(), any());
    }

    @Test
    void shouldAcceptOrderInRejectedState() {
        stubContext("REJECTED", "cliente@example.com");
        assertThat(useCase.execute(budget.getBudgetId()).getStatus()).isEqualTo(BudgetStatus.SENT);
    }

    @Test
    void shouldRejectIneligibleOrder() {
        stubContext("COMPLETED", "cliente@example.com");
        assertThatThrownBy(() -> useCase.execute(budget.getBudgetId())).isInstanceOf(InvalidServiceOrderStatusException.class);
        assertThat(budget.getStatus()).isEqualTo(BudgetStatus.DRAFT);
        verify(budgets, never()).sendForApproval(any(), any());
    }

    @Test
    void shouldRejectMissingBudgetWithoutQueryingOrder() {
        when(budgets.findById(budget.getBudgetId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.execute(budget.getBudgetId())).isInstanceOf(BudgetNotFoundException.class);
        verifyNoInteractions(serviceOrderGateway);
    }

    @Test
    void shouldRejectMissingOrderWithoutUpdatingBudget() {
        when(budgets.findById(budget.getBudgetId())).thenReturn(Optional.of(budget));
        when(serviceOrderGateway.findByServiceOrderId(budget.getServiceOrderId())).thenThrow(new ServiceOrderNotFoundException());
        assertThatThrownBy(() -> useCase.execute(budget.getBudgetId())).isInstanceOf(ServiceOrderNotFoundException.class);
        assertThat(budget.getStatus()).isEqualTo(BudgetStatus.DRAFT);
        verify(budgets, never()).sendForApproval(any(), any());
    }

    @Test
    void shouldRejectMismatchedContext() {
        when(budgets.findById(budget.getBudgetId())).thenReturn(Optional.of(budget));
        when(serviceOrderGateway.findByServiceOrderId(budget.getServiceOrderId()))
                .thenReturn(new ServiceOrderQuotationContext(UUID.randomUUID(), "IN_DIAGNOSIS", List.of(), "a@b.com"));
        assertThatThrownBy(() -> useCase.execute(budget.getBudgetId())).isInstanceOf(InvalidBudgetDataException.class);
        verify(budgets, never()).sendForApproval(any(), any());
    }

    @Test
    void shouldRejectNullIdentifier() {
        assertThatThrownBy(() -> useCase.execute(null)).isInstanceOf(InvalidBudgetDataException.class);
        verifyNoInteractions(budgets, serviceOrderGateway);
    }

    private void stubContext(String status, String email) {
        when(budgets.sendForApproval(eq(budget.getBudgetId()), anyString())).thenAnswer(invocation -> {
            budget.markAsSent();
            return budget;
        });
        when(budgets.findById(budget.getBudgetId())).thenReturn(Optional.of(budget));
        when(serviceOrderGateway.findByServiceOrderId(budget.getServiceOrderId()))
                .thenReturn(new ServiceOrderQuotationContext(budget.getServiceOrderId(), status, List.of(), email));
    }
}
