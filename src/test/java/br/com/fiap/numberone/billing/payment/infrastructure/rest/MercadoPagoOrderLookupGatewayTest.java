package br.com.fiap.numberone.billing.payment.infrastructure.rest;

import br.com.fiap.numberone.billing.payment.application.exceptions.OrderLookupException;
import br.com.fiap.numberone.billing.payment.application.results.ProviderOrderSnapshot;
import br.com.fiap.numberone.billing.payment.infrastructure.config.MercadoPagoProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static br.com.fiap.numberone.billing.payment.support.MercadoPagoWebhookFixtures.officialBody;
import static br.com.fiap.numberone.billing.payment.support.MercadoPagoWebhookFixtures.payment;

class MercadoPagoOrderLookupGatewayTest {
    public static final String REFERENCE = "873157ce-ac37-44e5-b74c-b7a26b055205";
    private HttpServer server;
    private HttpClient client;
    private final AtomicInteger identityRequests = new AtomicInteger();
    private volatile int identityStatus = 200;
    private volatile String identityBody = "{\"id\":1234567890123}";
    private volatile long identityDelay;
    private MercadoPagoOrderLookupGateway gateway;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private int status = 200;
    private String body = officialBody("ORD01ABC", REFERENCE, "processed", "accredited");
    private long delay;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            path.set(exchange.getRequestURI().toString());
            method.set(exchange.getRequestMethod());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try {
                if (delay > 0) Thread.sleep(delay);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/redirected");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException ignored) { /* Conexão encerrada deliberadamente no teste de timeout. */ }
            finally { exchange.close(); }
        });
        server.createContext("/users/me", exchange -> {
            identityRequests.incrementAndGet();
            try {
                if (identityDelay > 0) Thread.sleep(identityDelay);
                byte[] bytes = identityBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(identityStatus, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException ignored) { /* Timeout deliberado. */ }
            finally { exchange.close(); }
        });
        server.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(1)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofMillis(200));
        RestClient simulated = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).requestFactory(factory).build();
        MercadoPagoProperties properties = new MercadoPagoProperties(true, "TEST", "TEST-fictitious-only", null, null, "1234567890123");
        gateway = new MercadoPagoOrderLookupGateway(simulated, properties, new MercadoPagoOrderDetailsMapper(),
                new MercadoPagoSellerIdentityVerifier(simulated, properties));
    }

    @AfterEach void close() { server.stop(0); client.close(); }

    @Test
    void shouldQueryOfficialOrdersContractAndKeepOrderAndPaymentIdsDistinct() {
        ProviderOrderSnapshot result = gateway.findById("ORD01ABC");
        assertThat(identityRequests).hasValue(1);
        assertThat(path.get()).isEqualTo("/v1/orders/ORD01ABC");
        assertThat(method.get()).isEqualTo("GET");
        assertThat(auth.get()).isEqualTo("Bearer TEST-fictitious-only");
        assertThat(result.providerOrderId()).isEqualTo("ORD01ABC");
        assertThat(result.payments().getFirst().providerPaymentId()).isEqualTo("PAY01ABC");
        assertThat(result.totalAmount()).isEqualByComparingTo("10.00");
        assertThat(result.currency()).isEqualTo("BRL");
        assertThat(result.toString()).doesNotContain("private-token", "private@example.test");
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @CsvSource({"404,NOT_FOUND", "400,REQUEST_REJECTED", "401,AUTHORIZATION", "403,AUTHORIZATION", "408,TIMEOUT", "429,RATE_LIMIT", "500,PROVIDER_FAILURE", "503,PROVIDER_FAILURE", "302,INVALID_RESPONSE"})
    void shouldClassifyHttpFailuresWithoutKeepingPrivateBodiesOrFollowingRedirects(int httpStatus, OrderLookupException.Kind expected) {
        status = httpStatus;
        body = "private-error-token";
        assertThatThrownBy(() -> gateway.findById("ORD01ABC")).isInstanceOfSatisfying(OrderLookupException.class, failure -> {
            assertThat(failure.getKind()).isEqualTo(expected);
            assertThat(failure.getHttpStatus()).isEqualTo(httpStatus);
            assertThat(failure.getCause()).isInstanceOfSatisfying(RestClientResponseException.class,
                    cause -> assertThat(cause.getResponseBodyAsString()).isEmpty());
        });
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "reference", "currency", "amount", "numeric", "payment_id", "duplicate_payment", "payment_amount", "paid_amount", "transactions", "status", "processing_mode", "type", "trailing", "malformed", "oversized", "live_mode", "duplicate_field"})
    void shouldRejectMalformedOrInconsistentOfficialResponse(String field) {
        body = switch (field) {
            case "duplicate_field" -> body.replace("\"total_amount\":\"10.00\"", "\"total_amount\":\"1.00\",\"total_amount\":\"10.00\"");
            case "id" -> body.replace("ORD01ABC", "ORDother");
            case "reference" -> body.replace(REFERENCE, "not-a-uuid");
            case "currency" -> body.replace("BRL", "USD");
            case "amount" -> body.replace("\"total_amount\":\"10.00\"", "\"total_amount\":\"10.001\"");
            case "numeric" -> body.replace("\"total_amount\":\"10.00\"", "\"total_amount\":10.00");
            case "payment_id" -> body.replace("PAY01ABC", "../unsafe");
            case "duplicate_payment" -> body.replace("\"payments\":[", "\"payments\":[" + payment() + ",");
            case "payment_amount" -> body.replace("\"amount\":\"10.00\"", "\"amount\":\"-1\"");
            case "paid_amount" -> body.replace("\"paid_amount\":\"10.00\"", "\"paid_amount\":42");
            case "transactions" -> body.replace("\"transactions\":{", "\"transactions\":[{");
            case "status" -> body.replace("\"status\":\"processed\"", "\"status\":\"PROCESSED\"");
            case "processing_mode" -> body.replace("manual", "automatic");
            case "type" -> body.replace("online", "qr");
            case "trailing" -> body + " {}";
            case "oversized" -> "x".repeat(65537);
            case "live_mode" -> body.replace("\"type\":", "\"live_mode\":true,\"type\":");
            default -> "private-json-payload";
        };
        assertThatThrownBy(() -> gateway.findById("ORD01ABC")).isInstanceOfSatisfying(OrderLookupException.class,
                failure -> assertThat(failure.getKind()).isEqualTo(OrderLookupException.Kind.INVALID_RESPONSE));
    }

    @Test
    void shouldMapTimeoutWithoutRetry() {
        delay = 600;
        assertThatThrownBy(() -> gateway.findById("ORD01ABC")).isInstanceOfSatisfying(OrderLookupException.class,
                failure -> assertThat(failure.getKind()).isEqualTo(OrderLookupException.Kind.TIMEOUT));
        assertThat(requests).hasValue(1);
    }

    @Test
    void shouldMapConnectionFailure() {
        server.stop(0);
        assertThatThrownBy(() -> gateway.findById("ORD01ABC")).isInstanceOfSatisfying(OrderLookupException.class,
                failure -> assertThat(failure.getKind()).isEqualTo(OrderLookupException.Kind.COMMUNICATION));
    }

    @Test
    void shouldRefuseHttpInsideTransactionOrWithUnsafeIdentifier() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> gateway.findById("ORD01ABC")).isInstanceOfSatisfying(OrderLookupException.class,
                    failure -> assertThat(failure.getKind()).isEqualTo(OrderLookupException.Kind.ACTIVE_TRANSACTION));
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        assertThatThrownBy(() -> gateway.findById("../unsafe")).isInstanceOf(OrderLookupException.class);
        assertThat(requests).hasValue(0);
    }

    @Test
    void shouldDetectFinancialAdjustmentsWithoutReadingProtectedFields() {
        body = body.replace("\"payments\":[", "\"refunds\":[{}],\"payments\":[");
        assertThat(gateway.findById("ORD01ABC").hasRefundsOrChargebacks()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"401,AUTHORIZATION", "403,AUTHORIZATION", "500,INVALID_RESPONSE", "408,TIMEOUT"})
    void shouldNotQueryOrderAfterSellerVerificationHttpFailure(int identityHttpStatus, OrderLookupException.Kind kind) {
        identityStatus = identityHttpStatus;
        identityBody = "private-response-token";
        assertThatThrownBy(() -> gateway.findById("ORD01ABC")).isInstanceOfSatisfying(OrderLookupException.class,
                failure -> assertThat(failure.getKind()).isEqualTo(kind));
        assertThat(identityRequests).hasValue(1);
        assertThat(requests).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"id\":1234567890124}", "{\"id\":\"1234567890123\"}", "{}", "[]"})
    void shouldNotQueryOrderAfterInvalidOrDivergentSeller(String response) {
        identityBody = response;
        assertThatThrownBy(() -> gateway.findById("ORD01ABC")).isInstanceOf(OrderLookupException.class);
        assertThat(identityRequests).hasValue(1);
        assertThat(requests).hasValue(0);
    }

    @Test void shouldNotQueryOrderAfterIdentityTimeoutOrRetryAutomatically() {
        identityDelay = 600;
        assertThatThrownBy(() -> gateway.findById("ORD01ABC")).isInstanceOfSatisfying(OrderLookupException.class,
                failure -> assertThat(failure.getKind()).isEqualTo(OrderLookupException.Kind.TIMEOUT));
        assertThat(identityRequests).hasValue(1);
        assertThat(requests).hasValue(0);
    }

    @Test void shouldValidateOnEveryLookupAndUseExactlyVerifiedCredential() {
        RestClient simulated = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
        MercadoPagoProperties verified = new MercadoPagoProperties(true, "TEST", "TEST-fictitious-only", null, null, "1234567890123");
        MercadoPagoProperties other = new MercadoPagoProperties(true, "TEST", "APP_USR-other-fictitious", null, null, "1234567890123");
        MercadoPagoOrderLookupGateway configured = new MercadoPagoOrderLookupGateway(simulated, other, new MercadoPagoOrderDetailsMapper(),
                new MercadoPagoSellerIdentityVerifier(simulated, verified));
        configured.findById("ORD01ABC");
        configured.findById("ORD01ABC");
        assertThat(identityRequests).hasValue(2);
        assertThat(requests).hasValue(2);
        assertThat(auth.get()).isEqualTo("Bearer TEST-fictitious-only");
    }

}
