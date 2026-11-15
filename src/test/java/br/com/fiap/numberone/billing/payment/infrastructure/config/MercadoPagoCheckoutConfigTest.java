package br.com.fiap.numberone.billing.payment.infrastructure.config;

import br.com.fiap.numberone.billing.payment.application.exceptions.CheckoutCreationException;
import br.com.fiap.numberone.billing.payment.application.gateways.CheckoutGateway;
import br.com.fiap.numberone.billing.payment.application.gateways.OrderLookupGateway;
import br.com.fiap.numberone.billing.payment.application.exceptions.OrderLookupException;
import br.com.fiap.numberone.billing.payment.infrastructure.rest.MercadoPagoOrderLookupGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.net.http.HttpClient;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class MercadoPagoCheckoutConfigTest {
    private static final String TOKEN = "APP_USR-fabricated-secret-not-a-credential";
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(MercadoPagoCheckoutConfig.class);

    @Test void shouldDisableExternalCallsByDefaultWithoutCreatingHttpClient() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(CheckoutGateway.class).doesNotHaveBean(HttpClient.class);
            assertThatThrownBy(() -> context.getBean(OrderLookupGateway.class).findById("ORDtest"))
                    .isInstanceOfSatisfying(OrderLookupException.class, failure -> assertThat(failure.getKind()).isEqualTo(OrderLookupException.Kind.BLOCKED));
            assertThatThrownBy(() -> context.getBean(CheckoutGateway.class).validateSeller())
                    .isInstanceOfSatisfying(CheckoutCreationException.class, failure -> assertThat(failure.getKind()).isEqualTo(CheckoutCreationException.Kind.DISABLED));
        });
    }

    @Test void shouldPrepareLocalTestClientWithoutAnyStartupRequestOrTokenLeak(CapturedOutput output) {
        enabled("local").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(HttpClient.class).hasSingleBean(CheckoutGateway.class);
            assertThat(context.getBean(OrderLookupGateway.class)).isInstanceOf(MercadoPagoOrderLookupGateway.class);
            assertThat(context.getBean(HttpClient.class).followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
            assertThat(context.getBean(MercadoPagoProperties.class).toString()).doesNotContain(TOKEN, "1234567890123");
        });
        assertThat(output.getAll()).doesNotContain(TOKEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "default", "test", "production", "eks", "local,production", "local,test"})
    void shouldBlockAnyProfileOtherThanExclusivelyLocal(String profiles, CapturedOutput output) {
        enabled(profiles).run(context -> assertThat(context).hasFailed());
        assertThat(output.getAll()).doesNotContain(TOKEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRODUCTION", "SANDBOX", "test", ""})
    void shouldRequireExplicitTestEvenInLocalProfile(String environment) {
        enabled("local").withPropertyValues("billing.payment.mercado-pago.environment=" + environment).run(context -> assertThat(context).hasFailed());
    }

    @Test void shouldRejectProductionConfigurationEvenWhenDisabled() {
        runner.withPropertyValues("billing.payment.mercado-pago.environment=PRODUCTION").run(context -> assertThat(context).hasFailed());
    }

    @Test void shouldRequireCredentialFromDedicatedProcessEnvironment(CapturedOutput output) {
        runner.withInitializer(context -> {
            context.getEnvironment().setActiveProfiles("local");
            context.getEnvironment().getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of()));
        }).withPropertyValues("billing.payment.mercado-pago.enabled=true", "billing.payment.mercado-pago.environment=TEST",
                "billing.payment.mercado-pago.expected-test-seller-id=1234567890123", "billing.payment.mercado-pago.access-token=" + TOKEN)
                .run(context -> assertThat(context).hasFailed());
        assertThat(output.getAll()).doesNotContain(TOKEN);
    }

    @Test void shouldRejectDifferentCredentialOverridingEnvironment(CapturedOutput output) {
        enabled("local").withPropertyValues("billing.payment.mercado-pago.access-token=APP_USR-another-private-value")
                .run(context -> assertThat(context).hasFailed());
        assertThat(output.getAll()).doesNotContain(TOKEN, "APP_USR-another-private-value");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "-1", "0123", "1.0", "abc", "9223372036854775808"})
    void shouldRejectMissingOrInvalidIndependentSeller(String sellerId, CapturedOutput output) {
        enabled("local").withPropertyValues("billing.payment.mercado-pago.expected-test-seller-id=" + sellerId).run(context -> assertThat(context).hasFailed());
        assertThat(output.getAll()).doesNotContain(TOKEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "TEST-", "APP_USR-", "invalid", "APP_USR-fake token", "APP_USR-fake\r\nheader"})
    void shouldRejectAbsentOrMalformedTokenWithoutLeakingIt(String token, CapturedOutput output) {
        assertThatThrownBy(() -> new MercadoPagoProperties(true, "TEST", token, null, null, "1234567890123"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("header").hasMessageNotContaining("fake token");
        assertThat(output.getAll()).doesNotContain(TOKEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEST-fictitious", "APP_USR-fictitious"})
    void shouldTreatPrefixesOnlyAsSyntaxAndRequireIndependentSeller(String token) {
        assertThatThrownBy(() -> new MercadoPagoProperties(true, "TEST", token, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new MercadoPagoProperties(true, "TEST", token, null, null, "1234567890123").expectedTestSellerId()).isEqualTo("1234567890123");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0ms", "-1s", "1ns", "999999999s"})
    void shouldRejectInvalidTimeouts(String timeout) {
        enabled("local").withPropertyValues("billing.payment.mercado-pago.read-timeout=" + timeout).run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"base-url", "identity-base-url", "production-enabled"})
    void shouldRejectAttemptsToOverrideTrustedDestinationsOrEnableProduction(String property) {
        runner.withPropertyValues("billing.payment.mercado-pago." + property + "=https://other.test").run(context -> assertThat(context).hasFailed());
    }

    private ApplicationContextRunner enabled(String profiles) {
        return runner.withInitializer(context -> {
            context.getEnvironment().setActiveProfiles(profiles.isEmpty() ? new String[0] : profiles.split(","));
            context.getEnvironment().getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            Map.of("MERCADO_PAGO_TEST_ACCESS_TOKEN", TOKEN)));
        }).withPropertyValues("billing.payment.mercado-pago.enabled=true", "billing.payment.mercado-pago.environment=TEST",
                "billing.payment.mercado-pago.expected-test-seller-id=1234567890123",
                "billing.payment.mercado-pago.access-token=${MERCADO_PAGO_TEST_ACCESS_TOKEN}");
    }
}
