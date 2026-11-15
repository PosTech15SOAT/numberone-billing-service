package br.com.fiap.numberone.billing.budget.infrastructure.outbox;

import br.com.fiap.numberone.billing.budget.application.exceptions.IntegrationEventPublicationException;
import br.com.fiap.numberone.billing.budget.infrastructure.config.OutboxPublicationConfig;
import br.com.fiap.numberone.billing.budget.infrastructure.config.SqsPublicationProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class SqsIntegrationEventPublicationGatewayTest {

    @ParameterizedTest
    @CsvSource({"503,false,UNAVAILABLE", "403,true,REJECTED", "429,false,UNAVAILABLE"})
    void shouldClassifyFailuresWithoutLeakingResponsesOrRetryingInsideSdk(int status, boolean review, String reason) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = "{\"__type\":\"ServiceFailure\",\"message\":\"secret-password-do-not-record\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/x-amz-json-1.0");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        try (var client = new OutboxPublicationConfig().sqsClient(properties(endpoint, Duration.ofSeconds(2)),
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))) {
            var gateway = new SqsIntegrationEventPublicationGateway(client, endpoint.resolve("/000000000000/test"));
            assertThatThrownBy(() -> gateway.publish("{}"))
                    .isInstanceOfSatisfying(IntegrationEventPublicationException.class, failure -> {
                        assertThat(failure.getCause()).isInstanceOf(software.amazon.awssdk.services.sqs.model.SqsException.class);
                        assertThat(failure.requiresReview()).isEqualTo(review);
                        assertThat(failure.getMessage()).contains(reason, "HTTP_" + status).doesNotContain("secret", "password");
                    });
            assertThat(requests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldBoundAStalledHttpRequestWithAnExplicitTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            try (var client = new OutboxPublicationConfig().sqsClient(properties(endpoint, Duration.ofMillis(300)),
                    StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))) {
                assertThatThrownBy(() -> new SqsIntegrationEventPublicationGateway(client, endpoint.resolve("/000000000000/test")).publish("{}"))
                        .isInstanceOfSatisfying(IntegrationEventPublicationException.class, failure ->
                                assertThat(failure).hasMessageContaining("TIMEOUT")
                                        .hasCauseInstanceOf(software.amazon.awssdk.core.exception.SdkClientException.class));
            } finally {
                release.countDown();
                server.stop(0);
            }
        }
    }

    private SqsPublicationProperties properties(URI endpoint, Duration timeout) {
        return new SqsPublicationProperties("us-east-1", endpoint.resolve("/000000000000/test"), endpoint,
                Duration.ofMillis(200), timeout, timeout);
    }
}
