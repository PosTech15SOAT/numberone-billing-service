package br.com.fiap.numberone.billing.infrastructure.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.cloud.CloudPlatform;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

public class LocalProfileEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // Detecta as variáveis da plataforma sem permitir bypass por spring.main.cloud-platform=none.
        if (CloudPlatform.KUBERNETES.isDetected(environment) && environment.matchesProfiles("local")) {
            throw new IllegalStateException("O profile local não é permitido no Kubernetes");
        }
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
