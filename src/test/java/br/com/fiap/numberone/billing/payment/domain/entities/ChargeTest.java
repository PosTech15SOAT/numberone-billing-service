package br.com.fiap.numberone.billing.payment.domain.entities;

import br.com.fiap.numberone.billing.payment.domain.enums.ChargeStatus;
import br.com.fiap.numberone.billing.payment.domain.enums.PaymentAttemptStatus;
import br.com.fiap.numberone.billing.payment.domain.enums.PaymentMethod;
import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentDataException;
import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentStateException;
import br.com.fiap.numberone.billing.payment.domain.references.ServiceOrderPaymentContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChargeTest {
    private static final Currency BRL = Currency.getInstance("BRL");

    @Test
    void shouldCreateOpenChargeWithExactApprovedAmount() {
        ServiceOrderPaymentContext context = completedOrder();
        UUID budgetId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("530.12");

        Charge charge = Charge.create(context, budgetId, amount, BRL);

        assertThat(charge.getChargeId()).isNotNull();
        assertThat(charge.getServiceOrderId()).isEqualTo(context.serviceOrderId());
        assertThat(charge.getBudgetId()).isEqualTo(budgetId);
        assertThat(charge.getAmount()).isEqualTo(amount);
        assertThat(charge.getCurrency()).isEqualTo(BRL);
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.OPEN);
        assertThat(charge.getUpdatedAt()).isEqualTo(charge.getCreatedAt());
        assertThat(charge.getAttempts()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.000", "-0.01", "-0.0100", "-530"})
    void shouldRejectNonPositiveAmounts(String amount) {
        assertThatThrownBy(() -> Charge.create(completedOrder(), UUID.randomUUID(), new BigDecimal(amount), BRL))
                .isInstanceOf(InvalidPaymentDataException.class);
    }

    @Test
    void shouldRequireCommercialReferencesAndCurrency() {
        assertThatThrownBy(() -> Charge.create(null, UUID.randomUUID(), BigDecimal.TEN, BRL))
                .isInstanceOf(InvalidPaymentDataException.class);
        assertThatThrownBy(() -> Charge.create(completedOrder(), null, BigDecimal.TEN, BRL))
                .isInstanceOf(InvalidPaymentDataException.class);
        assertThatThrownBy(() -> Charge.create(completedOrder(), UUID.randomUUID(), null, BRL))
                .isInstanceOf(InvalidPaymentDataException.class);
        assertThatThrownBy(() -> Charge.create(completedOrder(), UUID.randomUUID(), BigDecimal.TEN, null))
                .isInstanceOf(InvalidPaymentDataException.class);
        assertThatThrownBy(() -> new ServiceOrderPaymentContext(null, "COMPLETED"))
                .isInstanceOf(InvalidPaymentDataException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"IN_PROGRESS", "DELIVERED", "CANCELLED", "UNKNOWN", " "})
    void shouldRequireCompletedOrder(String orderStatus) {
        assertThatThrownBy(() -> Charge.create(new ServiceOrderPaymentContext(UUID.randomUUID(), orderStatus),
                UUID.randomUUID(), BigDecimal.TEN, BRL)).isInstanceOf(InvalidPaymentDataException.class);
    }

    @Test
    void shouldRequireAttemptKey() {
        Charge charge = charge();
        assertThatThrownBy(() -> charge.startAttempt(null))
                .isInstanceOf(InvalidPaymentDataException.class);
        assertThat(charge.getAttempts()).isEmpty();
    }

    @Test
    void shouldStartPendingAttemptWithoutAssumingProviderAcceptedIt() {
        Charge charge = charge();
        UUID key = UUID.randomUUID();
        PaymentAttempt attempt = charge.startAttempt(key);

        assertThat(attempt.getChargeId()).isEqualTo(charge.getChargeId());
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(attempt.getIdempotencyKey()).isEqualTo(key);
        assertThat(attempt.getProviderPaymentId()).isNull();
        assertThat(attempt.getPaymentMethod()).isNull();
        assertThat(attempt.getProviderOrderId()).isNull();
        assertThat(attempt.getCheckoutUrl()).isNull();
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.OPEN);
        assertThat(charge.getUpdatedAt()).isAfter(charge.getCreatedAt());
    }

    @ParameterizedTest
    @EnumSource(value = PaymentAttemptStatus.class, names = {"PENDING", "UNKNOWN", "PAID"})
    void shouldBlockAnotherAttemptWhileOperationIsUnresolvedOrPaid(PaymentAttemptStatus status) {
        Charge charge = chargeWithAttempt(status);
        assertThatThrownBy(() -> charge.startAttempt(UUID.randomUUID()))
                .isInstanceOf(InvalidPaymentStateException.class);
        assertThat(charge.getAttempts()).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(PaymentAttemptStatus.class)
    void shouldReplayOriginalAttemptInAnyState(PaymentAttemptStatus status) {
        Charge charge = chargeWithAttempt(status);
        PaymentAttempt original = charge.getAttempts().getFirst();
        Instant updatedAt = charge.getUpdatedAt();

        PaymentAttempt replay = charge.startAttempt(original.getIdempotencyKey());

        assertThat(replay).isSameAs(original);
        assertThat(charge.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(charge.getAttempts()).hasSize(1);
    }

    @Test
    void shouldReplayOriginalAttemptAfterMethodBecomesKnown() {
        Charge charge = charge();
        PaymentAttempt attempt = charge.startAttempt(UUID.randomUUID());
        charge.recordPaymentMethod(attempt.getAttemptId(), PaymentMethod.PIX);
        Instant updatedAt = charge.getUpdatedAt();
        assertThat(charge.startAttempt(attempt.getIdempotencyKey())).isSameAs(attempt);
        assertThat(attempt.getPaymentMethod()).isEqualTo(PaymentMethod.PIX);
        assertThat(charge.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentAttemptStatus.class, names = {"FAILED", "EXPIRED"})
    void shouldAllowExplicitNewAttemptOnlyAfterConfirmedTerminalFailure(PaymentAttemptStatus terminal) {
        Charge charge = chargeWithAttempt(terminal);
        PaymentAttempt next = charge.startAttempt(UUID.randomUUID());
        assertThat(next.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(charge.getAttempts()).hasSize(2);
    }

    @ParameterizedTest
    @CsvSource({"PENDING,UNKNOWN", "PENDING,PAID", "PENDING,FAILED", "PENDING,EXPIRED",
            "UNKNOWN,PENDING", "UNKNOWN,PAID", "UNKNOWN,FAILED", "UNKNOWN,EXPIRED"})
    void shouldApplyConfirmedTransitions(PaymentAttemptStatus initial, PaymentAttemptStatus next) {
        Charge charge = chargeWithAttempt(initial);
        PaymentAttempt attempt = charge.getAttempts().getFirst();
        Instant previousUpdate = charge.getUpdatedAt();

        transition(charge, attempt.getAttemptId(), next);

        assertThat(attempt.getStatus()).isEqualTo(next);
        assertThat(charge.getStatus()).isEqualTo(next == PaymentAttemptStatus.PAID ? ChargeStatus.PAID : ChargeStatus.OPEN);
        assertThat(charge.getUpdatedAt()).isAfter(previousUpdate).isEqualTo(attempt.getUpdatedAt());
    }

    @ParameterizedTest
    @CsvSource({"PAID,UNKNOWN", "PAID,PENDING", "PAID,FAILED", "PAID,EXPIRED",
            "FAILED,UNKNOWN", "FAILED,PENDING", "FAILED,PAID", "FAILED,EXPIRED",
            "EXPIRED,UNKNOWN", "EXPIRED,PENDING", "EXPIRED,PAID", "EXPIRED,FAILED"})
    void shouldRejectRegressionsOfTerminalResults(PaymentAttemptStatus initial, PaymentAttemptStatus next) {
        Charge charge = chargeWithAttempt(initial);
        PaymentAttempt attempt = charge.getAttempts().getFirst();
        Instant updatedAt = charge.getUpdatedAt();
        assertThatThrownBy(() -> transition(charge, attempt.getAttemptId(), next))
                .isInstanceOf(InvalidPaymentStateException.class);
        assertThat(attempt.getStatus()).isEqualTo(initial);
        assertThat(charge.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @ParameterizedTest
    @EnumSource(PaymentAttemptStatus.class)
    void shouldTreatRepeatedConfirmationAsNoOp(PaymentAttemptStatus status) {
        Charge charge = chargeWithAttempt(status);
        PaymentAttempt attempt = charge.getAttempts().getFirst();
        transition(charge, attempt.getAttemptId(), status);
        Instant updatedAt = charge.getUpdatedAt();
        transition(charge, attempt.getAttemptId(), status);
        assertThat(charge.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " invalid", "invalid ", "invalid\n"})
    void shouldRejectInvalidProviderIdWithoutChangingState(String providerId) {
        Charge charge = charge();
        PaymentAttempt attempt = charge.startAttempt(UUID.randomUUID());
        Instant updatedAt = charge.getUpdatedAt();
        assertThatThrownBy(() -> charge.confirmPayment(attempt.getAttemptId(), providerId))
                .isInstanceOf(InvalidPaymentDataException.class);
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.OPEN);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(charge.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void shouldKeepProviderReferenceImmutableAndRejectOversizedId() {
        Charge charge = charge();
        PaymentAttempt attempt = charge.startAttempt(UUID.randomUUID());
        assertThatThrownBy(() -> charge.recordPendingAttempt(attempt.getAttemptId(), "a".repeat(256)))
                .isInstanceOf(InvalidPaymentDataException.class);
        charge.recordPendingAttempt(attempt.getAttemptId(), "provider-1");
        assertThatThrownBy(() -> charge.confirmPayment(attempt.getAttemptId(), "provider-2"))
                .isInstanceOf(InvalidPaymentStateException.class);
        assertThat(attempt.getProviderPaymentId()).isEqualTo("provider-1");
    }

    @Test
    void shouldRejectSecondSettlementAndPreserveCommercialData() {
        Charge charge = chargeWithAttempt(PaymentAttemptStatus.FAILED);
        UUID failedId = charge.getAttempts().getFirst().getAttemptId();
        UUID serviceOrderId = charge.getServiceOrderId();
        UUID budgetId = charge.getBudgetId();
        BigDecimal amount = charge.getAmount();
        PaymentAttempt next = charge.startAttempt(UUID.randomUUID());
        charge.confirmPayment(next.getAttemptId(), "payment-2");

        assertThatThrownBy(() -> charge.confirmPayment(failedId, "payment-1"))
                .isInstanceOf(InvalidPaymentStateException.class);
        charge.confirmPayment(next.getAttemptId(), "payment-2");
        assertThat(charge.getAttempts().stream().filter(attempt -> attempt.getStatus() == PaymentAttemptStatus.PAID)).hasSize(1);
        assertThat(charge.getAmount()).isEqualTo(amount);
        assertThat(charge.getBudgetId()).isEqualTo(budgetId);
        assertThat(charge.getServiceOrderId()).isEqualTo(serviceOrderId);
        assertThat(charge.getCurrency()).isEqualTo(BRL);
    }

    @Test
    void shouldNotReuseAProviderOperationInAnotherAttempt() {
        Charge charge = chargeWithAttempt(PaymentAttemptStatus.PENDING);
        PaymentAttempt first = charge.getAttempts().getFirst();
        charge.confirmAttemptFailure(first.getAttemptId());
        PaymentAttempt next = charge.startAttempt(UUID.randomUUID());
        assertThatThrownBy(() -> charge.recordPendingAttempt(next.getAttemptId(), first.getProviderPaymentId()))
                .isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test
    void shouldRejectAnAttemptOutsideTheAggregate() {
        Charge charge = charge();
        assertThatThrownBy(() -> charge.confirmPayment(UUID.randomUUID(), "payment"))
                .isInstanceOf(InvalidPaymentDataException.class);
    }

    @Test
    void shouldProtectHistoryAndAvoidSharingMutableAttemptsOnRestore() {
        Charge original = chargeWithAttempt(PaymentAttemptStatus.PENDING);
        Charge restored = restore(original, original.getStatus(), original.getAttempts());
        assertThatThrownBy(() -> restored.getAttempts().clear()).isInstanceOf(UnsupportedOperationException.class);
        original.confirmPayment(original.getAttempts().getFirst().getAttemptId(), "provider-1");
        assertThat(restored.getAttempts().getFirst().getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(restored.getStatus()).isEqualTo(ChargeStatus.OPEN);
    }

    @Test
    void shouldRejectInconsistentRestoredAggregate() {
        Charge first = chargeWithAttempt(PaymentAttemptStatus.PENDING);
        Charge other = chargeWithAttempt(PaymentAttemptStatus.PENDING);
        assertThatThrownBy(() -> restore(first, ChargeStatus.PAID, first.getAttempts()))
                .isInstanceOf(InvalidPaymentDataException.class);
        assertThatThrownBy(() -> restore(first, ChargeStatus.OPEN, other.getAttempts()))
                .isInstanceOf(InvalidPaymentDataException.class);
        assertThatThrownBy(() -> restore(first, ChargeStatus.OPEN,
                List.of(first.getAttempts().getFirst(), first.getAttempts().getFirst())))
                .isInstanceOf(InvalidPaymentDataException.class);
        Charge paid = chargeWithAttempt(PaymentAttemptStatus.PAID);
        assertThatThrownBy(() -> restore(paid, ChargeStatus.OPEN, paid.getAttempts()))
                .isInstanceOf(InvalidPaymentDataException.class);
    }

    @Test
    void shouldRejectInvalidRestoredAttempt() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> PaymentAttempt.restore(UUID.randomUUID(), UUID.randomUUID(), PaymentMethod.PIX,
                UUID.randomUUID(), PaymentAttemptStatus.PAID, null, now, now))
                .isInstanceOf(InvalidPaymentDataException.class);
        assertThatThrownBy(() -> PaymentAttempt.restore(UUID.randomUUID(), UUID.randomUUID(), PaymentMethod.PIX,
                UUID.randomUUID(), PaymentAttemptStatus.PENDING, null, now, now.minusSeconds(1)))
                .isInstanceOf(InvalidPaymentDataException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "1.2", "1.23", "10.0", "10.00", "10.000", "1.23000", "0.0100"})
    void shouldAcceptPositiveBrlAmountsWithoutChangingPrecision(String decimal) {
        BigDecimal amount = new BigDecimal(decimal);
        assertThat(Charge.create(completedOrder(), UUID.randomUUID(), amount, BRL).getAmount()).isEqualTo(amount);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.001", "530.123", "1.2301", "1.0001"})
    void shouldRejectRealFractionsOfCentWithoutRounding(String decimal) {
        assertThatThrownBy(() -> Charge.create(completedOrder(), UUID.randomUUID(), new BigDecimal(decimal), BRL))
                .isInstanceOf(InvalidPaymentDataException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USD", "EUR"})
    void shouldRejectNonBrlCurrency(String currency) {
        assertThatThrownBy(() -> Charge.create(completedOrder(), UUID.randomUUID(), BigDecimal.TEN, Currency.getInstance(currency)))
                .isInstanceOf(InvalidPaymentDataException.class);
    }

    @ParameterizedTest
    @EnumSource(PaymentMethod.class)
    void shouldRestoreLegacyMethodsAndReplayTheirOriginalKey(PaymentMethod method) {
        Charge source = charge();
        PaymentAttempt created = source.startAttempt(UUID.randomUUID());
        PaymentAttempt legacy = PaymentAttempt.restore(created.getAttemptId(), created.getChargeId(), method,
                created.getIdempotencyKey(), created.getStatus(), null, created.getCreatedAt(), created.getUpdatedAt());
        Charge restored = restore(source, ChargeStatus.OPEN, List.of(legacy));

        assertThat(restored.startAttempt(created.getIdempotencyKey()).getPaymentMethod()).isEqualTo(method);
        assertThat(restored.getUpdatedAt()).isEqualTo(source.getUpdatedAt());
    }

    @ParameterizedTest
    @CsvSource({"BRL,10.1234", "USD,10.12"})
    void shouldPreserveLegacyMoneyAndReplayButBlockNewOperations(String currency, String decimal) {
        Charge source = chargeWithAttempt(PaymentAttemptStatus.FAILED);
        Charge legacy = Charge.restore(source.getChargeId(), source.getServiceOrderId(), source.getBudgetId(),
                new BigDecimal(decimal), Currency.getInstance(currency), source.getStatus(), source.getCreatedAt(),
                source.getUpdatedAt(), source.getAttempts());
        UUID originalKey = legacy.getAttempts().getFirst().getIdempotencyKey();

        assertThat(legacy.startAttempt(originalKey).getIdempotencyKey()).isEqualTo(originalKey);
        assertThatThrownBy(() -> legacy.startAttempt(UUID.randomUUID())).isInstanceOf(InvalidPaymentDataException.class);
        assertThat(legacy.getAmount()).isEqualTo(new BigDecimal(decimal));
        assertThat(legacy.getCurrency().getCurrencyCode()).isEqualTo(currency);
        assertThat(legacy.getAttempts()).hasSize(1);
    }

    @Test
    void shouldAssociateOrderWithoutAssumingPaymentOrMethod() {
        Charge charge = charge();
        UUID key = UUID.randomUUID();
        PaymentAttempt attempt = charge.startAttempt(key);
        Instant before = charge.getUpdatedAt();

        charge.associateProviderOrder(attempt.getAttemptId(), "order-1", checkoutUrl());

        assertThat(attempt.getProviderOrderId()).isEqualTo("order-1");
        assertThat(attempt.getCheckoutUrl()).isEqualTo(checkoutUrl());
        assertThat(attempt.getProviderPaymentId()).isNull();
        assertThat(attempt.getPaymentMethod()).isNull();
        assertThat(attempt.getIdempotencyKey()).isEqualTo(key);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.OPEN);
        assertThat(charge.getUpdatedAt()).isAfter(before).isEqualTo(attempt.getUpdatedAt());

        Charge restored = restore(charge, ChargeStatus.OPEN, charge.getAttempts());
        assertThat(restored.getAttempts().getFirst().getProviderOrderId()).isEqualTo("order-1");
        assertThat(restored.getAttempts().getFirst().getCheckoutUrl()).isEqualTo(checkoutUrl());
    }

    @Test
    void shouldReplayOrderAssociationWithoutChangingVersionTimestampsOrClearingUrl() {
        Charge charge = charge();
        PaymentAttempt attempt = charge.startAttempt(UUID.randomUUID());
        charge.associateProviderOrder(attempt.getAttemptId(), "order-1", checkoutUrl());
        Instant confirmedAt = charge.getUpdatedAt();

        charge.associateProviderOrder(attempt.getAttemptId(), "order-1", checkoutUrl());
        charge.associateProviderOrder(attempt.getAttemptId(), "order-1", null);

        assertThat(charge.getUpdatedAt()).isEqualTo(confirmedAt);
        assertThat(attempt.getCheckoutUrl()).isEqualTo(checkoutUrl());
    }

    @Test
    void shouldAssociateKnownOrderBeforeUrlIsRecovered() {
        Charge charge = charge();
        PaymentAttempt attempt = charge.startAttempt(UUID.randomUUID());
        charge.associateProviderOrder(attempt.getAttemptId(), "order-1", null);
        assertThat(attempt.getCheckoutUrl()).isNull();
        charge.associateProviderOrder(attempt.getAttemptId(), "order-1", checkoutUrl());
        assertThat(attempt.getCheckoutUrl()).isEqualTo(checkoutUrl());
    }

    @Test
    void shouldRejectDivergentOrderOrUrlWithoutPartialChanges() {
        Charge charge = charge();
        PaymentAttempt attempt = charge.startAttempt(UUID.randomUUID());
        charge.associateProviderOrder(attempt.getAttemptId(), "order-1", checkoutUrl());
        Instant updatedAt = charge.getUpdatedAt();
        assertThatThrownBy(() -> charge.associateProviderOrder(attempt.getAttemptId(), "order-2", checkoutUrl()))
                .isInstanceOf(InvalidPaymentStateException.class);
        assertThatThrownBy(() -> charge.associateProviderOrder(attempt.getAttemptId(), "order-1", checkoutUrl() + "&different=true"))
                .isInstanceOf(InvalidPaymentStateException.class);
        assertThat(charge.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(attempt.getProviderOrderId()).isEqualTo("order-1");
        assertThat(attempt.getCheckoutUrl()).isEqualTo(checkoutUrl());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " order", "order ", "order\n"})
    void shouldRejectInvalidOrderId(String orderId) {
        Charge charge = charge();
        PaymentAttempt attempt = charge.startAttempt(UUID.randomUUID());
        assertThatThrownBy(() -> charge.associateProviderOrder(attempt.getAttemptId(), orderId, checkoutUrl()))
                .isInstanceOf(InvalidPaymentDataException.class);
        assertThat(attempt.getProviderOrderId()).isNull();
        assertThat(attempt.getCheckoutUrl()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "http://checkout.test/order", "/checkout", "https:///checkout",
            "https://user:password@checkout.test/order", "https://checkout.test/order#fragment",
            "https://checkout.test:99999/order", "https://checkout.test/bad path", "https://checkout.test/order\n"})
    void shouldRejectUnsafeOrMalformedCheckoutUrlWithoutBindingOrder(String url) {
        Charge charge = charge();
        PaymentAttempt attempt = charge.startAttempt(UUID.randomUUID());
        assertThatThrownBy(() -> charge.associateProviderOrder(attempt.getAttemptId(), "order-1", url))
                .isInstanceOf(InvalidPaymentDataException.class);
        assertThat(attempt.getProviderOrderId()).isNull();
        assertThat(attempt.getCheckoutUrl()).isNull();
    }

    @Test
    void shouldEnforceOrderAndUrlSizeBoundaries() {
        Charge charge = charge();
        PaymentAttempt attempt = charge.startAttempt(UUID.randomUUID());
        String prefix = "https://checkout.test/";
        String maximumUrl = prefix + "a".repeat(2048 - prefix.length());
        assertThatThrownBy(() -> charge.associateProviderOrder(attempt.getAttemptId(), "a".repeat(256), maximumUrl))
                .isInstanceOf(InvalidPaymentDataException.class);
        assertThatThrownBy(() -> charge.associateProviderOrder(attempt.getAttemptId(), "order-1", maximumUrl + "a"))
                .isInstanceOf(InvalidPaymentDataException.class);
        charge.associateProviderOrder(attempt.getAttemptId(), "a".repeat(255), maximumUrl);
        assertThat(attempt.getCheckoutUrl()).hasSize(2048);
    }

    @Test
    void shouldRecoverUnknownCreationUsingTheSameAttemptAndKey() {
        Charge charge = charge();
        UUID key = UUID.randomUUID();
        PaymentAttempt attempt = charge.startAttempt(key);
        charge.markAttemptUnknown(attempt.getAttemptId());
        charge.associateProviderOrder(attempt.getAttemptId(), "order-1", checkoutUrl());
        assertThat(charge.startAttempt(key)).isSameAs(attempt);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(attempt.getProviderPaymentId()).isNull();
        assertThatThrownBy(() -> charge.startAttempt(UUID.randomUUID())).isInstanceOf(InvalidPaymentStateException.class);

        charge.markAttemptUnknown(attempt.getAttemptId());
        Instant unknownAt = charge.getUpdatedAt();
        charge.associateProviderOrder(attempt.getAttemptId(), "order-1", checkoutUrl());
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(charge.getUpdatedAt()).isEqualTo(unknownAt);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentAttemptStatus.class, names = {"PAID", "FAILED", "EXPIRED"})
    void shouldAllowLateOrderMetadataWithoutRegressingTerminalFinancialState(PaymentAttemptStatus status) {
        Charge charge = chargeWithAttempt(status);
        PaymentAttempt attempt = charge.getAttempts().getFirst();
        ChargeStatus chargeStatus = charge.getStatus();
        charge.associateProviderOrder(attempt.getAttemptId(), "order-1", checkoutUrl());
        assertThat(attempt.getStatus()).isEqualTo(status);
        assertThat(charge.getStatus()).isEqualTo(chargeStatus);
    }

    @ParameterizedTest
    @EnumSource(PaymentMethod.class)
    void shouldRecordChosenMethodOnlyOnceAndWithoutAffectingRequestIdempotency(PaymentMethod method) {
        Charge charge = charge();
        PaymentAttempt attempt = charge.startAttempt(UUID.randomUUID());
        charge.recordPaymentMethod(attempt.getAttemptId(), method);
        Instant recordedAt = charge.getUpdatedAt();
        charge.recordPaymentMethod(attempt.getAttemptId(), method);
        assertThat(charge.getUpdatedAt()).isEqualTo(recordedAt);
        assertThatThrownBy(() -> charge.recordPaymentMethod(attempt.getAttemptId(), method == PaymentMethod.PIX ? PaymentMethod.CARD : PaymentMethod.PIX))
                .isInstanceOf(InvalidPaymentStateException.class);
        assertThat(attempt.getPaymentMethod()).isEqualTo(method);
        assertThat(attempt.getProviderPaymentId()).isNull();
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.OPEN);
    }

    @Test
    void shouldRejectMetadataForAnotherChargeAndDuplicateOrderWithinHistory() {
        Charge charge = charge();
        PaymentAttempt first = charge.startAttempt(UUID.randomUUID());
        assertThatThrownBy(() -> charge.associateProviderOrder(UUID.randomUUID(), "order-1", checkoutUrl()))
                .isInstanceOf(InvalidPaymentDataException.class);
        assertThatThrownBy(() -> charge.recordPaymentMethod(UUID.randomUUID(), PaymentMethod.PIX))
                .isInstanceOf(InvalidPaymentDataException.class);
        assertThatThrownBy(() -> charge.recordPaymentMethod(first.getAttemptId(), null))
                .isInstanceOf(InvalidPaymentDataException.class);
        charge.associateProviderOrder(first.getAttemptId(), "order-1", checkoutUrl());
        charge.confirmAttemptFailure(first.getAttemptId());
        PaymentAttempt second = charge.startAttempt(UUID.randomUUID());
        assertThatThrownBy(() -> charge.associateProviderOrder(second.getAttemptId(), "order-1", checkoutUrl()))
                .isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test
    void shouldRejectRestoredUrlWithoutOrderIdentity() {
        Charge source = charge();
        PaymentAttempt attempt = source.startAttempt(UUID.randomUUID());
        assertThatThrownBy(() -> PaymentAttempt.restore(attempt.getAttemptId(), source.getChargeId(), null,
                attempt.getIdempotencyKey(), PaymentAttemptStatus.PENDING, null, null, checkoutUrl(),
                attempt.getCreatedAt(), attempt.getUpdatedAt())).isInstanceOf(InvalidPaymentDataException.class);
    }

    private static String checkoutUrl() {
        return "https://checkout.example.test/pay?order_id=order-1";
    }

    private static Charge restore(Charge source, ChargeStatus status, List<PaymentAttempt> attempts) {
        return Charge.restore(source.getChargeId(), source.getServiceOrderId(), source.getBudgetId(), source.getAmount(),
                source.getCurrency(), status, source.getCreatedAt(), source.getUpdatedAt(), attempts);
    }

    private static Charge chargeWithAttempt(PaymentAttemptStatus status) {
        Charge charge = charge();
        PaymentAttempt attempt = charge.startAttempt(UUID.randomUUID());
        if (status != PaymentAttemptStatus.UNKNOWN) {
            charge.recordPendingAttempt(attempt.getAttemptId(), "provider-1");
        }
        transition(charge, attempt.getAttemptId(), status);
        return charge;
    }

    private static void transition(Charge charge, UUID attemptId, PaymentAttemptStatus status) {
        switch (status) {
            case PENDING -> charge.recordPendingAttempt(attemptId, "provider-1");
            case UNKNOWN -> charge.markAttemptUnknown(attemptId);
            case PAID -> charge.confirmPayment(attemptId, "provider-1");
            case FAILED -> charge.confirmAttemptFailure(attemptId);
            case EXPIRED -> charge.confirmAttemptExpiration(attemptId);
        }
    }

    private static ServiceOrderPaymentContext completedOrder() {
        return new ServiceOrderPaymentContext(UUID.randomUUID(), "COMPLETED");
    }

    private static Charge charge() {
        return Charge.create(completedOrder(), UUID.randomUUID(), new BigDecimal("530.12"), BRL);
    }
}
