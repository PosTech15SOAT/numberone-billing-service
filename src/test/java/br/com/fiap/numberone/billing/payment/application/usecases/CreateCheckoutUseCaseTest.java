package br.com.fiap.numberone.billing.payment.application.usecases;

import br.com.fiap.numberone.billing.payment.application.exceptions.CheckoutCreationException;
import br.com.fiap.numberone.billing.payment.application.exceptions.CheckoutOutcomeUnknownException;
import br.com.fiap.numberone.billing.payment.application.exceptions.ServiceOrderNotFoundException;
import br.com.fiap.numberone.billing.payment.application.exceptions.ServiceOrderPaymentUnavailableException;
import br.com.fiap.numberone.billing.payment.application.gateways.ChargeGateway;
import br.com.fiap.numberone.billing.payment.application.gateways.CheckoutGateway;
import br.com.fiap.numberone.billing.payment.application.gateways.ServiceOrderPaymentGateway;
import br.com.fiap.numberone.billing.payment.application.results.CheckoutOrder;
import br.com.fiap.numberone.billing.payment.application.results.CheckoutResult;
import br.com.fiap.numberone.billing.payment.application.results.PreparedCheckout;
import br.com.fiap.numberone.billing.payment.domain.entities.Charge;
import br.com.fiap.numberone.billing.payment.domain.entities.PaymentAttempt;
import br.com.fiap.numberone.billing.payment.domain.enums.PaymentAttemptStatus;
import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentDataException;
import br.com.fiap.numberone.billing.payment.domain.references.ServiceOrderPaymentContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CreateCheckoutUseCaseTest {
    private final ServiceOrderPaymentGateway serviceOrders = mock(ServiceOrderPaymentGateway.class);
    private final ChargeGateway charges = mock(ChargeGateway.class);
    private final CheckoutGateway checkout = mock(CheckoutGateway.class);
    private final CheckoutGateway.ValidatedCheckout validatedCheckout = mock(CheckoutGateway.ValidatedCheckout.class);
    private final CreateCheckoutUseCase useCase = new CreateCheckoutUseCase(serviceOrders, charges, checkout);
    private final UUID orderId = UUID.randomUUID();
    private final UUID key = UUID.randomUUID();
    private final ServiceOrderPaymentContext completed = new ServiceOrderPaymentContext(orderId, "COMPLETED");
    private Charge charge;
    private PaymentAttempt attempt;

    @BeforeEach
    void setUp() {
        when(checkout.validateSeller()).thenReturn(validatedCheckout);
        charge = Charge.create(completed, UUID.randomUUID(), BigDecimal.TEN, Currency.getInstance("BRL"));
        attempt = charge.startAttempt(key);
        charge.claimCheckoutCreation(attempt.getAttemptId());
        when(serviceOrders.findById(orderId)).thenReturn(completed);
    }

    @Test
    void shouldCreateOnlyAfterPreparationAndReturnPersistedResult() {
        prepare(true);
        when(validatedCheckout.createCheckout(charge, attempt.getAttemptId())).thenReturn(remoteOrder());
        when(charges.associateProviderOrder(charge.getChargeId(), attempt.getAttemptId(), "order-test", url()))
                .thenAnswer(invocation -> {
                    charge.associateProviderOrder(attempt.getAttemptId(), "order-test", url());
                    return charge;
                });
        CheckoutResult result = useCase.execute(orderId, key);
        assertThat(result.chargeId()).isEqualTo(charge.getChargeId());
        assertThat(result.attemptId()).isEqualTo(attempt.getAttemptId());
        assertThat(result.checkoutUrl()).isEqualTo(url());
        assertThat(result.providerOrderId()).isEqualTo("order-test");
        assertThat(result.status()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(attempt.getProviderPaymentId()).isNull();
        assertThat(result.toString()).doesNotContain(url());
        InOrder order = inOrder(charges, checkout, validatedCheckout);
        order.verify(charges).inspectCheckout(orderId, key);
        order.verify(checkout).validateSeller();
        order.verify(charges).prepareCheckout(completed, key);
        order.verify(validatedCheckout).createCheckout(charge, attempt.getAttemptId());
        order.verify(charges).associateProviderOrder(charge.getChargeId(), attempt.getAttemptId(), "order-test", url());
    }

    @Test
    void shouldReplayKnownUrlWithoutCallingProviderOrWriting() {
        charge.associateProviderOrder(attempt.getAttemptId(), "order-test", url());
        prepare(false);
        assertThat(useCase.execute(orderId, key).checkoutUrl()).isEqualTo(url());
        verifyNoInteractions(checkout);
        verify(charges).inspectCheckout(orderId, key);
        verifyNoMoreInteractions(charges);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentAttemptStatus.class, names = {"PENDING", "UNKNOWN", "FAILED", "EXPIRED"})
    void shouldReturnExistingStateWithoutTakingOverReservation(PaymentAttemptStatus status) {
        if (status == PaymentAttemptStatus.UNKNOWN) charge.markAttemptUnknown(attempt.getAttemptId());
        if (status == PaymentAttemptStatus.FAILED) charge.confirmAttemptFailure(attempt.getAttemptId());
        if (status == PaymentAttemptStatus.EXPIRED) charge.confirmAttemptExpiration(attempt.getAttemptId());
        prepare(false);
        assertThat(useCase.execute(orderId, key).status()).isEqualTo(status);
        verifyNoInteractions(checkout);
    }

    @ParameterizedTest
    @EnumSource(CheckoutCreationException.Kind.class)
    void shouldRetainEveryProviderFailureForReconciliationWithoutFinancialFailure(CheckoutCreationException.Kind kind) {
        prepare(true);
        when(validatedCheckout.createCheckout(charge, attempt.getAttemptId()))
                .thenThrow(new CheckoutCreationException(kind, null, kind != CheckoutCreationException.Kind.DISABLED, null));
        when(charges.markAttemptUnknown(charge.getChargeId(), attempt.getAttemptId())).thenAnswer(invocation -> {
            charge.markAttemptUnknown(attempt.getAttemptId());
            return charge;
        });
        assertThat(useCase.execute(orderId, key).status()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(attempt.getIdempotencyKey()).isEqualTo(key);
        assertThat(attempt.getCheckoutCreationClaimedAt()).isNotNull();
        verify(charges, never()).confirmAttemptFailure(any(), any());
        verify(validatedCheckout, times(1)).createCheckout(any(), any());
    }

    @Test
    void shouldPreserveOriginalFailureWhenResponseCannotBeStored() {
        prepare(true);
        RuntimeException failure = new IllegalStateException("database unavailable");
        when(validatedCheckout.createCheckout(charge, attempt.getAttemptId())).thenReturn(remoteOrder());
        when(charges.associateProviderOrder(any(), any(), any(), any())).thenThrow(failure);
        when(charges.markAttemptUnknown(any(), any())).thenReturn(charge);
        assertThatThrownBy(() -> useCase.execute(orderId, key))
                .isInstanceOfSatisfying(CheckoutOutcomeUnknownException.class, exception -> {
                    assertThat(exception.getChargeId()).isEqualTo(charge.getChargeId());
                    assertThat(exception.getAttemptId()).isEqualTo(attempt.getAttemptId());
                }).hasCause(failure);
        verify(charges).markAttemptUnknown(charge.getChargeId(), attempt.getAttemptId());
        verify(validatedCheckout, times(1)).createCheckout(any(), any());
    }

    @Test
    void shouldRetainBothCausesIfUnknownCannotBeStoredEither() {
        prepare(true);
        CheckoutCreationException remoteFailure = new CheckoutCreationException(
                CheckoutCreationException.Kind.TIMEOUT, null, true, null);
        RuntimeException persistenceFailure = new IllegalStateException("db unavailable");
        when(validatedCheckout.createCheckout(any(), any())).thenThrow(remoteFailure);
        when(charges.markAttemptUnknown(any(), any())).thenThrow(persistenceFailure);
        assertThatThrownBy(() -> useCase.execute(orderId, key)).hasCause(remoteFailure)
                .satisfies(exception -> assertThat(exception.getSuppressed()).containsExactly(persistenceFailure));
    }

    @ParameterizedTest
    @ValueSource(strings = {"IN_PROGRESS", "FINALIZADA", "CANCELLED", "completed"})
    void shouldRejectAnythingOtherThanTrustedCompleted(String status) {
        when(serviceOrders.findById(orderId)).thenReturn(new ServiceOrderPaymentContext(orderId, status));
        assertThatThrownBy(() -> useCase.execute(orderId, key)).isInstanceOf(InvalidPaymentDataException.class);
        verify(charges, never()).prepareCheckout(any(), any());
        verifyNoInteractions(checkout);
    }

    @Test
    void shouldPropagateNotFoundAndConsultationFailureWithoutPreparing() {
        for (RuntimeException failure : new RuntimeException[]{new ServiceOrderNotFoundException(),
                new ServiceOrderPaymentUnavailableException()}) {
            doThrow(failure).when(serviceOrders).findById(orderId);
            assertThatThrownBy(() -> useCase.execute(orderId, key)).isSameAs(failure);
        }
        verify(charges, never()).prepareCheckout(any(), any());
        verifyNoInteractions(checkout);
    }

    @Test
    void shouldRejectWrongOrAbsentIdentityFromServiceOrderGateway() {
        when(serviceOrders.findById(orderId)).thenReturn(null,
                new ServiceOrderPaymentContext(UUID.randomUUID(), "COMPLETED"));
        for (int index = 0; index < 2; index++) {
            assertThatThrownBy(() -> useCase.execute(orderId, key)).isInstanceOf(InvalidPaymentDataException.class);
        }
        verify(charges, never()).prepareCheckout(any(), any());
        verifyNoInteractions(checkout);
    }

    @Test
    void shouldRejectMissingInputBeforeExternalLookup() {
        assertThatThrownBy(() -> useCase.execute(null, key)).isInstanceOf(InvalidPaymentDataException.class);
        assertThatThrownBy(() -> useCase.execute(orderId, null)).isInstanceOf(InvalidPaymentDataException.class);
        verifyNoInteractions(serviceOrders, charges, checkout);
    }

    @Test
    void shouldNotCallProviderWhenPreparationFails() {
        when(charges.prepareCheckout(completed, key)).thenThrow(new InvalidPaymentDataException("Sem orçamento"));
        assertThatThrownBy(() -> useCase.execute(orderId, key)).isInstanceOf(InvalidPaymentDataException.class);
        verify(checkout).validateSeller();
        verifyNoInteractions(validatedCheckout);
        verify(charges, never()).markAttemptUnknown(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = CheckoutCreationException.Kind.class, names = {"AUTHORIZATION", "TIMEOUT", "COMMUNICATION", "INVALID_RESPONSE", "DISABLED"})
    void shouldPropagateValidationFailureWithoutAnyReservationOrUnknown(CheckoutCreationException.Kind kind) {
        CheckoutCreationException failure = new CheckoutCreationException(kind, null, false, null);
        when(checkout.validateSeller()).thenThrow(failure);
        assertThatThrownBy(() -> useCase.execute(orderId, key)).isSameAs(failure);
        verify(charges, never()).prepareCheckout(any(), any());
        verify(charges, never()).markAttemptUnknown(any(), any());
        verifyNoInteractions(validatedCheckout);
    }

    @Test void shouldReturnReplayWithoutEvenConsultingServiceOrder() {
        charge.associateProviderOrder(attempt.getAttemptId(), "order-test", url());
        prepare(false);
        doThrow(new ServiceOrderPaymentUnavailableException()).when(serviceOrders).findById(any());
        assertThat(useCase.execute(orderId, key).checkoutUrl()).isEqualTo(url());
        verifyNoInteractions(serviceOrders, checkout, validatedCheckout);
    }

    @Test void shouldDiscardValidatedOperationWhenAnotherRequestAlreadyReserved() {
        when(charges.prepareCheckout(completed, key)).thenReturn(new PreparedCheckout(charge, attempt.getAttemptId(), false));
        assertThat(useCase.execute(orderId, key).attemptId()).isEqualTo(attempt.getAttemptId());
        verify(checkout).validateSeller();
        verifyNoInteractions(validatedCheckout);
    }

    private void prepare(boolean acquired) {
        if (!acquired) when(charges.inspectCheckout(orderId, key)).thenReturn(java.util.Optional.of(CheckoutResult.from(charge, attempt.getAttemptId())));
        else when(charges.prepareCheckout(completed, key)).thenReturn(new PreparedCheckout(charge, attempt.getAttemptId(), true));
    }

    private CheckoutOrder remoteOrder() { return new CheckoutOrder("order-test", URI.create(url()), "created"); }
    private String url() { return "https://www.mercadopago.com.br/checkout/v1/redirect?pref_id=fictitious"; }
}
