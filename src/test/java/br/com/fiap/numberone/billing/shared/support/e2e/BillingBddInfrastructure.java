package br.com.fiap.numberone.billing.shared.support.e2e;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;

@TestConfiguration(proxyBeanMethods = false)
public class BillingBddInfrastructure {
    @Bean(initMethod = "start", destroyMethod = "stop")
    PostgreSQLContainer<?> bddPostgres() {
        return new PostgreSQLContainer<>("postgres:17-alpine");
    }

    @Bean(destroyMethod = "close")
    BillingExternalServices billingExternalServices() throws IOException {
        return new BillingExternalServices();
    }

    @Bean
    DynamicPropertyRegistrar bddProperties(PostgreSQLContainer<?> bddPostgres, BillingExternalServices services) {
        return registry -> {
            registry.add("spring.datasource.url", bddPostgres::getJdbcUrl);
            registry.add("spring.datasource.username", bddPostgres::getUsername);
            registry.add("spring.datasource.password", bddPostgres::getPassword);
            registry.add("billing.integrations.os-base-url", services::baseUrl);
            registry.add("billing.integrations.inventory-base-url", services::baseUrl);
        };
    }
}
