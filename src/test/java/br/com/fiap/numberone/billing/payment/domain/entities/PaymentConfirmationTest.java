package br.com.fiap.numberone.billing.payment.domain.entities;

import br.com.fiap.numberone.billing.payment.domain.enums.ChargeStatus;
import br.com.fiap.numberone.billing.payment.domain.enums.PaymentAttemptStatus;
import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentDataException;
import br.com.fiap.numberone.billing.payment.domain.references.ServiceOrderPaymentContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class PaymentConfirmationTest {

    @ParameterizedTest
    @ValueSource(strings = {"10.0", "10.00", "10.000", "1.23000"})
    void shouldConfirmExactCentAmountWithoutNormalizingItsOriginalScale(String value) {
        BigDecimal amount = new BigDecimal(value);
        Charge charge = Charge.create(new ServiceOrderPaymentContext(UUID.randomUUID(), "COMPLETED"), UUID.randomUUID(),
                amount, Currency.getInstance("BRL"));
        PaymentAttempt attempt = charge.startAttempt(UUID.randomUUID());
        charge.confirmPayment(attempt.getAttemptId(), "PAYtest");
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.PAID);
        assertThat(charge.getAmount()).isEqualTo(amount);
    }
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldConfirmPendingOrUnknownExactlyOnceWithoutReplacingIdentity(boolean unknown) {
        Charge charge = Charge.create(new ServiceOrderPaymentContext(UUID.randomUUID(), "COMPLETED"), UUID.randomUUID(), new BigDecimal("10.00"), Currency.getInstance("BRL"));
        PaymentAttempt attempt = charge.startAttempt(UUID.randomUUID());
        charge.claimCheckoutCreation(attempt.getAttemptId());
        if (unknown) charge.markAttemptUnknown(attempt.getAttemptId());
        charge.confirmPayment(attempt.getAttemptId(), "PAYtest");
        java.time.Instant confirmedAt = charge.getUpdatedAt();
        java.time.Instant claimedAt = attempt.getCheckoutCreationClaimedAt();
        charge.confirmPayment(attempt.getAttemptId(), "PAYtest");
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.PAID);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PAID);
        assertThat(attempt.getProviderPaymentId()).isEqualTo("PAYtest");
        assertThat(charge.getUpdatedAt()).isEqualTo(confirmedAt);
        assertThat(attempt.getCheckoutCreationClaimedAt()).isEqualTo(claimedAt);
    }

    @ParameterizedTest
    @CsvSource({"USD,10.00", "BRL,10.001"})
    void shouldKeepIncompatibleLegacyMoneyUntouchedAndRefuseSettlement(String currency, String amount) {
        Charge original = Charge.create(new ServiceOrderPaymentContext(UUID.randomUUID(), "COMPLETED"), UUID.randomUUID(), BigDecimal.TEN, Currency.getInstance("BRL"));
        PaymentAttempt attempt = original.startAttempt(UUID.randomUUID());
        Charge legacy = Charge.restore(original.getChargeId(), original.getServiceOrderId(), original.getBudgetId(), new BigDecimal(amount), Currency.getInstance(currency),
                original.getStatus(), original.getCreatedAt(), original.getUpdatedAt(), original.getAttempts());
        assertThatThrownBy(() -> legacy.confirmPayment(attempt.getAttemptId(), "PAYtest")).isInstanceOf(InvalidPaymentDataException.class);
        assertThat(legacy.getStatus()).isEqualTo(ChargeStatus.OPEN);
        assertThat(legacy.getAmount()).isEqualTo(new BigDecimal(amount));
        assertThat(legacy.getAttempts().getFirst().getProviderPaymentId()).isNull();
    }
}
