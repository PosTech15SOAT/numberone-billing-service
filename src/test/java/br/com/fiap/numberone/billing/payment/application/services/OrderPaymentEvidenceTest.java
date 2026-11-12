package br.com.fiap.numberone.billing.payment.application.services;

import br.com.fiap.numberone.billing.payment.application.exceptions.OrderLookupException;
import br.com.fiap.numberone.billing.payment.application.results.OrderObservation;
import br.com.fiap.numberone.billing.payment.application.results.ProviderOrderSnapshot;
import br.com.fiap.numberone.billing.payment.application.results.ProviderPaymentSnapshot;
import br.com.fiap.numberone.billing.payment.domain.entities.Charge;
import br.com.fiap.numberone.billing.payment.domain.entities.PaymentAttempt;
import br.com.fiap.numberone.billing.payment.domain.enums.ChargeStatus;
import br.com.fiap.numberone.billing.payment.domain.references.ServiceOrderPaymentContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static br.com.fiap.numberone.billing.payment.support.PaymentConfirmationFixtures.approved;
import static org.assertj.core.api.Assertions.*;

class OrderPaymentEvidenceTest {
    private Charge charge;
    private PaymentAttempt attempt;
    @BeforeEach void setUp() {
        charge = Charge.create(new ServiceOrderPaymentContext(UUID.randomUUID(), "COMPLETED"), UUID.randomUUID(), new BigDecimal("10.00"), Currency.getInstance("BRL"));
        attempt = charge.startAttempt(UUID.randomUUID());
        charge.associateProviderOrder(attempt.getAttemptId(), "ORDtest", "https://checkout.example.test/test");
    }

    @Test void shouldAcceptEquivalentBigDecimalValuesWithoutMutatingDomain() {
        OrderObservation observation = approved(charge, attempt.getAttemptId(), "PAYtest");
        ProviderOrderSnapshot original = observation.snapshot();
        ProviderOrderSnapshot snapshot = new ProviderOrderSnapshot(original.providerOrderId(), "processed", "accredited", BigDecimal.TEN, new BigDecimal("10.0"), "BRL", original.externalReference(),
                List.of(new ProviderPaymentSnapshot("PAYtest", "processed", "accredited", new BigDecimal("10.00"), BigDecimal.TEN)), false);
        assertThat(OrderPaymentEvidence.requireApproved(charge, withSnapshot(observation, snapshot))).isEqualTo("PAYtest");
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.OPEN);
    }

    @Test
    void shouldRejectMissingSnapshotAsInvalidEvidence() {
        assertThatThrownBy(() -> OrderPaymentEvidence.observe(charge, "ORDtest", null))
                .isInstanceOf(OrderLookupException.class);
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.OPEN);
    }

    @Test
    void shouldNotCombineMultiplePaymentsIntoFullAccreditation() {
        var snapshot = new ProviderOrderSnapshot("ORDtest", "processed", "accredited", BigDecimal.TEN,
                BigDecimal.TEN, "BRL", attempt.getAttemptId().toString(), List.of(
                new ProviderPaymentSnapshot("PAYfirst", "processed", "accredited", new BigDecimal("5.00"), new BigDecimal("5.00")),
                new ProviderPaymentSnapshot("PAYsecond", "processed", "accredited", new BigDecimal("5.00"), new BigDecimal("5.00"))), false);

        var observation = OrderPaymentEvidence.observe(charge, "ORDtest", snapshot);

        assertThat(observation.situation()).isEqualTo(OrderObservation.Situation.INDETERMINATE);
        assertThatThrownBy(() -> OrderPaymentEvidence.requireApproved(charge, observation))
                .isInstanceOf(OrderLookupException.class);
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.OPEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"order", "charge", "attempt", "serviceOrder", "reference", "currency", "amount", "paymentId", "knownPaymentId", "situation"})
    void shouldRejectDivergentIdentityEvenWhenObservationClaimsApproved(String field) {
        OrderObservation original = approved(charge, attempt.getAttemptId(), "PAYtest");
        ProviderOrderSnapshot source = original.snapshot();
        if (field.equals("knownPaymentId")) charge.recordPendingAttempt(attempt.getAttemptId(), "PAYknown");
        ProviderOrderSnapshot snapshot = new ProviderOrderSnapshot(field.equals("order") ? "ORDother" : source.providerOrderId(), source.status(), source.statusDetail(),
                field.equals("amount") ? BigDecimal.ONE : source.totalAmount(), source.totalPaidAmount(), field.equals("currency") ? "USD" : "BRL",
                field.equals("reference") ? UUID.randomUUID().toString() : source.externalReference(),
                List.of(new ProviderPaymentSnapshot(field.equals("paymentId") ? " " : "PAYtest", "processed", "accredited", BigDecimal.TEN, BigDecimal.TEN)), false);
        OrderObservation observation = new OrderObservation(original.providerOrderId(), field.equals("charge") ? UUID.randomUUID() : original.chargeId(),
                field.equals("attempt") ? UUID.randomUUID() : original.attemptId(), field.equals("serviceOrder") ? UUID.randomUUID() : original.serviceOrderId(),
                field.equals("situation") ? OrderObservation.Situation.PENDING : OrderObservation.Situation.APPROVED, snapshot);
        assertThatThrownBy(() -> OrderPaymentEvidence.requireApproved(charge, observation)).isInstanceOf(OrderLookupException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pendingOrder", "rejectedOrder", "orderDetail", "pendingPayment", "paymentDetail", "partialPayment", "excessPayment", "partialPaid", "excessPaid", "missingPaid", "partialTotalPaid", "excessTotalPaid", "missingTotalPaid", "refund", "empty", "scale", "zero", "negative"})
    void shouldNeverApproveInsufficientFinancialEvidence(String field) {
        OrderObservation original = approved(charge, attempt.getAttemptId(), "PAYtest");
        BigDecimal amount = switch (field) { case "partialPayment" -> BigDecimal.ONE; case "excessPayment" -> new BigDecimal("11"); case "scale" -> new BigDecimal("10.000"); case "zero" -> BigDecimal.ZERO; case "negative" -> BigDecimal.ONE.negate(); default -> BigDecimal.TEN; };
        BigDecimal paid = switch (field) { case "partialPaid" -> BigDecimal.ONE; case "excessPaid" -> new BigDecimal("11"); case "missingPaid" -> null; default -> BigDecimal.TEN; };
        BigDecimal totalPaid = switch (field) { case "partialTotalPaid" -> BigDecimal.ONE; case "excessTotalPaid" -> new BigDecimal("11"); case "missingTotalPaid" -> null; default -> BigDecimal.TEN; };
        ProviderOrderSnapshot snapshot = new ProviderOrderSnapshot("ORDtest", field.equals("pendingOrder") ? "processing" : field.equals("rejectedOrder") ? "failed" : "processed", field.equals("orderDetail") ? "partially_refunded" : "accredited", BigDecimal.TEN, totalPaid, "BRL", attempt.getAttemptId().toString(),
                field.equals("empty") ? List.of() : List.of(new ProviderPaymentSnapshot("PAYtest", field.equals("pendingPayment") ? "processing" : "processed", field.equals("paymentDetail") ? "in_process" : "accredited", amount, paid)), field.equals("refund"));
        assertThat(OrderPaymentEvidence.observe(charge, "ORDtest", snapshot).situation()).isNotEqualTo(OrderObservation.Situation.APPROVED);
        assertThatThrownBy(() -> OrderPaymentEvidence.requireApproved(charge, withSnapshot(original, snapshot))).isInstanceOf(OrderLookupException.class);
    }
    private OrderObservation withSnapshot(OrderObservation original, ProviderOrderSnapshot snapshot) {
        return new OrderObservation(original.providerOrderId(), original.chargeId(), original.attemptId(), original.serviceOrderId(), original.situation(), snapshot);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.00", "10.96", "11.25"})
    void shouldApprovePrincipalCreditedWithCoherentPaidTotalsWithoutClassifyingExcess(String paid) {
        ProviderOrderSnapshot snapshot = new ProviderOrderSnapshot("ORDtest", "processed", "accredited", BigDecimal.TEN,
                new BigDecimal(paid), "BRL", attempt.getAttemptId().toString(),
                List.of(new ProviderPaymentSnapshot("PAYtest", "processed", "accredited", BigDecimal.TEN, new BigDecimal(paid))), false);
        OrderObservation observed = OrderPaymentEvidence.observe(charge, "ORDtest", snapshot);
        assertThat(observed.situation()).isEqualTo(OrderObservation.Situation.APPROVED);
        assertThat(OrderPaymentEvidence.requireApproved(charge, observed)).isEqualTo("PAYtest");
        assertThat(charge.getAmount()).isEqualByComparingTo("10.00");
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.OPEN);
    }

    @ParameterizedTest
    @CsvSource({"9.99,10.96", "10.96,9.99", "10.96,11.00", "11.00,10.96"})
    void shouldKeepInsufficientOrInconsistentPaidTotalsIndeterminate(String paid, String totalPaid) {
        ProviderOrderSnapshot snapshot = new ProviderOrderSnapshot("ORDtest", "processed", "accredited", BigDecimal.TEN,
                new BigDecimal(totalPaid), "BRL", attempt.getAttemptId().toString(),
                List.of(new ProviderPaymentSnapshot("PAYtest", "processed", "accredited", BigDecimal.TEN, new BigDecimal(paid))), false);
        assertThat(OrderPaymentEvidence.observe(charge, "ORDtest", snapshot).situation()).isEqualTo(OrderObservation.Situation.INDETERMINATE);
    }
}
