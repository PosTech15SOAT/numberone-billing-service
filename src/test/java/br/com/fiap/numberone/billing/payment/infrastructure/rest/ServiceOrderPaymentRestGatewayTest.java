package br.com.fiap.numberone.billing.payment.infrastructure.rest;

import br.com.fiap.numberone.billing.payment.application.exceptions.ServiceOrderNotFoundException;
import br.com.fiap.numberone.billing.payment.application.exceptions.ServiceOrderPaymentUnavailableException;
import br.com.fiap.numberone.billing.payment.application.exceptions.ServiceOrderPaymentUnavailableException.Failure;
import br.com.fiap.numberone.billing.payment.domain.exceptions.ServiceOrderNotCompletedException;
import br.com.fiap.numberone.billing.payment.domain.references.ServiceOrderPaymentContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class ServiceOrderPaymentRestGatewayTest {
    private final UUID orderId = UUID.randomUUID();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> employee = new AtomicReference<>();
    private HttpServer server;
    private HttpClient client;
    private ServiceOrderPaymentRestGateway gateway;
    private int status = 200;
    private String contentType = "application/json";
    private String response;
    private long delayMillis;

    @BeforeEach
    void setUp() throws Exception {
        response = body(orderId, "COMPLETED");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().toString());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            employee.set(exchange.getRequestHeaders().getFirst("X-Authenticated-Subject"));
            try {
                if (delayMillis > 0) Thread.sleep(delayMillis);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/redirected");
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException ignored) {
                // O teste de timeout encerra deliberadamente a conexão antes da resposta.
            } finally { exchange.close(); }
        });
        server.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(1)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofMillis(150));
        gateway = new ServiceOrderPaymentRestGateway(RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).requestFactory(factory).build(), new ServiceOrderPaymentMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        client.close();
    }

    @Test
    void shouldUseHtmlContractAndReturnTrustedCompletedWithoutAdministrativeHeaders() {
        ServiceOrderPaymentContext context = gateway.findById(orderId);
        assertThat(context.serviceOrderId()).isEqualTo(orderId);
        assertThat(context.status()).isEqualTo("COMPLETED");
        context.requireCompleted();
        assertThat(method.get()).isEqualTo("GET");
        assertThat(path.get()).isEqualTo("/internal/service-orders/" + orderId);
        assertThat(authorization.get()).isNull();
        assertThat(employee.get()).isNull();
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"IN_PROGRESS", "CANCELLED", "FINALIZADA"})
    void shouldPreserveOtherStatusWithoutTranslatingItToCompleted(String status) {
        response = body(orderId, status);
        ServiceOrderPaymentContext context = gateway.findById(orderId);
        assertThat(context.status()).isEqualTo(status);
        assertThatThrownBy(context::requireCompleted).isInstanceOf(ServiceOrderNotCompletedException.class);
    }

    @Test
    void shouldBlockCancellationInProgress() {
        response = body(orderId, "COMPLETED").replace("\"cancellationInProgress\":false", "\"cancellationInProgress\":true");
        assertThatThrownBy(() -> gateway.findById(orderId).requireCompleted()).isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"false\"", "0"})
    void shouldRejectInvalidCancellationFlag(String value) {
        response = body(orderId, "COMPLETED").replace("\"cancellationInProgress\":false", "\"cancellationInProgress\":" + value);
        assertFailure(Failure.INVALID_RESPONSE);
    }

    @Test void shouldRejectMissingCancellationFlag() {
        response = body(orderId, "COMPLETED").replace(",\"cancellationInProgress\":false", "");
        assertFailure(Failure.INVALID_RESPONSE);
    }

    @Test
    void shouldMapNotFoundWithoutCopyingPrivateBody() {
        status = 404;
        response = "private-token-private-email";
        assertThatThrownBy(() -> gateway.findById(orderId)).isInstanceOf(ServiceOrderNotFoundException.class)
                .hasMessage("OS não encontrada");
    }

    @Test
    void shouldRejectDivergentIdentity() {
        response = body(UUID.randomUUID(), "COMPLETED");
        assertFailure(Failure.INVALID_RESPONSE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "malformed-private-json", "{}", "{\"serviceOrderId\":42,\"status\":\"COMPLETED\"}",
            "{\"serviceOrderId\":\"%s\",\"status\":42}", "{\"serviceOrderId\":\"%s\",\"status\":null}",
            "{\"serviceOrderId\":\"%s\",\"status\":\"completed\"}", "{\"serviceOrderId\":\"%s\",\"status\":\" COMPLETED\"}"})
    void shouldRejectInvalidPayload(String payload) {
        response = payload.contains("%s") ? payload.formatted(orderId) : payload;
        assertFailure(Failure.INVALID_RESPONSE);
    }

    @Test
    void shouldRejectOversizedPayload() {
        response = "x".repeat(17000);
        assertFailure(Failure.INVALID_RESPONSE);
    }

    @Test
    void shouldRejectNonJsonContentType() {
        contentType = "text/html";
        assertFailure(Failure.INVALID_RESPONSE);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 409, 422, 429, 500, 503})
    void shouldMapHttpFailuresWithoutRetainingPrivateResponse(int httpStatus) {
        status = httpStatus;
        response = "private-response-token";
        assertThatThrownBy(() -> gateway.findById(orderId)).isInstanceOfSatisfying(ServiceOrderPaymentUnavailableException.class, failure -> {
            assertThat(failure.getFailure()).isEqualTo(Failure.UNAVAILABLE);
            assertThat(failure.getCause()).isInstanceOfSatisfying(RestClientResponseException.class, cause -> {
                assertThat(cause.getStatusCode().value()).isEqualTo(httpStatus);
                assertThat(cause.getResponseBodyAsString()).isEmpty();
                assertThat(cause.getResponseHeaders()).isNull();
            });
        });
        assertThat(requests).hasValue(1);
    }

    @Test
    void shouldMapHttpTimeout() {
        status = 408;
        assertFailure(Failure.TIMEOUT);
    }

    @Test
    void shouldMapReadTimeoutWithoutRetry() {
        delayMillis = 600;
        assertFailure(Failure.TIMEOUT);
        assertThat(requests).hasValue(1);
    }

    @Test
    void shouldMapConnectionFailure() {
        server.stop(0);
        assertFailure(Failure.UNAVAILABLE);
    }

    @Test
    void shouldNotFollowRedirect() {
        status = 302;
        assertFailure(Failure.INVALID_RESPONSE);
        assertThat(requests).hasValue(1);
    }

    private void assertFailure(Failure expected) {
        assertThatThrownBy(() -> gateway.findById(orderId)).isInstanceOfSatisfying(ServiceOrderPaymentUnavailableException.class,
                failure -> assertThat(failure.getFailure()).isEqualTo(expected));
    }

    private String body(UUID id, String status) {
        return "{\"serviceOrderId\":\"" + id + "\",\"status\":\"" + status + "\",\"cancellationInProgress\":false,\"updatedAt\":\"2026-11-09T12:00:00Z\"}";
    }
}
