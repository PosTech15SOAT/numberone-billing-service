package br.com.fiap.numberone.billing.budget.application.usecases;

import br.com.fiap.numberone.billing.budget.application.exceptions.BillingSagaConflict;
import br.com.fiap.numberone.billing.budget.application.gateways.BillingSagaStore;
import br.com.fiap.numberone.billing.budget.application.gateways.BillingSagaStore.Cancellation;
import br.com.fiap.numberone.billing.budget.application.gateways.BillingSagaStore.ClosedContext;
import br.com.fiap.numberone.billing.payment.application.gateways.ServiceOrderPaymentGateway;
import br.com.fiap.numberone.billing.payment.domain.references.ServiceOrderPaymentContext;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CloseBillingContextTest {
    private final BillingSagaStore store = mock(BillingSagaStore.class);
    private final ServiceOrderPaymentGateway orders = mock(ServiceOrderPaymentGateway.class);
    private final CloseBillingContext useCase = new CloseBillingContext(store, orders);
    private final Cancellation request = new Cancellation(UUID.randomUUID(), UUID.randomUUID(), null, null);

    @Test void shouldReplayCancellationWithoutCallingOsOrClosingAgain() {
        var previous = new ClosedContext(request.operationId(), request.serviceOrderId(), null, null);
        when(store.previousCancellation(request)).thenReturn(Optional.of(previous));
        assertThat(useCase.execute(request)).isEqualTo(previous);
        verifyNoInteractions(orders);
        verify(store, never()).closeContext(any());
    }

    @Test void shouldCloseOnlyWhenOsCancellationIsInProgressBeforeExecution() {
        var closed = new ClosedContext(request.operationId(), request.serviceOrderId(), null, null);
        when(orders.findById(request.serviceOrderId())).thenReturn(new ServiceOrderPaymentContext(request.serviceOrderId(), "IN_DIAGNOSIS", true));
        when(store.closeContext(request)).thenReturn(closed);
        assertThat(useCase.execute(request)).isEqualTo(closed);
        verify(store).closeContext(request);
    }

    @Test void shouldRejectWhenOsDoesNotConfirmCancellation() {
        when(orders.findById(request.serviceOrderId())).thenReturn(new ServiceOrderPaymentContext(request.serviceOrderId(), "IN_DIAGNOSIS", false));
        assertThatThrownBy(() -> useCase.execute(request)).isInstanceOf(BillingSagaConflict.class);
        verify(store, never()).closeContext(any());
    }
}
