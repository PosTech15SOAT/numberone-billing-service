package br.com.fiap.numberone.billing.payment.infrastructure.config;

import br.com.fiap.numberone.billing.payment.application.exceptions.ServiceOrderPaymentUnavailableException;
import br.com.fiap.numberone.billing.payment.application.gateways.ChargeGateway;
import br.com.fiap.numberone.billing.payment.application.gateways.CheckoutGateway;
import br.com.fiap.numberone.billing.payment.application.gateways.ServiceOrderPaymentGateway;
import br.com.fiap.numberone.billing.payment.application.usecases.CreateCheckoutUseCase;
import br.com.fiap.numberone.billing.payment.domain.references.ServiceOrderPaymentContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.UUID;
import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class CreateCheckoutConfigTest {
    private final ChargeGateway charges = mock(ChargeGateway.class);
    private final CheckoutGateway checkout = mock(CheckoutGateway.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CreateCheckoutConfig.class)
            .withBean(ChargeGateway.class, () -> charges).withBean(CheckoutGateway.class, () -> checkout);

    @Test
    void shouldFailClosedWhileCt02aIsPending() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(CreateCheckoutUseCase.class).doesNotHaveBean(HttpClient.class);
            assertThatThrownBy(() -> context.getBean(CreateCheckoutUseCase.class)
                    .execute(UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOf(ServiceOrderPaymentUnavailableException.class).hasMessageContaining("Matheus");
            verify(charges).inspectCheckout(any(), any());
            verifyNoMoreInteractions(charges);
            verifyNoInteractions(checkout);
        });
    }

    @Test
    void shouldAllowExplicitLocalLoopbackModeWithoutCallingProviderOnStartup() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .withPropertyValues("billing.payment.service-order.local-unauthenticated-enabled=true",
                        "billing.payment.service-order.base-url=http://127.0.0.1:1").run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ServiceOrderPaymentGateway.class).hasSingleBean(HttpClient.class);
                    assertThat(context.getBean(ServiceOrderPaymentGateway.class)).isInstanceOf(
                            br.com.fiap.numberone.billing.payment.infrastructure.rest.ServiceOrderPaymentRestGateway.class);
                    verifyNoInteractions(charges, checkout);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "eks", "production", "local,production"})
    void shouldRejectUnauthenticatedModeOutsideExclusiveLocalProfile(String profiles) {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles.isEmpty() ? new String[0] : profiles.split(",")))
                .withPropertyValues("billing.payment.service-order.local-unauthenticated-enabled=true",
                        "billing.payment.service-order.base-url=http://127.0.0.1:1")
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://os-service:8080", "https://example.test", "http://127.0.0.2", "http://localhost.evil.test",
            "http://user:private@localhost", "http://localhost?token=private", "http://localhost#fragment", "http://localhost/base", "http://localhost:0", "http://localhost:70000"})
    void shouldRejectNonLoopbackOrUnsafeLocalDestination(String url) {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .withPropertyValues("billing.payment.service-order.local-unauthenticated-enabled=true",
                        "billing.payment.service-order.base-url=" + url).run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0ms", "-1s", "2m"})
    void shouldRejectInvalidTimeout(String timeout) {
        runner.withPropertyValues("billing.payment.service-order.read-timeout=" + timeout)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldRejectUnknownPropertiesInsteadOfPretendingAuthenticationIsConfigured() {
        runner.withPropertyValues("billing.payment.service-order.access-token=fictitious")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldBindExplicitEmptyDestinationWhileDisabled() {
        runner.withPropertyValues("billing.payment.service-order.base-url=").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(HttpClient.class);
            assertThatThrownBy(() -> context.getBean(ServiceOrderPaymentGateway.class).findById(UUID.randomUUID()))
                    .isInstanceOf(ServiceOrderPaymentUnavailableException.class);
        });
    }

    @Test
    void shouldRequireExplicitDestinationWhenLocalModeIsEnabled() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .withPropertyValues("billing.payment.service-order.local-unauthenticated-enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldUseExplicitTrustedGatewayWhenProvided() {
        UUID orderId = UUID.randomUUID();
        ServiceOrderPaymentGateway trusted = id -> new ServiceOrderPaymentContext(id, "COMPLETED");
        runner.withBean(ServiceOrderPaymentGateway.class, () -> trusted).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ServiceOrderPaymentGateway.class);
            assertThat(context.getBean(ServiceOrderPaymentGateway.class)).isSameAs(trusted);
            assertThat(trusted.findById(orderId).serviceOrderId()).isEqualTo(orderId);
        });
    }
}
