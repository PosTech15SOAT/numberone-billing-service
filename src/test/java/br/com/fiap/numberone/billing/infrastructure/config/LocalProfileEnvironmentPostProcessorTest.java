package br.com.fiap.numberone.billing.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalProfileEnvironmentPostProcessorTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"local", "local,homologation"})
    void shouldAllowLocalOutsideKubernetes(String profiles) throws Exception {
        var application = application(Map.of());
        try (var context = application.run(configuration("spring.profiles.active=" + profiles))) {
            assertThat(context.isActive()).isTrue();
            assertThat(context.getEnvironment().matchesProfiles("local")).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"spring.profiles.active=local", "spring.profiles.active=homologation,local",
            "spring.profiles.default=local", "spring.profiles.include=local",
            "spring.profiles.active=developer\nspring.profiles.group.developer=local"})
    void shouldRefuseLocalDuringStartupAfterResolvingProfiles(String profiles) throws Exception {
        var application = application(kubernetesEnvironment());
        assertThatThrownBy(() -> application.run(configuration(profiles + "\nspring.main.cloud-platform=none")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("O profile local não é permitido no Kubernetes");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "test", "eks", "homologation,production"})
    void shouldPreserveOtherProfilesInsideKubernetes(String profiles) throws Exception {
        var application = application(kubernetesEnvironment());
        try (var context = application.run(configuration("spring.profiles.active=" + profiles))) {
            assertThat(context.isActive()).isTrue();
            assertThat(context.getEnvironment().matchesProfiles("local")).isFalse();
        }
    }

    @Test
    void shouldNotTreatAnArbitraryEnvironmentLabelAsKubernetes() throws Exception {
        var application = application(Map.of("ENVIRONMENT", "production"));
        try (var context = application.run(configuration("spring.profiles.active=local"))) {
            assertThat(context.isActive()).isTrue();
        }
    }

    private SpringApplication application(Map<String, Object> variables) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        var application = new SpringApplication(ProbeConfiguration.class);
        application.setEnvironment(environment);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        return application;
    }

    private String[] configuration(String properties) throws Exception {
        // Arquivo isolado: não importa o .env local nem inicializa integrações durante esta prova de startup.
        Path configuration = directory.resolve("application.properties");
        Files.writeString(configuration, properties);
        return new String[]{"--spring.config.location=" + configuration.toUri(), "--spring.main.banner-mode=off"};
    }

    private Map<String, Object> kubernetesEnvironment() {
        return Map.of("KUBERNETES_SERVICE_HOST", "10.96.0.1", "KUBERNETES_SERVICE_PORT", "443");
    }

    @Configuration(proxyBeanMethods = false)
    static class ProbeConfiguration { }
}
