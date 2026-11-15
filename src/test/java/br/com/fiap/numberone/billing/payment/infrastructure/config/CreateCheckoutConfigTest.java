package br.com.fiap.numberone.billing.payment.infrastructure.config;

import br.com.fiap.numberone.billing.payment.application.gateways.ChargeGateway;
import br.com.fiap.numberone.billing.payment.application.gateways.CheckoutGateway;
import br.com.fiap.numberone.billing.payment.application.gateways.ServiceOrderPaymentGateway;
import br.com.fiap.numberone.billing.payment.infrastructure.rest.ServiceOrderPaymentRestGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CreateCheckoutConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CreateCheckoutConfig.class)
            .withBean(ChargeGateway.class, () -> mock(ChargeGateway.class))
            .withBean(CheckoutGateway.class, () -> mock(CheckoutGateway.class))
            .withPropertyValues("billing.integrations.os-base-url=http://os-service:8080",
                    "billing.integrations.inventory-base-url=http://inventory:8080",
                    "billing.integrations.connect-timeout=2s", "billing.integrations.read-timeout=5s");

    @Test void shouldUseRealServiceOrderClientWithoutTokenOrLocalProfile() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ServiceOrderPaymentGateway.class);
            assertThat(context.getBean(ServiceOrderPaymentGateway.class)).isInstanceOf(ServiceOrderPaymentRestGateway.class);
        });
    }

    @ParameterizedTest @ValueSource(strings = {"0ms", "-1s", "2m"})
    void shouldRejectInvalidTimeout(String timeout) {
        runner.withPropertyValues("billing.payment.service-order.read-timeout=" + timeout)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void shouldRejectObsoleteSettings() {
        runner.withPropertyValues("billing.payment.service-order.local-unauthenticated-enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }
}
