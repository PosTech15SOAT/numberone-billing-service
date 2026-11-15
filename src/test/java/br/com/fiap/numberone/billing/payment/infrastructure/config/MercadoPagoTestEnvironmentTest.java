package br.com.fiap.numberone.billing.payment.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.*;

class MercadoPagoTestEnvironmentTest {
    private MockEnvironment kind() {
        var environment = new MockEnvironment().withProperty("KUBERNETES_SERVICE_HOST", "10.96.0.1")
                .withProperty("POD_NAMESPACE", "numberone-local").withProperty("MERCADO_PAGO_KIND_TEST_ENABLED", "true")
                .withProperty("OS_SERVICE_BASE_URL", "http://numberone-os-service:8080")
                .withProperty("INVENTORY_SERVICE_BASE_URL", "http://numberone-inventory-service:8082");
        environment.setActiveProfiles("kind");
        return environment;
    }

    @Test void allowsExplicitDevelopmentKind() {
        assertThatCode(() -> MercadoPagoTestEnvironment.validate(kind())).doesNotThrowAnyException();
    }

    @Test void rejectsEveryMissingKindCondition() {
        for (String property : new String[]{"KUBERNETES_SERVICE_HOST", "POD_NAMESPACE", "MERCADO_PAGO_KIND_TEST_ENABLED",
                "OS_SERVICE_BASE_URL", "INVENTORY_SERVICE_BASE_URL"}) {
            var configured = kind();
            configured.setProperty(property, "");
            assertThatThrownBy(() -> MercadoPagoTestEnvironment.validate(configured)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void rejectsProductionAndMixedProfilesDespitePermission() {
        for (String[] profiles : new String[][]{{"eks"}, {"production"}, {"kind", "production"}, {"local", "kind"}}) {
            var environment = kind(); environment.setActiveProfiles(profiles);
            assertThatThrownBy(() -> MercadoPagoTestEnvironment.validate(environment)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void rejectsLocalInsideKubernetes() {
        var environment = kind(); environment.setActiveProfiles("local");
        assertThatThrownBy(() -> MercadoPagoTestEnvironment.validate(environment)).isInstanceOf(IllegalArgumentException.class);
    }
}
