package br.com.fiap.numberone.billing.payment.infrastructure.persistence;

import br.com.fiap.numberone.billing.payment.api.security.OrderWebhookRequestFilter;
import br.com.fiap.numberone.billing.payment.application.gateways.ChargeGateway;
import br.com.fiap.numberone.billing.payment.application.gateways.OrderLookupGateway;
import br.com.fiap.numberone.billing.payment.application.gateways.CheckoutGateway;
import br.com.fiap.numberone.billing.payment.application.results.CheckoutOrder;
import br.com.fiap.numberone.billing.payment.application.results.PreparedCheckout;
import br.com.fiap.numberone.billing.payment.application.usecases.CreateCheckoutUseCase;
import br.com.fiap.numberone.billing.payment.domain.references.ServiceOrderPaymentContext;
import br.com.fiap.numberone.billing.payment.infrastructure.config.MercadoPagoProperties;
import br.com.fiap.numberone.billing.payment.infrastructure.rest.MercadoPagoOrderDetailsMapper;
import br.com.fiap.numberone.billing.payment.infrastructure.rest.MercadoPagoOrderLookupGateway;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static br.com.fiap.numberone.billing.payment.support.MercadoPagoWebhookFixtures.signature;
import static br.com.fiap.numberone.billing.payment.support.MercadoPagoWebhookFixtures.officialBody;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@ActiveProfiles("local")
@SpringBootTest(properties = {"billing.notifications.enabled=false", "billing.outbox.enabled=false", "billing.payment.mercado-pago.enabled=false",
        "billing.integrations.os-base-url=http://os.test", "billing.integrations.inventory-base-url=http://inventory.test",
        "billing.payment.mercado-pago-webhook.enabled=true", "billing.payment.mercado-pago-webhook.environment=TEST",
        "billing.payment.mercado-pago-webhook.secret=fictitious-webhook-secret"})
class OrderWebhookObservationIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private WebApplicationContext context;
    @Autowired private ChargeGateway charges;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderWebhookRequestFilter filter;
    @MockitoBean private OrderLookupGateway orders;
    private HttpServer server;
    private HttpClient client;
    private MockMvc mvc;
    private String official;
    private volatile int orderStatus = 200;
    private volatile long orderDelay;
    private volatile int identityStatus = 200;
    private volatile String sellerResponse = "{\"id\":1234567890123}";
    private volatile long identityDelay;
    private final AtomicInteger identities = new AtomicInteger();
    private final AtomicInteger requests = new AtomicInteger();
    private PreparedCheckout prepared;
    private String providerOrderId;
    private volatile String queriedOrderPath;

    @BeforeEach void setUp() throws Exception {
        UUID serviceOrderId = UUID.randomUUID();
        jdbc.update("INSERT INTO budget (budget_id,service_order_id,budget_version,quoted_amount,approved_amount,status,created_at) VALUES (?,?,1,10.00,10.00,'APPROVED',CURRENT_TIMESTAMP)", UUID.randomUUID(), serviceOrderId);
        prepared = charges.prepareCheckout(new ServiceOrderPaymentContext(serviceOrderId, "COMPLETED"), UUID.randomUUID());
        providerOrderId = "ORD" + UUID.randomUUID().toString().replace("-", "");
        charges.associateProviderOrder(prepared.charge().getChargeId(), prepared.attemptId(), providerOrderId, "https://checkout.example.test/known");
        official = officialBody(providerOrderId, prepared.attemptId().toString(), "processed", "accredited");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/orders/", exchange -> {
            requests.incrementAndGet();
            queriedOrderPath = exchange.getRequestURI().getPath();
            try {
                if (orderDelay > 0) Thread.sleep(orderDelay);
                byte[] body = official.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(orderStatus, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException ignored) { /* Timeout deliberado. */ }
            finally { exchange.close(); }
        });
        server.createContext("/users/me", exchange -> {
            identities.incrementAndGet();
            try {
                if (identityDelay > 0) Thread.sleep(identityDelay);
                byte[] bytes = sellerResponse.getBytes(StandardCharsets.UTF_8);
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
        factory.setReadTimeout(Duration.ofSeconds(1));
        MercadoPagoOrderLookupGateway simulatedTransport = new MercadoPagoOrderLookupGateway(RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).requestFactory(factory).build(),
                new MercadoPagoProperties(true, "TEST", "TEST-fictitious-only", null, null, "1234567890123"), new MercadoPagoOrderDetailsMapper(),
                new br.com.fiap.numberone.billing.payment.infrastructure.rest.MercadoPagoSellerIdentityVerifier(RestClient.builder()
                        .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).requestFactory(factory).build(),
                        new MercadoPagoProperties(true, "TEST", "TEST-fictitious-only", null, null, "1234567890123")));
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return simulatedTransport.findById(invocation.getArgument(0));
        }).when(orders).findById(any());
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
    }

    @AfterEach void close() { server.stop(0); client.close(); }

    @ParameterizedTest
    @CsvSource({"created,created,PENDING", "processed,accredited,PENDING", "failed,rejected_by_issuer,PENDING", "processed,accredited,UNKNOWN",
            "failed,rejected_by_issuer,UNKNOWN", "processed,accredited,PAID", "failed,rejected_by_issuer,PAID"})
    void shouldPreserveWholeAggregateAndBudgetAfterDuplicateNotification(String status, String detail, String initialState) throws Exception {
        if (initialState.equals("UNKNOWN")) charges.markAttemptUnknown(prepared.charge().getChargeId(), prepared.attemptId());
        // Quitação prévia com a mesma evidência para verificar replay idempotente.
        String paymentId = "PAY" + prepared.attemptId();
        if (initialState.equals("PAID")) charges.confirmPayment(br.com.fiap.numberone.billing.payment.support.PaymentConfirmationFixtures.approved(charges.findById(prepared.charge().getChargeId()).orElseThrow(), prepared.attemptId(), paymentId));
        official = officialBody(providerOrderId, prepared.attemptId().toString(), status, detail).replace("PAY01ABC", paymentId);
        Map<String, Object> chargeBefore = chargeRow();
        List<Map<String, Object>> attemptsBefore = attemptRows();
        List<Map<String, Object>> budgetBefore = budgetRows();
        mvc.perform(notification(providerOrderId, "order.processed")).andExpect(status().isOk()).andExpect(content().string(""));
        Map<String, Object> afterFirst = chargeRow();
        List<Map<String, Object>> attemptsAfterFirst = attemptRows();
        mvc.perform(notification(providerOrderId, "order.processed")).andExpect(status().isOk()).andExpect(content().string(""));
        assertThat(chargeRow()).isEqualTo(afterFirst);
        assertThat(attemptRows()).isEqualTo(attemptsAfterFirst);
        if (status.equals("processed")) {
            assertThat(afterFirst.get("status")).isEqualTo("PAID");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM integration_event_outbox WHERE charge_id=?", Integer.class, prepared.charge().getChargeId())).isEqualTo(1);
        } else {
            assertThat(chargeRow()).isEqualTo(chargeBefore);
            assertThat(attemptRows()).isEqualTo(attemptsBefore);
        }
        assertThat(budgetRows()).isEqualTo(budgetBefore);
        assertThat(requests).hasValue(2);
    }

    @Test
    void shouldRecordOfficialRefundEvenWhenNotificationIsOutOfOrder() throws Exception {
        mvc.perform(notification(providerOrderId, "order.processed")).andExpect(status().isOk());
        Map<String, Object> before = chargeRow();
        List<Map<String, Object>> attemptsBefore = attemptRows();
        assertThat(before.get("status")).isEqualTo("PAID");
        official = officialBody(providerOrderId, prepared.attemptId().toString(), "refunded", "refunded")
                .replace("\"processed\"", "\"refunded\"").replace("\"accredited\"", "\"refunded\"");
        mvc.perform(notification(providerOrderId, "order.created")).andExpect(status().isOk());
        assertThat(chargeRow().get("status")).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForList("SELECT event_type FROM integration_event_outbox WHERE charge_id=?", String.class,
                prepared.charge().getChargeId())).containsExactlyInAnyOrder("PagamentoConfirmado", "PagamentoAtualizado");
        assertThat(attemptRows()).isEqualTo(attemptsBefore);
        assertThat(requests).hasValue(2);
    }

    @Test
    void shouldRejectDivergentOfficialReferenceWithoutChangingLocalRows() throws Exception {
        official = officialBody(providerOrderId, UUID.randomUUID().toString(), "processed", "accredited");
        Map<String, Object> before = chargeRow();
        List<Map<String, Object>> attemptsBefore = attemptRows();
        mvc.perform(notification(providerOrderId, "order.processed")).andExpect(status().isBadGateway());
        assertThat(chargeRow()).isEqualTo(before);
        assertThat(attemptRows()).isEqualTo(attemptsBefore);
    }

    @Test
    void shouldDeferEarlyWebhookAndConfirmRedeliveryWithoutRecreatingCheckout() throws Exception {
        UUID serviceOrderId = UUID.randomUUID();
        jdbc.update("INSERT INTO budget (budget_id,service_order_id,budget_version,quoted_amount,approved_amount,status,created_at) VALUES (?,?,1,10.00,10.00,'APPROVED',CURRENT_TIMESTAMP)", UUID.randomUUID(), serviceOrderId);
        String earlyOrderId = "ORDearly" + UUID.randomUUID().toString().replace("-", "");
        AtomicInteger creations = new AtomicInteger();
        CheckoutGateway checkout = () -> (charge, attemptId) -> {
            creations.incrementAndGet();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(charges.findByProviderOrderId(earlyOrderId)).isEmpty();
            official = officialBody(earlyOrderId, attemptId.toString(), "processed", "accredited")
                    .replace("PAY01ABC", "PAY" + attemptId);
            try {
                mvc.perform(notification(earlyOrderId, "order.processed")).andExpect(status().isServiceUnavailable())
                        .andExpect(content().string(""));
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
            verifyNoInteractions(orders);
            return new CheckoutOrder(earlyOrderId, URI.create("https://checkout.example.test/early"), "created");
        };
        CreateCheckoutUseCase create = new CreateCheckoutUseCase(
                id -> new ServiceOrderPaymentContext(id, "COMPLETED"), charges, checkout);
        UUID key = UUID.randomUUID();

        var result = create.execute(serviceOrderId, key);
        var associated = charges.findByProviderOrderId(earlyOrderId).orElseThrow();
        var originalAttempt = associated.getAttempts().getFirst();
        assertThat(originalAttempt.getIdempotencyKey()).isEqualTo(key);
        assertThat(associated.getStatus().name()).isEqualTo("OPEN");
        assertThat(associated.getAttempts().getFirst().getStatus().name()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM integration_event_outbox WHERE charge_id=?", Integer.class,
                result.chargeId())).isZero();
        assertThat(create.execute(serviceOrderId, key)).isEqualTo(result);
        assertThat(creations).hasValue(1);
        verifyNoInteractions(orders);

        // Só uma nova entrega explícita permite confirmar após a associação local.
        mvc.perform(notification(earlyOrderId, "order.processed")).andExpect(status().isOk());
        var paid = charges.findByProviderOrderId(earlyOrderId).orElseThrow();
        assertThat(paid.getStatus().name()).isEqualTo("PAID");
        var paidAttempt = paid.getAttempts().getFirst();
        assertThat(paidAttempt.getAttemptId()).isEqualTo(originalAttempt.getAttemptId());
        assertThat(paidAttempt.getIdempotencyKey()).isEqualTo(key);
        assertThat(paidAttempt.getCheckoutCreationClaimedAt()).isEqualTo(originalAttempt.getCheckoutCreationClaimedAt());
        assertThat(paidAttempt.getProviderOrderId()).isEqualTo(earlyOrderId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM integration_event_outbox WHERE charge_id=?", Integer.class,
                result.chargeId())).isEqualTo(1);
        mvc.perform(notification(earlyOrderId, "order.processed")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM integration_event_outbox WHERE charge_id=?", Integer.class,
                result.chargeId())).isEqualTo(1);
        assertThat(creations).hasValue(1);
    }

    @Test
    void shouldDeferRepeatedUnknownOrderWithoutCreatingOrChangingLocalStateOrQueryingProvider() throws Exception {
        int count = jdbc.queryForObject("SELECT count(*) FROM charge", Integer.class);
        int attempts = jdbc.queryForObject("SELECT count(*) FROM payment_attempt", Integer.class);
        int events = jdbc.queryForObject("SELECT count(*) FROM integration_event_outbox", Integer.class);
        var chargeBefore = chargeRow();
        var attemptsBefore = attemptRows();
        for (int index = 0; index < 2; index++) {
            mvc.perform(notification("ORDunknown", "order.processed")).andExpect(status().isServiceUnavailable())
                    .andExpect(content().string(""));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM charge", Integer.class)).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_attempt", Integer.class)).isEqualTo(attempts);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM integration_event_outbox", Integer.class)).isEqualTo(events);
        assertThat(chargeRow()).isEqualTo(chargeBefore);
        assertThat(attemptRows()).isEqualTo(attemptsBefore);
        verifyNoInteractions(orders);
        assertThat(requests).hasValue(0);
    }

    @Test
    void shouldRejectInvalidSignatureBeforeAnyProviderLookupAndKeepState() throws Exception {
        Map<String, Object> before = chargeRow();
        mvc.perform(notification(providerOrderId, "order.processed").header("x-signature", "duplicate")).andExpect(status().isUnauthorized());
        assertThat(chargeRow()).isEqualTo(before);
        verifyNoInteractions(orders);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void shouldConfirmSanitizedInstallmentFixtureAndPreserveDuplicateAndOutOfOrderReplay(
            boolean unknown, boolean lowercaseSignature) throws Exception {
        if (unknown) charges.markAttemptUnknown(prepared.charge().getChargeId(), prepared.attemptId());
        try (java.io.InputStream resource = getClass().getResourceAsStream("/payment/checkout-pro-paid-installments.json")) {
            official = new String(java.util.Objects.requireNonNull(resource).readAllBytes(), StandardCharsets.UTF_8)
                    .replace("ORDTSTfixtureInstallments", providerOrderId)
                    .replace("10000000-0000-4000-8000-000000000001", prepared.attemptId().toString())
                    .replace("PAYfixtureInstallments", "PAY" + prepared.attemptId());
        }
        List<Map<String, Object>> budgetBefore = budgetRows();
        List<Map<String, Object>> attemptsBefore = attemptRows();
        mvc.perform(notification(providerOrderId, "order.processed", lowercaseSignature)).andExpect(status().isOk());
        Map<String, Object> paid = chargeRow();
        List<Map<String, Object>> attempts = attemptRows();
        assertThat(paid.get("status")).isEqualTo("PAID");
        assertThat((java.math.BigDecimal) paid.get("amount")).isEqualByComparingTo("10.00");
        assertThat(attempts.getFirst().get("status")).isEqualTo("PAID");
        for (String field : List.of("attempt_id", "idempotency_key", "checkout_creation_claimed_at", "provider_order_id"))
            assertThat(attempts.getFirst().get(field)).isEqualTo(attemptsBefore.getFirst().get(field));
        List<Map<String, Object>> events = jdbc.queryForList("SELECT * FROM integration_event_outbox WHERE charge_id=?", prepared.charge().getChargeId());
        assertThat(events).hasSize(1);
        mvc.perform(notification(providerOrderId, "order.created", lowercaseSignature)).andExpect(status().isOk());
        assertThat(chargeRow()).isEqualTo(paid);
        assertThat(attemptRows()).isEqualTo(attempts);
        assertThat(jdbc.queryForList("SELECT * FROM integration_event_outbox WHERE charge_id=?", prepared.charge().getChargeId())).isEqualTo(events);
        assertThat(budgetRows()).isEqualTo(budgetBefore);
        assertThat(identities).hasValue(2);
        assertThat(requests).hasValue(2);
        assertThat(queriedOrderPath).isEqualTo("/v1/orders/" + providerOrderId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTHORIZATION", "IDENTITY", "TIMEOUT", "INVALID_RESPONSE"})
    void shouldNeverConfirmOrEmitEventAfterIdentityFailure(String failure, CapturedOutput output) throws Exception {
        if (failure.equals("AUTHORIZATION")) identityStatus = 401;
        if (failure.equals("IDENTITY")) sellerResponse = "{\"id\":1234567890124}";
        if (failure.equals("TIMEOUT")) identityDelay = 2000;
        if (failure.equals("INVALID_RESPONSE")) sellerResponse = "private-identity-response";
        Map<String, Object> before = chargeRow();
        List<Map<String, Object>> attemptsBefore = attemptRows();
        List<Map<String, Object>> budgetBefore = budgetRows();
        int status = failure.equals("TIMEOUT") ? 504 : failure.equals("INVALID_RESPONSE") ? 502 : 503;
        mvc.perform(notification(providerOrderId, "order.processed")).andExpect(status().is(status));
        assertThat(chargeRow()).isEqualTo(before);
        assertThat(attemptRows()).isEqualTo(attemptsBefore);
        assertThat(budgetRows()).isEqualTo(budgetBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM integration_event_outbox WHERE charge_id=?", Integer.class, prepared.charge().getChargeId())).isZero();
        assertThat(identities).hasValue(1);
        assertThat(requests).hasValue(0);
        assertThat(output.getAll()).doesNotContain("TEST-fictitious-only", "private-identity-response");
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTHORIZATION", "PROVIDER_FAILURE", "TIMEOUT", "INVALID_RESPONSE"})
    void shouldNeverConfirmOrEmitEventAfterOrderLookupFailure(String failure, CapturedOutput output) throws Exception {
        if (failure.equals("AUTHORIZATION")) orderStatus = 401;
        if (failure.equals("PROVIDER_FAILURE")) orderStatus = 503;
        if (failure.equals("TIMEOUT")) orderDelay = 2000;
        if (failure.equals("INVALID_RESPONSE")) official = "private-order-response";
        Map<String, Object> before = chargeRow();
        List<Map<String, Object>> attemptsBefore = attemptRows();
        List<Map<String, Object>> budgetBefore = budgetRows();
        int status = failure.equals("TIMEOUT") ? 504 : failure.equals("INVALID_RESPONSE") ? 502 : 503;
        mvc.perform(notification(providerOrderId, "order.processed")).andExpect(status().is(status));
        assertThat(chargeRow()).isEqualTo(before);
        assertThat(attemptRows()).isEqualTo(attemptsBefore);
        assertThat(budgetRows()).isEqualTo(budgetBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM integration_event_outbox WHERE charge_id=?", Integer.class, prepared.charge().getChargeId())).isZero();
        assertThat(identities).hasValue(1);
        assertThat(requests).hasValue(1);
        assertThat(output.getAll()).doesNotContain("TEST-fictitious-only", "private-order-response");
    }

    private MockHttpServletRequestBuilder notification(String id, String action) {
        return notification(id, action, false);
    }
    private MockHttpServletRequestBuilder notification(String id, String action, boolean lowercaseSignature) {
        return post(OrderWebhookRequestFilter.PATH).queryParam("data.id", id).queryParam("type", "order")
                .header("x-request-id", "request-123").header("x-signature", signature(
                        lowercaseSignature ? id.toLowerCase(java.util.Locale.ROOT) : id, "request-123", "1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"order\",\"live_mode\":false,\"action\":\"" + action
                        + "\",\"data\":{\"id\":\"" + id + "\",\"status\":\"approved\",\"total_amount\":\"0.01\"}}");
    }
    private Map<String, Object> chargeRow() { return jdbc.queryForMap("SELECT * FROM charge WHERE charge_id=?", prepared.charge().getChargeId()); }
    private List<Map<String, Object>> attemptRows() { return jdbc.queryForList("SELECT * FROM payment_attempt WHERE charge_id=? ORDER BY attempt_id", prepared.charge().getChargeId()); }
    private List<Map<String, Object>> budgetRows() { return jdbc.queryForList("SELECT * FROM budget WHERE budget_id=?", prepared.charge().getBudgetId()); }
}
