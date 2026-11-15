package br.com.fiap.numberone.billing.payment.infrastructure.config;

import br.com.fiap.numberone.billing.payment.application.exceptions.OrderLookupException;
import br.com.fiap.numberone.billing.payment.application.gateways.ChargeGateway;
import br.com.fiap.numberone.billing.payment.application.gateways.OrderLookupGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MercadoPagoWebhookConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(MercadoPagoWebhookConfig.class, MercadoPagoCheckoutConfig.class)
            .withBean(ChargeGateway.class, () -> mock(ChargeGateway.class));

    @Test
    void shouldDisableIngressAndBlockLookupWithoutCreatingHttpClient() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(HttpClient.class);
            assertThat(context.getBean(MercadoPagoWebhookProperties.class).enabled()).isFalse();
            assertThatThrownBy(() -> context.getBean(OrderLookupGateway.class).findById("ORD01ABC"))
                    .isInstanceOfSatisfying(OrderLookupException.class, failure -> assertThat(failure.getKind()).isEqualTo(OrderLookupException.Kind.BLOCKED));
        });
    }

    @Test
    void shouldAllowOnlySignatureReceptionWhileKeepingLookupBlocked() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .withPropertyValues("billing.payment.mercado-pago-webhook.enabled=true", "billing.payment.mercado-pago-webhook.environment=TEST",
                "billing.payment.mercado-pago-webhook.secret=fictitious-secret").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(HttpClient.class);
            assertThat(context.getBean(MercadoPagoWebhookProperties.class).toString()).doesNotContain("fictitious-secret");
            assertThatThrownBy(() -> context.getBean(OrderLookupGateway.class).findById("ORD01ABC")).isInstanceOf(OrderLookupException.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "default", "test", "production", "eks", "local,production", "local,test"})
    void shouldBlockEnabledWebhookOutsideExclusiveLocalProfile(String profiles) {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles.isEmpty() ? new String[0] : profiles.split(",")))
                .withPropertyValues("billing.payment.mercado-pago-webhook.enabled=true", "billing.payment.mercado-pago-webhook.environment=TEST",
                        "billing.payment.mercado-pago-webhook.secret=fictitious-secret")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldRequireSecretAndExplicitTestEnvironment() {
        runner.withPropertyValues("billing.payment.mercado-pago-webhook.enabled=true").run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRODUCTION", "test", "SANDBOX"})
    void shouldRejectOtherEnvironmentsEvenWhenDisabled(String environment) {
        runner.withPropertyValues("billing.payment.mercado-pago-webhook.environment=" + environment).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldRejectAttemptToConfigureARealLookupEndpointOrCredential() {
        runner.withPropertyValues("billing.payment.mercado-pago-webhook.base-url=https://api.mercadopago.com")
                .run(context -> assertThat(context).hasFailed());
    }
}
