package br.com.fiap.numberone.billing.payment.infrastructure.config;

import org.springframework.core.env.Environment;

/** Explicit permission for the isolated development cluster; never enables production. */
final class MercadoPagoTestEnvironment {
    private MercadoPagoTestEnvironment() { }

    static void validate(Environment environment) {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length == 1 && "local".equals(profiles[0])
                && environment.getProperty("KUBERNETES_SERVICE_HOST") == null) return;
        if (profiles.length == 1 && "kind".equals(profiles[0])
                && !environment.getProperty("KUBERNETES_SERVICE_HOST", "").isBlank()
                && "numberone-local".equals(environment.getProperty("POD_NAMESPACE"))
                && "true".equals(environment.getProperty("MERCADO_PAGO_KIND_TEST_ENABLED"))
                && "http://numberone-os-service:8080".equals(environment.getProperty("OS_SERVICE_BASE_URL"))
                && "http://numberone-inventory-service:8082".equals(environment.getProperty("INVENTORY_SERVICE_BASE_URL"))) return;
        throw new IllegalArgumentException("Mercado Pago TEST exige local fora de Kubernetes ou autorização explícita do Kind de desenvolvimento");
    }
}
