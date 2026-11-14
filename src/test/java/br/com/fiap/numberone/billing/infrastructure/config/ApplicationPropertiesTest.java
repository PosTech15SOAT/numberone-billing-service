package br.com.fiap.numberone.billing.infrastructure.config;

import br.com.fiap.numberone.billing.budget.infrastructure.config.BudgetIntegrationProperties;
import br.com.fiap.numberone.billing.budget.infrastructure.config.BudgetNotificationProperties;
import br.com.fiap.numberone.billing.budget.infrastructure.config.OutboxPublisherProperties;
import br.com.fiap.numberone.billing.budget.infrastructure.config.SqsPublicationProperties;
import br.com.fiap.numberone.billing.payment.infrastructure.config.MercadoPagoProperties;
import br.com.fiap.numberone.billing.payment.infrastructure.config.MercadoPagoWebhookProperties;
import br.com.fiap.numberone.billing.payment.infrastructure.config.ServiceOrderPaymentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationPropertiesTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"default", "local"})
    void shouldPreserveOperationalDefaultsAndDisabledIntegrations(String profile) throws Exception {
        assertStartup(profile, "defaults", "INFO", Map.of());
    }

    private static void assertDefaults(ConfigurableEnvironment environment, String profile) {
        var binder = Binder.get(environment);
        assertThat(binder.bind("billing.integrations", BudgetIntegrationProperties.class).get())
                .isEqualTo(new BudgetIntegrationProperties(java.net.URI.create("http://os.test"),
                        java.net.URI.create("http://inventory.test"), Duration.ofSeconds(2), Duration.ofSeconds(5)));
        assertThat(binder.bind("billing.outbox", OutboxPublisherProperties.class).get())
                .isEqualTo(new OutboxPublisherProperties(10, Duration.ofSeconds(60), Duration.ofSeconds(1),
                        5, Duration.ofSeconds(5), Duration.ofMinutes(5)));
        assertThat(binder.bind("billing.notifications", BudgetNotificationProperties.class).get())
                .isEqualTo(new BudgetNotificationProperties("", "", Duration.ofHours(48), Duration.ofSeconds(30),
                        1, 5, Duration.ofSeconds(120), Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(30), ""));
        var payment = binder.bind("billing.payment.mercado-pago", MercadoPagoProperties.class).get();
        var serviceOrder = binder.bind("billing.payment.service-order", ServiceOrderPaymentProperties.class).get();
        assertThat(payment.enabled()).isFalse();
        assertThat(payment.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(payment.readTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(serviceOrder.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(serviceOrder.readTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(binder.bind("billing.payment.mercado-pago-webhook", MercadoPagoWebhookProperties.class).get().enabled()).isFalse();
        assertThat(environment.getProperty("billing.notifications.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("billing.outbox.enabled", Boolean.class)).isEqualTo(profile.equals("local"));
        assertThat(environment.getProperty("logging.level.root")).isEqualTo("INFO");
        assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("ecs");
        for (String property : new String[]{"server.tomcat.accesslog.enabled", "spring.mvc.log-request-details",
                "spring.jpa.show-sql", "spring.mail.properties.mail.debug"}) {
            assertThat(environment.getProperty(property, Boolean.class)).as(property).isFalse();
        }
        for (String property : new String[]{"auth", "starttls.enable", "starttls.required", "ssl.checkserveridentity"}) {
            assertThat(environment.getProperty("spring.mail.properties.mail.smtp." + property, Boolean.class)).as(property).isTrue();
        }
        for (String property : new String[]{"connectiontimeout", "timeout", "writetimeout"}) {
            assertThat(environment.getProperty("spring.mail.properties.mail.smtp." + property, Integer.class)).as(property).isEqualTo(5000);
        }
        if (profile.equals("local")) {
            var sqs = binder.bind("billing.outbox.sqs", SqsPublicationProperties.class).get();
            assertThat(sqs.endpoint()).hasToString("http://localhost:4566");
            assertThat(sqs.queueUrl()).hasToString("http://localhost:4566/queue/us-east-1/000000000000/numberone-billing-events");
            assertThat(sqs.connectionTimeout()).isEqualTo(Duration.ofSeconds(1));
            assertThat(sqs.readTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(sqs.apiCallTimeout()).isEqualTo(Duration.ofSeconds(5));
        }
    }

    @Test
    void shouldBindNativeEnvironmentNamesWithHyphensRemoved() throws Exception {
        assertStartup("local", "binding", "INFO", Map.of(
                "BILLING_OUTBOX_BATCHSIZE", "3", "BILLING_OUTBOX_POLLINTERVAL", "2s",
                "BILLING_INTEGRATIONS_READTIMEOUT", "7s", "BILLING_NOTIFICATIONS_AUTHORIZATIONTTL", "24h",
                "BILLING_PAYMENT_MERCADOPAGO_READTIMEOUT", "8s", "BILLING_PAYMENT_SERVICEORDER_READTIMEOUT", "9s",
                "BILLING_OUTBOX_SQS_APICALLTIMEOUT", "6s", "SPRING_MAIL_PROPERTIES_MAIL_SMTP_TIMEOUT", "7000",
                "OUTBOX_ENABLED", "false"));
    }

    private static void assertNativeBinding(ConfigurableEnvironment environment) {
        var binder = Binder.get(environment);
        var outbox = binder.bind("billing.outbox", OutboxPublisherProperties.class).get();
        assertThat(outbox.batchSize()).isEqualTo(3);
        assertThat(outbox.pollInterval()).isEqualTo(Duration.ofSeconds(2));
        assertThat(binder.bind("billing.integrations", BudgetIntegrationProperties.class).get().readTimeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(binder.bind("billing.notifications", BudgetNotificationProperties.class).get().authorizationTtl()).isEqualTo(Duration.ofHours(24));
        assertThat(binder.bind("billing.payment.mercado-pago", MercadoPagoProperties.class).get().readTimeout()).isEqualTo(Duration.ofSeconds(8));
        assertThat(binder.bind("billing.payment.service-order", ServiceOrderPaymentProperties.class).get().readTimeout()).isEqualTo(Duration.ofSeconds(9));
        assertThat(binder.bind("billing.outbox.sqs", SqsPublicationProperties.class).get().apiCallTimeout()).isEqualTo(Duration.ofSeconds(6));
        assertThat(binder.bind("spring.mail.properties.mail.smtp.timeout", Integer.class).get()).isEqualTo(7000);
        assertThat(environment.getProperty("billing.outbox.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("billing.outbox.poll-interval")).isEqualTo("2s");
    }

    @ParameterizedTest
    @ValueSource(strings = {"INFO", "DEBUG", "WARN", "ERROR", "TRACE"})
    void shouldApplyDotenvLogLevelDuringLocalStartup(String level) throws Exception {
        Files.writeString(directory.resolve(".env"), "LOG_LEVEL=" + level + "\n");
        assertStartup("local", "logging", level, Map.of());
    }

    @Test
    void shouldDefaultToInfoWithoutDotenv() throws Exception {
        assertStartup("local", "logging", "INFO", Map.of());
    }

    @Test
    void shouldPreferProcessLogLevelOverDotenv() throws Exception {
        Files.writeString(directory.resolve(".env"), "LOG_LEVEL=DEBUG\n");
        assertStartup("local", "logging", "WARN", Map.of("LOG_LEVEL", "WARN"));
    }

    private void assertStartup(String profile, String scenario, String expected, Map<String, String> variables) throws Exception {
        // Separate JVM: tests the real early logging initialization and relative .env import without reading local secrets.
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path"), LoggingProbe.class.getName(),
                "--spring.profiles.active=" + profile, "--spring.config.location=classpath:/application.properties",
                "--probe.scenario=" + scenario)
                .directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("startup.log").toFile());
        process.environment().clear();
        process.environment().putAll(Map.of("OS_SERVICE_BASE_URL", "http://os.test", "INVENTORY_SERVICE_BASE_URL", "http://inventory.test"));
        process.environment().putAll(variables);
        var child = process.start();
        try {
            assertThat(child.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).as(Files.readString(directory.resolve("startup.log"))).isZero();
            assertThat(Files.readString(directory.resolve("startup.log"))).contains("ROOT_LEVEL=" + expected);
        } finally {
            child.destroyForcibly();
        }
    }

    public static class LoggingProbe {
        public static void main(String[] args) {
            var application = new SpringApplication(ProbeConfiguration.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            try (var context = application.run(args)) {
                var environment = context.getEnvironment();
                switch (environment.getProperty("probe.scenario", "logging")) {
                    case "defaults" -> assertDefaults(environment, environment.getActiveProfiles()[0]);
                    case "binding" -> assertNativeBinding(environment);
                    default -> { }
                }
                var root = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
                System.out.println("ROOT_LEVEL=" + root.getLevel());
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ProbeConfiguration { }
}
