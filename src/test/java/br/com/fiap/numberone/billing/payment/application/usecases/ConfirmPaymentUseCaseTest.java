package br.com.fiap.numberone.billing.payment.application.usecases;

import br.com.fiap.numberone.billing.payment.application.gateways.ChargeGateway;
import br.com.fiap.numberone.billing.payment.application.results.OrderObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfirmPaymentUseCaseTest {
    private final ChargeGateway charges = mock(ChargeGateway.class);
    private final ConfirmPaymentUseCase useCase = new ConfirmPaymentUseCase(charges);

    @Test void shouldDelegateApprovedObservationWithoutReplacingIdentityOrEvidence() {
        OrderObservation observation = observation(OrderObservation.Situation.APPROVED);
        useCase.execute(observation);
        verify(charges).confirmPayment(same(observation));
        verifyNoMoreInteractions(charges);
    }

    @Test void shouldDelegateConfirmedRefundForRevalidation() {
        OrderObservation observation = observation(OrderObservation.Situation.REFUNDED);
        useCase.execute(observation);
        verify(charges).recordFullRefund(same(observation));
        verifyNoMoreInteractions(charges);
    }

    @ParameterizedTest
    @EnumSource(value = OrderObservation.Situation.class, names = {"APPROVED", "REFUNDED"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldPreserveNonConclusiveAndUnsupportedObservationsWithoutMutatingCharge(OrderObservation.Situation situation) {
        useCase.execute(observation(situation));
        verifyNoInteractions(charges);
    }

    @Test void shouldPreservePersistenceFailureCauseAndNotRetry() {
        OrderObservation observation = observation(OrderObservation.Situation.APPROVED);
        IllegalStateException failure = new IllegalStateException("controlled");
        when(charges.confirmPayment(observation)).thenThrow(failure);
        assertThatThrownBy(() -> useCase.execute(observation)).isSameAs(failure);
        verify(charges).confirmPayment(observation);
        verifyNoMoreInteractions(charges);
    }

    @Test void shouldRejectMissingObservation() {
        assertThatThrownBy(() -> useCase.execute(null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(charges);
    }

    private OrderObservation observation(OrderObservation.Situation situation) {
        return new OrderObservation("ORDtest", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), situation, null);
    }
}
