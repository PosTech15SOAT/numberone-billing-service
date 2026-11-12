package br.com.fiap.numberone.billing.payment.application.usecases;

import br.com.fiap.numberone.billing.payment.application.exceptions.ChargeNotFoundException;
import br.com.fiap.numberone.billing.payment.application.gateways.ChargeGateway;
import br.com.fiap.numberone.billing.payment.application.results.ChargeCheckoutResult;
import br.com.fiap.numberone.billing.payment.domain.entities.Charge;
import br.com.fiap.numberone.billing.payment.domain.entities.PaymentAttempt;
import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentDataException;
import br.com.fiap.numberone.billing.payment.domain.references.ServiceOrderPaymentContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GetChargeCheckoutUseCaseTest {
    private final ChargeGateway charges = mock(ChargeGateway.class);
    private final GetChargeCheckoutUseCase useCase = new GetChargeCheckoutUseCase(charges);

    @Test
    void shouldReadChargeWithoutCreatingAttemptOrCallingExternalServices() {
        Charge charge = charge();
        when(charges.findByServiceOrderId(charge.getServiceOrderId())).thenReturn(Optional.of(charge));
        ChargeCheckoutResult result = useCase.execute(charge.getServiceOrderId());
        assertThat(result.chargeId()).isEqualTo(charge.getChargeId());
        assertThat(result.amount()).isEqualByComparingTo("530.12");
        assertThat(result.checkout()).isNull();
        verify(charges).findByServiceOrderId(charge.getServiceOrderId());
        verifyNoMoreInteractions(charges);
    }

    @Test
    void shouldReturnLatestAttemptInsteadOfOldCheckout() {
        Charge charge = charge();
        PaymentAttempt old = charge.startAttempt(UUID.randomUUID());
        charge.associateProviderOrder(old.getAttemptId(), "old-order", "https://checkout.example.test/old");
        charge.confirmAttemptFailure(old.getAttemptId());
        PaymentAttempt current = charge.startAttempt(UUID.randomUUID());
        charge.markAttemptUnknown(current.getAttemptId());
        when(charges.findByServiceOrderId(charge.getServiceOrderId())).thenReturn(Optional.of(charge));
        ChargeCheckoutResult result = useCase.execute(charge.getServiceOrderId());
        assertThat(result.checkout().attemptId()).isEqualTo(current.getAttemptId());
        assertThat(result.checkout().checkoutUrl()).isNull();
        assertThat(result.checkout().status().name()).isEqualTo("UNKNOWN");
    }

    @Test
    void shouldReturnExistingUrlWithoutExposingKeyOrReservation() {
        Charge charge = charge();
        PaymentAttempt attempt = charge.startAttempt(UUID.randomUUID());
        charge.associateProviderOrder(attempt.getAttemptId(), "known-order", "https://checkout.example.test/existing");
        when(charges.findByServiceOrderId(charge.getServiceOrderId())).thenReturn(Optional.of(charge));
        assertThat(useCase.execute(charge.getServiceOrderId()).checkout().checkoutUrl()).endsWith("/existing");
        assertThat(useCase.execute(charge.getServiceOrderId()).toString()).doesNotContain("/existing", attempt.getIdempotencyKey().toString());
    }

    @Test
    void shouldRejectAbsentCharge() {
        when(charges.findByServiceOrderId(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.execute(UUID.randomUUID())).isInstanceOf(ChargeNotFoundException.class);
    }

    @Test
    void shouldRejectAbsentIdentityBeforeQuery() {
        assertThatThrownBy(() -> useCase.execute(null)).isInstanceOf(InvalidPaymentDataException.class);
        verifyNoInteractions(charges);
    }

    private Charge charge() {
        return Charge.create(new ServiceOrderPaymentContext(UUID.randomUUID(), "COMPLETED"), UUID.randomUUID(),
                new BigDecimal("530.12"), Currency.getInstance("BRL"));
    }
}
