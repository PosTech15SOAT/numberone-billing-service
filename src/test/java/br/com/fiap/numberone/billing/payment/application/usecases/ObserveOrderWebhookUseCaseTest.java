package br.com.fiap.numberone.billing.payment.application.usecases;

import br.com.fiap.numberone.billing.payment.application.exceptions.OrderLookupException;
import br.com.fiap.numberone.billing.payment.application.gateways.ChargeGateway;
import br.com.fiap.numberone.billing.payment.application.gateways.OrderLookupGateway;
import br.com.fiap.numberone.billing.payment.application.results.OrderObservation;
import br.com.fiap.numberone.billing.payment.application.results.ProviderOrderSnapshot;
import br.com.fiap.numberone.billing.payment.application.results.ProviderPaymentSnapshot;
import br.com.fiap.numberone.billing.payment.domain.entities.Charge;
import br.com.fiap.numberone.billing.payment.domain.entities.PaymentAttempt;
import br.com.fiap.numberone.billing.payment.domain.enums.ChargeStatus;
import br.com.fiap.numberone.billing.payment.domain.enums.PaymentAttemptStatus;
import br.com.fiap.numberone.billing.payment.domain.references.ServiceOrderPaymentContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ObserveOrderWebhookUseCaseTest {
    private final ChargeGateway charges = mock(ChargeGateway.class);
    private final OrderLookupGateway orders = mock(OrderLookupGateway.class);
    private final ObserveOrderWebhookUseCase useCase = new ObserveOrderWebhookUseCase(charges, orders);
    private Charge charge;
    private PaymentAttempt attempt;

    @BeforeEach
    void setUp() {
        charge = Charge.create(new ServiceOrderPaymentContext(UUID.randomUUID(), "COMPLETED"), UUID.randomUUID(), BigDecimal.TEN, Currency.getInstance("BRL"));
        attempt = charge.startAttempt(UUID.randomUUID());
        charge.claimCheckoutCreation(attempt.getAttemptId());
        charge.associateProviderOrder(attempt.getAttemptId(), "ORD01ABC", "https://checkout.example.test/known");
        when(charges.findByProviderOrderId("ORD01ABC")).thenReturn(Optional.of(charge));
    }

    @ParameterizedTest
    @CsvSource({"created,created,PENDING", "processing,in_process,PENDING", "action_required,waiting_capture,PENDING",
            "processed,accredited,APPROVED", "failed,rejected_by_issuer,REJECTED", "canceled,canceled,CANCELED",
            "refunded,refunded,REFUNDED", "processed,partially_refunded,INDETERMINATE", "future_state,new_detail,INDETERMINATE"})
    void shouldRepresentOfficialSituationWithoutMutatingAnyLocalState(String status, String detail, OrderObservation.Situation expected) {
        when(orders.findById("ORD01ABC")).thenReturn(snapshot(status, detail, BigDecimal.TEN, BigDecimal.TEN, false));
        Instant before = charge.getUpdatedAt();
        Instant claim = attempt.getCheckoutCreationClaimedAt();
        for (int index = 0; index < 2; index++) assertThat(useCase.execute("ORD01ABC").situation()).isEqualTo(expected);
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.OPEN);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(attempt.getProviderPaymentId()).isNull();
        assertThat(charge.getUpdatedAt()).isEqualTo(before);
        assertThat(attempt.getCheckoutCreationClaimedAt()).isEqualTo(claim);
        verify(charges, times(2)).findByProviderOrderId("ORD01ABC");
        verifyNoMoreInteractions(charges);
    }

    @Test
    void shouldIgnoreUnknownLocalOrderWithoutProviderLookupOrCreatingCharge() {
        when(charges.findByProviderOrderId("unknown")).thenReturn(Optional.empty());
        assertThat(useCase.execute("unknown").situation()).isEqualTo(OrderObservation.Situation.UNKNOWN_ORDER);
        verifyNoInteractions(orders);
        verify(charges).findByProviderOrderId("unknown");
        verifyNoMoreInteractions(charges);
    }

    @Test
    void shouldUseCurrentOfficialStateInsteadOfPriorNotificationOrder() {
        when(orders.findById("ORD01ABC")).thenReturn(snapshot("processed", "accredited", BigDecimal.TEN, BigDecimal.TEN, false),
                snapshot("refunded", "refunded", BigDecimal.TEN, BigDecimal.TEN, false));
        assertThat(useCase.execute("ORD01ABC").situation()).isEqualTo(OrderObservation.Situation.APPROVED);
        assertThat(useCase.execute("ORD01ABC").situation()).isEqualTo(OrderObservation.Situation.REFUNDED);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"order", "reference", "currency", "amount"})
    void shouldRejectOfficialResponseDivergentFromLocalOperation(String field) {
        ProviderOrderSnapshot valid = snapshot("processed", "accredited", BigDecimal.TEN, BigDecimal.TEN, false);
        when(orders.findById("ORD01ABC")).thenReturn(new ProviderOrderSnapshot(field.equals("order") ? "other" : valid.providerOrderId(),
                valid.status(), valid.statusDetail(), field.equals("amount") ? BigDecimal.ONE : valid.totalAmount(), valid.totalPaidAmount(),
                field.equals("currency") ? "USD" : "BRL", field.equals("reference") ? UUID.randomUUID().toString() : valid.externalReference(), valid.payments(), false));
        assertThatThrownBy(() -> useCase.execute("ORD01ABC")).isInstanceOfSatisfying(OrderLookupException.class,
                failure -> assertThat(failure.getKind()).isEqualTo(OrderLookupException.Kind.INVALID_RESPONSE));
    }

    @Test
    void shouldNotTreatProcessedAloneOrMissingAmountsOrAdjustmentsAsApproved() {
        when(orders.findById("ORD01ABC")).thenReturn(snapshot("processed", "accredited", null, BigDecimal.TEN, false),
                snapshot("processed", "accredited", BigDecimal.ONE, BigDecimal.TEN, false),
                snapshot("processed", "accredited", BigDecimal.TEN, BigDecimal.ONE, false),
                snapshot("processed", "accredited", BigDecimal.TEN, BigDecimal.TEN, true));
        for (int index = 0; index < 4; index++) assertThat(useCase.execute("ORD01ABC").situation()).isEqualTo(OrderObservation.Situation.INDETERMINATE);
    }

    @Test
    void shouldNotApproveWithoutPaymentEvidence() {
        when(orders.findById("ORD01ABC")).thenReturn(new ProviderOrderSnapshot("ORD01ABC", "processed", "accredited", BigDecimal.TEN,
                BigDecimal.TEN, "BRL", attempt.getAttemptId().toString(), List.of(), false));
        assertThat(useCase.execute("ORD01ABC").situation()).isEqualTo(OrderObservation.Situation.INDETERMINATE);
    }

    @Test
    void shouldPreserveUnknownAndPropagateLookupFailureWithoutFinancialTransition() {
        charge.markAttemptUnknown(attempt.getAttemptId());
        when(orders.findById("ORD01ABC")).thenThrow(new OrderLookupException(OrderLookupException.Kind.TIMEOUT, null, null));
        assertThatThrownBy(() -> useCase.execute("ORD01ABC")).isInstanceOf(OrderLookupException.class);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        verify(charges).findByProviderOrderId("ORD01ABC");
        verifyNoMoreInteractions(charges);
    }

    @Test
    void shouldRejectResponseThatDoesNotContainPreviouslyKnownPaymentIdentity() {
        charge.recordPendingAttempt(attempt.getAttemptId(), "PAYknown");
        when(orders.findById("ORD01ABC")).thenReturn(snapshot("processed", "accredited", BigDecimal.TEN, BigDecimal.TEN, false));
        assertThatThrownBy(() -> useCase.execute("ORD01ABC")).isInstanceOf(OrderLookupException.class);
        assertThat(attempt.getProviderPaymentId()).isEqualTo("PAYknown");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldNeverApproveSplitPaymentsEvenWhenAggregateTotalsMatch(boolean individualAmountsDiverge) {
        ProviderOrderSnapshot split = new ProviderOrderSnapshot("ORD01ABC", "processed", "accredited", BigDecimal.TEN, BigDecimal.TEN,
                "BRL", attempt.getAttemptId().toString(), List.of(
                new ProviderPaymentSnapshot("PAYone", "processed", "accredited", new BigDecimal("4.00"), new BigDecimal(individualAmountsDiverge ? "6.00" : "4.00")),
                new ProviderPaymentSnapshot("PAYtwo", "processed", "accredited", new BigDecimal("6.00"), new BigDecimal(individualAmountsDiverge ? "4.00" : "6.00"))), false);
        when(orders.findById("ORD01ABC")).thenReturn(split);
        assertThat(useCase.execute("ORD01ABC").situation()).isEqualTo(OrderObservation.Situation.INDETERMINATE);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
    }

    private ProviderOrderSnapshot snapshot(String status, String detail, BigDecimal paid, BigDecimal paymentPaid, boolean adjusted) {
        return new ProviderOrderSnapshot("ORD01ABC", status, detail, BigDecimal.TEN, paid, "BRL", attempt.getAttemptId().toString(),
                List.of(new ProviderPaymentSnapshot("PAY01ABC", "processed", "accredited", BigDecimal.TEN, paymentPaid)), adjusted);
    }
}
