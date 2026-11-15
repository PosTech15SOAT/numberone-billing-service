package br.com.fiap.numberone.billing.payment.infrastructure.persistence;

import br.com.fiap.numberone.billing.payment.application.gateways.ChargeGateway;
import br.com.fiap.numberone.billing.payment.application.results.CheckoutResult;
import br.com.fiap.numberone.billing.payment.application.usecases.CreateCheckoutUseCase;
import br.com.fiap.numberone.billing.payment.domain.entities.Charge;
import br.com.fiap.numberone.billing.payment.domain.exceptions.PaymentIdempotencyConflictException;
import br.com.fiap.numberone.billing.payment.domain.enums.PaymentAttemptStatus;
import br.com.fiap.numberone.billing.payment.domain.references.ServiceOrderPaymentContext;
import br.com.fiap.numberone.billing.payment.infrastructure.config.MercadoPagoProperties;
import br.com.fiap.numberone.billing.payment.infrastructure.rest.MercadoPagoCheckoutGateway;
import br.com.fiap.numberone.billing.payment.infrastructure.rest.MercadoPagoOrderMapper;
import br.com.fiap.numberone.billing.payment.infrastructure.rest.MercadoPagoSellerIdentityVerifier;
import br.com.fiap.numberone.billing.payment.infrastructure.rest.SellerIdentityVerificationException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = {"billing.notifications.enabled=false", "billing.outbox.enabled=false",
        "billing.payment.mercado-pago.enabled=false", "billing.integrations.os-base-url=http://os.test", "billing.integrations.inventory-base-url=http://inventory.test"})
class VerifiedCheckoutIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired private ChargeGateway charges;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private HttpServer server;
    private HttpClient client;
    private ExecutorService serverExecutor;
    private CreateCheckoutUseCase useCase;
    private MercadoPagoCheckoutGateway checkout;
    private final UUID serviceOrderId = UUID.randomUUID();
    private final UUID budgetId = UUID.randomUUID();
    private final UUID idempotencyKey = UUID.randomUUID();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private volatile int identityStatus = 200;
    private volatile String sellerId = "1234567890123";
    private volatile long identityDelay;
    private volatile long postDelay;
    private volatile int existingChargesBeforeIdentity;
    private volatile CyclicBarrier identityBarrier;
    private Runnable beforeIdentityResponse = () -> {};
    private static final String TOKEN = "APP_USR-fictitious-only";

    @BeforeEach void setUp() throws Exception {
        jdbc.update("""
                INSERT INTO budget (budget_id,service_order_id,budget_version,quoted_amount,approved_amount,status,created_at)
                VALUES (?,?,1,10.00,10.00,'APPROVED',CURRENT_TIMESTAMP)
                """, budgetId, serviceOrderId);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                requests.add(exchange.getRequestMethod() + " " + path);
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer " + TOKEN);
                String response;
                int status;
                if (path.equals("/users/me")) {
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM charge WHERE service_order_id=?", Integer.class, serviceOrderId)).isEqualTo(existingChargesBeforeIdentity);
                    if (identityBarrier != null) identityBarrier.await(10, TimeUnit.SECONDS);
                    beforeIdentityResponse.run();
                    if (identityDelay > 0) Thread.sleep(identityDelay);
                    response = "{\"id\":" + sellerId + ",\"email\":\"private@example.test\",\"access_token\":\"private-response-token\"}";
                    status = identityStatus;
                } else {
                    Map<String, Object> attempt = attemptRow();
                    assertThat(attempt.get("checkout_creation_claimed_at")).isNotNull();
                    assertThat(attempt.get("idempotency_key")).isEqualTo(idempotencyKey);
                    assertThat(attempt.get("status")).isEqualTo("PENDING");
                    assertThat(path).isEqualTo("/v1/orders");
                    assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                    assertThat(exchange.getRequestHeaders().getFirst("X-Idempotency-Key")).isEqualTo(idempotencyKey.toString());
                    JsonNode sent = JsonMapper.builder().build().readTree(exchange.getRequestBody().readAllBytes());
                    assertThat(sent.path("total_amount").asText()).isEqualTo("10.00");
                    assertThat(sent.path("external_reference").asText()).isEqualTo(attempt.get("attempt_id").toString());
                    if (postDelay > 0) Thread.sleep(postDelay);
                    response = "{\"id\":\"ORD" + attempt.get("attempt_id") + "\",\"checkout_url\":\"https://www.mercadopago.com.br/checkout/v1/redirect?private-link\",\"status\":\"created\",\"currency\":\"BRL\",\"total_amount\":\"10.00\",\"external_reference\":\"" + attempt.get("attempt_id") + "\"}";
                    status = 201;
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (java.io.IOException ignored) { /* Timeout deliberado fecha a conexão antes da resposta. */ }
            catch (Throwable failure) { serverFailure.compareAndSet(null, failure); }
            finally { exchange.close(); }
        });
        server.start();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofMillis(500));
        RestClient simulated = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).requestFactory(factory).build();
        MercadoPagoProperties properties = new MercadoPagoProperties(true, "TEST", TOKEN, null, null, "1234567890123");
        checkout = new MercadoPagoCheckoutGateway(simulated, new MercadoPagoOrderMapper(), properties, new MercadoPagoSellerIdentityVerifier(simulated, properties));
        useCase = new CreateCheckoutUseCase(serviceOrderId -> new ServiceOrderPaymentContext(serviceOrderId, "COMPLETED"), charges, checkout);
    }
    @AfterEach void close() { server.stop(0); serverExecutor.shutdownNow(); client.close(); }

    @Test void shouldValidateBeforeReservationAndCommitBeforePostAndReplayLocally(CapturedOutput output) {
        CheckoutResult result = execute();
        assertThat(result.checkoutUrl()).isNotNull();
        assertThat(result.status()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(requests).containsExactly("GET /users/me", "POST /v1/orders");
        Map<String, Object> beforeReplay = attemptRow();
        assertThat(execute()).isEqualTo(result);
        assertThat(attemptRow()).isEqualTo(beforeReplay);
        assertThat(requests).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM integration_event_outbox WHERE charge_id=?", Integer.class, result.chargeId())).isZero();
        assertThat(serverFailure.get()).isNull();
        assertThat(output.getAll()).doesNotContain(TOKEN, "private@example.test", "private-response-token", "private-link", idempotencyKey.toString());
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 401, 403, 500})
    void shouldNeverReserveOrPostAfterIdentityFailureAndPermitFreshValidationWithSameKey(int status) {
        identityStatus = status;
        sellerId = "1234567890124";
        assertThatThrownBy(this::execute).isInstanceOf(SellerIdentityVerificationException.class);
        assertNoChargeOrAttempt();
        assertThat(requests).containsExactly("GET /users/me");
        identityStatus = 200;
        sellerId = "1234567890123";
        CheckoutResult result = execute();
        assertThat(result.status()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(attemptRow().get("idempotency_key")).isEqualTo(idempotencyKey);
        assertThat(requests).containsExactly("GET /users/me", "GET /users/me", "POST /v1/orders");
        assertThat(serverFailure.get()).isNull();
    }

    @Test void shouldNotReserveAfterIdentityTimeoutOrRetryAutomatically() {
        identityDelay = 1500;
        assertThatThrownBy(this::execute).isInstanceOfSatisfying(SellerIdentityVerificationException.class,
                failure -> assertThat(failure.getReason()).isEqualTo(SellerIdentityVerificationException.Reason.TIMEOUT));
        assertNoChargeOrAttempt();
        assertThat(requests).containsExactly("GET /users/me");
    }

    @Test void shouldAllowConcurrentValidationsButOnlyReservationOwnerToPost() throws Exception {
        identityDelay = 100;
        identityBarrier = new CyclicBarrier(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<CheckoutResult> request = () -> { barrier.await(10, TimeUnit.SECONDS); return execute(); };
            List<java.util.concurrent.Future<CheckoutResult>> results = executor.invokeAll(List.of(request, request), 20, TimeUnit.SECONDS);
            CheckoutResult first = results.getFirst().get();
            CheckoutResult second = results.getLast().get();
            assertThat(first.attemptId()).isEqualTo(second.attemptId());
        }
        assertThat(Collections.frequency(requests, "POST /v1/orders")).isEqualTo(1);
        assertThat(Collections.frequency(requests, "GET /users/me")).isEqualTo(2);
        assertThat(serverFailure.get()).isNull();
    }

    @Test void shouldBlockIdentityAndPostInsideCallerTransaction() {
        charges.prepareCheckout(new ServiceOrderPaymentContext(serviceOrderId, "COMPLETED"), idempotencyKey);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> checkout.validateSeller()))
                .isInstanceOfSatisfying(SellerIdentityVerificationException.class, failure -> assertThat(failure.getReason()).isEqualTo(SellerIdentityVerificationException.Reason.ACTIVE_TRANSACTION));
        assertThat(requests).isEmpty();
    }

    @Test void shouldPreserveExistingChargeWithoutAttemptWhenIdentityFails() {
        Charge charge = charges.createCharge(
                new ServiceOrderPaymentContext(serviceOrderId, "COMPLETED"), budgetId, Currency.getInstance("BRL"));
        existingChargesBeforeIdentity = 1;
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM charge WHERE charge_id=?", charge.getChargeId());
        identityStatus = 401;
        assertThatThrownBy(this::execute).isInstanceOf(SellerIdentityVerificationException.class);
        assertThat(jdbc.queryForMap("SELECT * FROM charge WHERE charge_id=?", charge.getChargeId())).isEqualTo(before);
        assertThat(charges.findById(charge.getChargeId()).orElseThrow().getAttempts()).isEmpty();
        assertThat(requests).containsExactly("GET /users/me");
        assertThat(serverFailure.get()).isNull();
    }

    @Test void shouldRevalidateLocalAmountAfterIdentityAndRejectReservationWithoutPost() {
        Charge charge = charges.createCharge(
                new ServiceOrderPaymentContext(serviceOrderId, "COMPLETED"), budgetId, Currency.getInstance("BRL"));
        existingChargesBeforeIdentity = 1;
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM charge WHERE charge_id=?", charge.getChargeId());
        beforeIdentityResponse = () -> jdbc.update("UPDATE budget SET approved_amount=11.00 WHERE budget_id=?", budgetId);
        assertThatThrownBy(this::execute).isInstanceOf(PaymentIdempotencyConflictException.class);
        assertThat(jdbc.queryForMap("SELECT * FROM charge WHERE charge_id=?", charge.getChargeId())).isEqualTo(before);
        assertThat(charges.findById(charge.getChargeId()).orElseThrow().getAttempts()).isEmpty();
        assertThat(requests).containsExactly("GET /users/me");
        assertThat(serverFailure.get()).isNull();
    }

    @Test void shouldKeepPostTimeoutUnknownAndReplayOriginalIdentityWithoutAnyHttp() {
        postDelay = 1500;
        CheckoutResult result = execute();
        assertThat(result.status()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(result.checkoutUrl()).isNull();
        Map<String, Object> beforeReplay = attemptRow();
        assertThat(beforeReplay.get("attempt_id")).isEqualTo(result.attemptId());
        assertThat(beforeReplay.get("idempotency_key")).isEqualTo(idempotencyKey);
        assertThat(beforeReplay.get("checkout_creation_claimed_at")).isNotNull();
        identityStatus = 401;
        assertThat(execute()).isEqualTo(result);
        assertThat(attemptRow()).isEqualTo(beforeReplay);
        assertThat(requests).containsExactly("GET /users/me", "POST /v1/orders");
        assertThat(serverFailure.get()).isNull();
    }

    private void assertNoChargeOrAttempt() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM charge WHERE service_order_id=?", Integer.class, serviceOrderId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_attempt WHERE idempotency_key=?", Integer.class, idempotencyKey)).isZero();
    }

    private CheckoutResult execute() { return useCase.execute(serviceOrderId, idempotencyKey); }
    private Map<String, Object> attemptRow() { return jdbc.queryForMap("SELECT * FROM payment_attempt WHERE charge_id IN (SELECT charge_id FROM charge WHERE service_order_id=?)", serviceOrderId); }
}
