package br.com.fiap.numberone.billing.payment.domain.entities;

import br.com.fiap.numberone.billing.payment.domain.enums.PaymentAttemptStatus;
import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentDataException;
import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentStateException;
import br.com.fiap.numberone.billing.payment.domain.references.ServiceOrderPaymentContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckoutReservationTest {
    @Test
    void shouldClaimOnceWithoutRenewingOrChangingIdentity() {
        Charge charge = charge();
        UUID key = UUID.randomUUID();
        PaymentAttempt attempt = charge.startAttempt(key);
        assertThat(charge.claimCheckoutCreation(attempt.getAttemptId())).isTrue();
        Instant claim = attempt.getCheckoutCreationClaimedAt();
        Instant update = charge.getUpdatedAt();

        assertThat(charge.claimCheckoutCreation(attempt.getAttemptId())).isFalse();
        assertThat(charge.startAttempt(key)).isSameAs(attempt);
        assertThat(attempt.getCheckoutCreationClaimedAt()).isEqualTo(claim);
        assertThat(charge.getUpdatedAt()).isEqualTo(update);
        assertThat(attempt.getIdempotencyKey()).isEqualTo(key);
    }

    @Test
    void shouldPreserveClaimAcrossUnknownAndRestorationAndSuccessfulAssociation() {
        Charge charge = charge();
        PaymentAttempt attempt = charge.startAttempt(UUID.randomUUID());
        charge.claimCheckoutCreation(attempt.getAttemptId());
        Instant claim = attempt.getCheckoutCreationClaimedAt();
        charge.markAttemptUnknown(attempt.getAttemptId());
        Charge restored = Charge.restore(charge.getChargeId(), charge.getServiceOrderId(), charge.getBudgetId(),
                charge.getAmount(), charge.getCurrency(), charge.getStatus(), charge.getCreatedAt(),
                charge.getUpdatedAt(), charge.getAttempts());
        assertThat(restored.claimCheckoutCreation(attempt.getAttemptId())).isFalse();
        restored.associateProviderOrder(attempt.getAttemptId(), "order-test", "https://checkout.example.test/pay");
        assertThat(restored.getAttempts().getFirst().getCheckoutCreationClaimedAt()).isEqualTo(claim);
        assertThat(restored.getAttempts().getFirst().getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentAttemptStatus.class, names = {"UNKNOWN", "FAILED", "EXPIRED", "PAID"})
    void shouldRejectFirstClaimOnNonPendingAttempt(PaymentAttemptStatus status) {
        Instant now = Instant.now();
        PaymentAttempt attempt = PaymentAttempt.restore(UUID.randomUUID(), UUID.randomUUID(), null,
                UUID.randomUUID(), status, status == PaymentAttemptStatus.PAID ? "payment" : null, now, now);
        assertThatThrownBy(() -> attempt.claimCheckoutCreation(now))
                .isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test
    void shouldRejectClaimWhenOrderAlreadyKnown() {
        Charge charge = charge();
        PaymentAttempt attempt = charge.startAttempt(UUID.randomUUID());
        charge.associateProviderOrder(attempt.getAttemptId(), "known", null);
        assertThatThrownBy(() -> charge.claimCheckoutCreation(attempt.getAttemptId()))
                .isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test
    void shouldRejectClaimOutsideAttemptLifetime() {
        Instant now = Instant.now();
        for (Instant invalid : new Instant[]{now.minusSeconds(1), now.plusSeconds(1)}) {
            assertThatThrownBy(() -> PaymentAttempt.restore(UUID.randomUUID(), UUID.randomUUID(), null,
                    UUID.randomUUID(), PaymentAttemptStatus.PENDING, null, null, null, invalid, now, now))
                    .isInstanceOf(InvalidPaymentDataException.class);
        }
    }

    private Charge charge() {
        return Charge.create(new ServiceOrderPaymentContext(UUID.randomUUID(), "COMPLETED"), UUID.randomUUID(),
                BigDecimal.TEN, Currency.getInstance("BRL"));
    }
}
