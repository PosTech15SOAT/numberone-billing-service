package br.com.fiap.numberone.billing.payment.infrastructure.rest;

import br.com.fiap.numberone.billing.payment.application.exceptions.CheckoutCreationException;
import br.com.fiap.numberone.billing.payment.application.exceptions.CheckoutCreationException.Kind;
import br.com.fiap.numberone.billing.payment.application.gateways.CheckoutGateway;
import br.com.fiap.numberone.billing.payment.application.results.CheckoutOrder;
import br.com.fiap.numberone.billing.payment.domain.entities.Charge;
import br.com.fiap.numberone.billing.payment.domain.entities.PaymentAttempt;
import br.com.fiap.numberone.billing.payment.domain.enums.ChargeStatus;
import br.com.fiap.numberone.billing.payment.domain.enums.PaymentAttemptStatus;
import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentDataException;
import br.com.fiap.numberone.billing.payment.domain.references.ServiceOrderPaymentContext;
import br.com.fiap.numberone.billing.payment.infrastructure.config.MercadoPagoProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@ExtendWith(OutputCaptureExtension.class)
class MercadoPagoCheckoutGatewayTest {
    private static final String TOKEN = "TEST-fabricated-not-a-credential";
    private static final String ORDER_ID = "ORDTST-fabricated-order";
    private static final String CHECKOUT_URL = "https://www.mercadopago.com.br/checkout/v1/redirect?order_id=" + ORDER_ID;
    private final JsonMapper jsonMapper = new JsonMapper();
    private final BlockingQueue<ReceivedRequest> received = new LinkedBlockingQueue<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private HttpServer server;
    private HttpClient httpClient;
    private MercadoPagoCheckoutGateway gateway;
    private Charge charge;
    private PaymentAttempt attempt;
    private int responseStatus;
    private String responseBody;
    private long delayMillis;
    private final AtomicInteger identityRequests = new AtomicInteger();
    private volatile String identityAuthorization;

    @BeforeEach
    void startSimulatedServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/users/me", exchange -> {
            identityRequests.incrementAndGet();
            identityAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body = "{\"id\":1234567890123}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/", this::respond);
        server.start();
        charge = Charge.create(new ServiceOrderPaymentContext(UUID.randomUUID(), "COMPLETED"), UUID.randomUUID(),
                new BigDecimal("530.12"), Currency.getInstance("BRL"));
        attempt = charge.startAttempt(UUID.randomUUID());
        responseStatus = 201;
        responseBody = jsonMapper.writeValueAsString(validResponse());
        gateway = gateway("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(3), true);
    }

    @AfterEach
    void stopSimulatedServer() {
        server.stop(0);
        executor.shutdownNow();
        httpClient.close();
    }

    @Test
    void shouldSendExactApprovedAmountAndPersistedKeyAndReturnOnlyOrderData() throws Exception {
        CheckoutOrder result = gateway.validateSeller().createCheckout(charge, attempt.getAttemptId());
        ReceivedRequest request = received.poll(3, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v1/orders");
        assertThat(request.authorization()).isEqualTo("Bearer " + TOKEN);
        assertThat(request.key()).isEqualTo(attempt.getIdempotencyKey().toString());
        assertThat(request.contentType()).isEqualTo("application/json");
        assertThat(request.accept()).isEqualTo("application/json");
        Map<?, ?> json = jsonMapper.readValue(request.body(), Map.class);
        assertThat(json.get("type")).isEqualTo("online");
        assertThat(json.get("processing_mode")).isEqualTo("manual");
        assertThat(json.get("total_amount")).isEqualTo("530.12");
        assertThat(json.get("external_reference")).isEqualTo(attempt.getAttemptId().toString());
        assertThat(json.containsKey("currency")).isFalse();
        assertThat(json.containsKey("payer")).isFalse();
        List<?> items = (List<?>) json.get("items");
        assertThat(((Map<?, ?>) items.getFirst()).get("unit_price")).isEqualTo("530.12");
        assertThat(((Map<?, ?>) items.getFirst()).get("quantity")).isEqualTo(1);
        assertThat(result.providerOrderId()).isEqualTo(ORDER_ID);
        assertThat(result.checkoutUrl().toString()).isEqualTo(CHECKOUT_URL);
        assertThat(result.providerStatus()).isEqualTo("created");
        assertThat(result.toString()).doesNotContain(CHECKOUT_URL);
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.OPEN);
        assertThat(attempt.getProviderOrderId()).isNull();
        assertThat(attempt.getProviderPaymentId()).isNull();
        assertThat(attempt.getPaymentMethod()).isNull();
    }

    @Test
    void shouldReuseIdenticalKeyAndBodyOnExplicitEquivalentCalls() throws Exception {
        gateway.validateSeller().createCheckout(charge, attempt.getAttemptId());
        gateway.validateSeller().createCheckout(charge, attempt.getAttemptId());
        ReceivedRequest first = received.poll(3, TimeUnit.SECONDS);
        ReceivedRequest second = received.poll(3, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(second.key()).isEqualTo(first.key()).isEqualTo(attempt.getIdempotencyKey().toString());
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(received).isEmpty();
    }

    @Test
    void shouldConsumeValidatedCreationOnlyOnceWithoutAnotherIdentityRequest() throws Exception {
        CheckoutGateway.ValidatedCheckout validated = gateway.validateSeller();
        assertThat(identityRequests.get()).isEqualTo(1);
        assertThat(received).isEmpty();
        validated.createCheckout(charge, attempt.getAttemptId());
        assertThatThrownBy(() -> validated.createCheckout(charge, attempt.getAttemptId()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(received.poll(3, TimeUnit.SECONDS)).isNotNull();
        assertThat(received).isEmpty();
        assertThat(identityRequests.get()).isEqualTo(1);
    }

    @Test
    void shouldUseExactCredentialValidatedByVerifierEvenIfGatewayPropertiesDiffer() throws Exception {
        RestClient simulated = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
        MercadoPagoProperties verified = new MercadoPagoProperties(true, "TEST", TOKEN, null, null, "1234567890123");
        MercadoPagoProperties different = new MercadoPagoProperties(true, "TEST", "APP_USR-other-fictitious-token", null, null, "1234567890123");
        MercadoPagoCheckoutGateway configured = new MercadoPagoCheckoutGateway(simulated, new MercadoPagoOrderMapper(), different,
                new MercadoPagoSellerIdentityVerifier(simulated, verified));
        CheckoutGateway.ValidatedCheckout validated = configured.validateSeller();
        validated.createCheckout(charge, attempt.getAttemptId());
        assertThat(identityAuthorization).isEqualTo("Bearer " + TOKEN);
        assertThat(received.poll(3, TimeUnit.SECONDS).authorization()).isEqualTo(identityAuthorization);
        assertThat(identityRequests.get()).isEqualTo(1);
        assertThat(received).isEmpty();
    }

    @Test
    void shouldBlockPostIfTransactionStartsAfterIdentityValidation() {
        CheckoutGateway.ValidatedCheckout validated = gateway.validateSeller();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> validated.createCheckout(charge, attempt.getAttemptId()))
                    .isInstanceOfSatisfying(SellerIdentityVerificationException.class,
                            failure -> assertThat(failure.getReason()).isEqualTo(SellerIdentityVerificationException.Reason.ACTIVE_TRANSACTION));
            assertThat(received).isEmpty();
            assertThat(identityRequests.get()).isEqualTo(1);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "1.2", "1.23"})
    void shouldSerializeDecimalStringWithTwoPlacesWithoutRounding(String amount) throws Exception {
        Charge priced = Charge.create(new ServiceOrderPaymentContext(UUID.randomUUID(), "COMPLETED"), UUID.randomUUID(),
                new BigDecimal(amount), Currency.getInstance("BRL"));
        PaymentAttempt pricedAttempt = priced.startAttempt(UUID.randomUUID());
        Map<String, Object> response = validResponse();
        response.put("total_amount", amount);
        response.put("external_reference", pricedAttempt.getAttemptId().toString());
        responseBody = jsonMapper.writeValueAsString(response);
        gateway.validateSeller().createCheckout(priced, pricedAttempt.getAttemptId());
        ReceivedRequest request = received.poll(3, TimeUnit.SECONDS);
        assertThat(jsonMapper.readValue(request.body(), Map.class).get("total_amount"))
                .isEqualTo(new BigDecimal(amount).setScale(2).toPlainString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "checkout_url", "status"})
    void shouldRejectMissingRequiredResponseFieldsWithUnknownOutcome(String missing) {
        Map<String, Object> response = validResponse();
        response.remove(missing);
        responseBody = jsonMapper.writeValueAsString(response);
        CheckoutCreationException failure = failure();
        assertThat(failure.getKind()).isEqualTo(Kind.INVALID_RESPONSE);
        assertThat(failure.isResultUnknown()).isTrue();
        assertThat(failure.getCause()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "not-json", "{\"id\":", "{\"id\":123,\"checkout_url\":\"https://www.mercadopago.com.br/pay\",\"status\":\"created\"}"})
    void shouldRejectAbsentMalformedOrStructurallyInvalidResponse(String response) {
        responseBody = response;
        assertThat(failure().getKind()).isEqualTo(Kind.INVALID_RESPONSE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://www.mercadopago.com.br/pay", "/pay", "https:///pay", "https://evil.test/pay",
            "https://www.mercadopago.com.br.evil.test/pay", "https://user:password@www.mercadopago.com.br/pay",
            "https://www.mercadopago.com.br/pay#secret", "https://www.mercadopago.com.br:8443/pay"})
    void shouldRejectUnsafeCheckoutDestination(String url) {
        Map<String, Object> response = validResponse();
        response.put("checkout_url", url);
        responseBody = jsonMapper.writeValueAsString(response);
        assertThat(failure().getKind()).isEqualTo(Kind.INVALID_RESPONSE);
    }

    @ParameterizedTest
    @CsvSource({"currency,USD", "currency,''", "total_amount,530.13", "total_amount,-530.12",
            "total_amount,NaN", "total_amount,1e2", "external_reference,different-attempt"})
    void shouldRejectReturnedFinancialOrCommercialMismatch(String field, String value) {
        Map<String, Object> response = validResponse();
        response.put(field, value);
        responseBody = jsonMapper.writeValueAsString(response);
        assertThat(failure().getKind()).isEqualTo(Kind.INVALID_RESPONSE);
    }

    @Test
    void shouldAcceptFinancialFieldsWhenAbsentWithoutInventingThem() {
        Map<String, Object> response = validResponse();
        response.remove("total_amount");
        response.remove("currency");
        response.remove("external_reference");
        responseBody = jsonMapper.writeValueAsString(response);
        assertThat(gateway.validateSeller().createCheckout(charge, attempt.getAttemptId()).providerOrderId()).isEqualTo(ORDER_ID);
    }

    @ParameterizedTest
    @CsvSource({"400,REQUEST_REJECTED,false", "401,AUTHORIZATION,false", "403,AUTHORIZATION,false",
            "409,IDEMPOTENCY_CONFLICT,true", "422,REQUEST_REJECTED,false", "429,RATE_LIMIT,true",
            "408,TIMEOUT,true", "500,PROVIDER_FAILURE,true", "503,PROVIDER_FAILURE,true", "404,OTHER_REJECTION,false"})
    void shouldClassifyHttpFailuresWithoutStoringPrivateErrorBodyOrRetrying(int status, Kind kind, boolean unknown) {
        responseStatus = status;
        responseBody = "{\"message\":\"private@example.test Bearer fake-private-secret\"}";
        CheckoutCreationException failure = failure();
        assertThat(failure.getKind()).isEqualTo(kind);
        assertThat(failure.getHttpStatus()).isEqualTo(status);
        assertThat(failure.isResultUnknown()).isEqualTo(unknown);
        assertThat(failure.getCause()).isInstanceOf(RestClientResponseException.class);
        RestClientResponseException cause = (RestClientResponseException) failure.getCause();
        assertThat(cause.getResponseBodyAsString()).isEmpty();
        assertThat(cause.getResponseHeaders()).isNull();
        assertThat(failure.toString()).doesNotContain("private", TOKEN);
        assertThat(received).hasSize(1);
    }

    @Test
    void shouldNotFollowRedirectOrForwardCredentialsToLocation() {
        responseStatus = 302;
        responseBody = "";
        assertThat(failure().getKind()).isEqualTo(Kind.INVALID_RESPONSE);
        assertThat(received).hasSize(1);
    }

    @Test
    void shouldReportTimeoutAsUnknownAndPreserveTechnicalCause() throws Exception {
        httpClient.close();
        gateway = gateway("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofMillis(100), true);
        delayMillis = 600;
        CheckoutCreationException failure = failure();
        assertThat(failure.getKind()).isEqualTo(Kind.TIMEOUT);
        assertThat(failure.isResultUnknown()).isTrue();
        assertThat(failure.getCause()).isInstanceOf(ResourceAccessException.class);
        assertThat(failure.getCause().getStackTrace()).isNotEmpty();
        assertThat(received.poll(3, TimeUnit.SECONDS)).isNotNull();
        assertThat(received).isEmpty();
    }

    @Test
    void shouldReportConnectionFailureAsUnknownWithoutChangingAttempt() throws IOException {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) { unusedPort = socket.getLocalPort(); }
        httpClient.close();
        gateway = gateway("http://127.0.0.1:" + unusedPort, Duration.ofMillis(500), true);
        CheckoutCreationException failure = failure();
        assertThat(failure.getKind()).isEqualTo(Kind.COMMUNICATION);
        assertThat(failure.isResultUnknown()).isTrue();
        assertThat(failure.getCause()).isInstanceOf(ResourceAccessException.class);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
    }

    @Test
    void shouldMakeNoRequestWhenDisabledOrInputIsInvalid() {
        MercadoPagoProperties disabled = new MercadoPagoProperties(false, null, null, null, null, null);
        MercadoPagoCheckoutGateway disabledGateway = new MercadoPagoCheckoutGateway(RestClient.create(), new MercadoPagoOrderMapper(), disabled, new MercadoPagoSellerIdentityVerifier(RestClient.create(), disabled));
        assertThatThrownBy(() -> disabledGateway.validateSeller())
                .isInstanceOfSatisfying(CheckoutCreationException.class, exception -> assertThat(exception.getKind()).isEqualTo(Kind.DISABLED));
        assertThatThrownBy(() -> gateway.validateSeller().createCheckout(null, attempt.getAttemptId())).isInstanceOf(InvalidPaymentDataException.class);
        assertThatThrownBy(() -> gateway.validateSeller().createCheckout(charge, UUID.randomUUID())).isInstanceOf(InvalidPaymentDataException.class);
        charge.markAttemptUnknown(attempt.getAttemptId());
        assertThatThrownBy(() -> gateway.validateSeller().createCheckout(charge, attempt.getAttemptId())).isInstanceOf(InvalidPaymentDataException.class);
        assertThat(received).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"BRL,530.123", "USD,530.12"})
    void shouldRejectHistoricalMoneyOutsidePaymentPolicyBeforeNetworkCall(String currency, String amount) {
        Charge legacy = Charge.restore(charge.getChargeId(), charge.getServiceOrderId(), charge.getBudgetId(), new BigDecimal(amount),
                Currency.getInstance(currency), charge.getStatus(), charge.getCreatedAt(), charge.getUpdatedAt(), charge.getAttempts());
        assertThatThrownBy(() -> gateway.validateSeller().createCheckout(legacy, attempt.getAttemptId())).isInstanceOf(InvalidPaymentDataException.class);
        assertThat(received).isEmpty();
    }

    @Test
    void shouldNotExposeCredentialsOrPrivateResponseAtTraceLogging(CapturedOutput output) {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.springframework.web.client");
        ch.qos.logback.classic.Level previous = logger.getLevel();
        try {
            logger.setLevel(ch.qos.logback.classic.Level.TRACE);
            Map<String, Object> response = validResponse();
            response.put("client_token", "private-client-token");
            response.put("payer", Map.of("email", "private@example.test"));
            responseBody = jsonMapper.writeValueAsString(response);
            gateway.validateSeller().createCheckout(charge, attempt.getAttemptId());
            responseStatus = 400;
            responseBody = "private-error-body " + TOKEN;
            failure();
            assertThat(output.getAll()).doesNotContain(TOKEN, "private-client-token", "private@example.test", "private-error-body");
        } finally {
            logger.setLevel(previous);
        }
    }

    private MercadoPagoCheckoutGateway gateway(String baseUrl, Duration readTimeout, boolean enabled) {
        MercadoPagoProperties properties = new MercadoPagoProperties(enabled, "TEST", TOKEN, Duration.ofMillis(500), readTimeout, "1234567890123");
        httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        RestClient client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        RestClient identityClient = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).requestFactory(factory).build();
        return new MercadoPagoCheckoutGateway(client, new MercadoPagoOrderMapper(), properties, new MercadoPagoSellerIdentityVerifier(identityClient, properties));
    }

    private CheckoutCreationException failure() {
        CheckoutCreationException exception = catchThrowableOfType(CheckoutCreationException.class, () -> gateway.validateSeller().createCheckout(charge, attempt.getAttemptId()));
        assertThat(exception).as("Falha tipada esperada na criação externa de checkout").isNotNull();
        return exception;
    }

    private Map<String, Object> validResponse() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", ORDER_ID);
        response.put("checkout_url", CHECKOUT_URL);
        response.put("status", "created");
        response.put("currency", "BRL");
        response.put("total_amount", "530.12");
        response.put("external_reference", attempt.getAttemptId().toString());
        return response;
    }

    private void respond(HttpExchange exchange) throws IOException {
        received.add(new ReceivedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestHeaders().getFirst("X-Idempotency-Key"),
                exchange.getRequestHeaders().getFirst("Content-Type"), exchange.getRequestHeaders().getFirst("Accept"),
                new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
        try {
            if (delayMillis > 0) { Thread.sleep(delayMillis); }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-Private", "private-response-header");
            if (responseStatus == 302) { exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/redirected"); }
            byte[] body = responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private record ReceivedRequest(String method, String path, String authorization, String key, String contentType, String accept, String body) {
    }
}
