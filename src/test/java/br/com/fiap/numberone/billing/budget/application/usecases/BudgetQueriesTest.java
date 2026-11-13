package br.com.fiap.numberone.billing.budget.application.usecases;

import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetNotFoundException;
import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.enums.BudgetStatus;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetDataException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BudgetQueriesTest {

    private final BudgetGateway gateway = mock(BudgetGateway.class);
    private final GetBudgetUseCase getBudget = new GetBudgetUseCase(gateway, id -> Optional.empty());
    private final GetBudgetHistoryUseCase getHistory = new GetBudgetHistoryUseCase(gateway);
    private final UUID orderId = UUID.randomUUID();

    @Test
    void shouldReturnBudgetOrReportItMissing() {
        Budget budget = revision(1, BudgetStatus.DRAFT);
        when(gateway.findById(budget.getBudgetId())).thenReturn(Optional.of(budget));
        assertThat(getBudget.execute(budget.getBudgetId()).budget()).isSameAs(budget);
        UUID missing = UUID.randomUUID();
        when(gateway.findById(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> getBudget.execute(missing)).isInstanceOf(BudgetNotFoundException.class);
    }

    @Test
    void shouldValidateIdentifiersBeforeReading() {
        assertThatThrownBy(() -> getBudget.execute(null)).isInstanceOf(InvalidBudgetDataException.class);
        assertThatThrownBy(() -> getHistory.execute(null)).isInstanceOf(InvalidBudgetDataException.class);
        verifyNoInteractions(gateway);
    }

    @Test
    void shouldReturnEmptyHistoryWithoutAssumingOrderDoesNotExist() {
        when(gateway.findAllByServiceOrderId(orderId)).thenReturn(List.of());
        var history = getHistory.execute(orderId);
        assertThat(history.serviceOrderId()).isEqualTo(orderId);
        assertThat(history.budgets()).isEmpty();
        assertThat(history.referenceBudgetId()).isNull();
        verify(gateway).findAllByServiceOrderId(orderId);
        verifyNoMoreInteractions(gateway);
    }

    @ParameterizedTest
    @EnumSource(value = BudgetStatus.class, names = {"SENT", "APPROVED", "REJECTED"})
    void shouldUseHighestCommercialVersionAndKeepNewerDraftsOutOfReference(BudgetStatus status) {
        Budget old = revision(1, BudgetStatus.SENT);
        Budget reference = revision(2, status);
        Budget draft = revision(3, BudgetStatus.DRAFT);
        when(gateway.findAllByServiceOrderId(orderId)).thenReturn(List.of(old, reference, draft));
        var history = getHistory.execute(orderId);
        assertThat(history.budgets()).containsExactly(old, reference, draft);
        assertThat(history.referenceBudgetId()).isEqualTo(reference.getBudgetId());
        assertThat(draft.getStatus()).isEqualTo(BudgetStatus.DRAFT);
        assertThat(old.getStatus()).isEqualTo(BudgetStatus.SENT);
        verify(gateway).findAllByServiceOrderId(orderId);
        verifyNoMoreInteractions(gateway);
    }

    @Test
    void shouldHaveNoReferenceForDraftsAndCancelledRevisions() {
        when(gateway.findAllByServiceOrderId(orderId)).thenReturn(List.of(
                revision(1, BudgetStatus.DRAFT), revision(2, BudgetStatus.CANCELLED)));
        assertThat(getHistory.execute(orderId).referenceBudgetId()).isNull();
    }

    private Budget revision(int version, BudgetStatus status) {
        var now = LocalDateTime.now();
        return Budget.restore(UUID.randomUUID(), orderId, version, BigDecimal.TEN, null, status,
                null, null, now, now);
    }
}
