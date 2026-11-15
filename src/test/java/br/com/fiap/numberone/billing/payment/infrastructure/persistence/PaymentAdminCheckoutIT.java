package br.com.fiap.numberone.billing.payment.infrastructure.persistence;

import br.com.fiap.numberone.billing.payment.application.exceptions.CheckoutCreationException;
import br.com.fiap.numberone.billing.payment.application.gateways.CheckoutGateway;
import br.com.fiap.numberone.billing.payment.application.results.CheckoutOrder;
import br.com.fiap.numberone.billing.payment.domain.entities.Charge;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(properties = {"spring.config.import=", "billing.outbox.enabled=false", "billing.notifications.enabled=false",
        "billing.payment.mercado-pago.enabled=false",
        "billing.integrations.os-base-url=http://os.test", "billing.integrations.inventory-base-url=http://inventory.test",
        "billing.payment.service-order.read-timeout=1s"})
class PaymentAdminCheckoutIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final AtomicInteger OS_REQUESTS = new AtomicInteger();
    private static final AtomicReference<String> OS_STATUS = new AtomicReference<>("COMPLETED");
    private static final AtomicReference<String> CANCELLATION = new AtomicReference<>("false");
    private static final AtomicReference<String> RECEIVED_AUTHORIZATION = new AtomicReference<>();
    private static final AtomicInteger OS_HTTP_STATUS = new AtomicInteger(200);
    private static final AtomicReference<String> RECEIVED_EMPLOYEE = new AtomicReference<>();
    private static final ExecutorService OS_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer OS_SERVER = startOsServer();
    private final JsonMapper json = new JsonMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("billing.integrations.os-base-url", () -> "http://127.0.0.1:" + OS_SERVER.getAddress().getPort());
    }

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CheckoutGateway checkout;
    private final CheckoutGateway.ValidatedCheckout validatedCheckout = mock(CheckoutGateway.ValidatedCheckout.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(checkout.validateSeller()).thenReturn(validatedCheckout);
        OS_REQUESTS.set(0);
        OS_STATUS.set("COMPLETED");
        CANCELLATION.set("false");
        RECEIVED_AUTHORIZATION.set(null);
        OS_HTTP_STATUS.set(200);
        RECEIVED_EMPLOYEE.set(null);
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Charge charge = invocation.getArgument(0);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_attempt WHERE charge_id=? AND checkout_creation_claimed_at IS NOT NULL",
                    Integer.class, charge.getChargeId())).isEqualTo(1);
            String id = "order-" + UUID.randomUUID();
            return new CheckoutOrder(id, URI.create("https://www.mercadopago.com.br/checkout/v1/redirect?pref_id=" + id), "created");
        }).when(validatedCheckout).createCheckout(any(), any());
    }

    @AfterAll
    static void stopOs() { OS_SERVER.stop(0); OS_EXECUTOR.close(); }

    @Test
    void shouldCreateThroughTrustedOsHttpAndReplayAndReadWithoutAnotherProviderPost() throws Exception {
        UUID orderId = approvedOrder();
        UUID key = UUID.randomUUID();
        String first = mvc.perform(post(path(orderId)).header("Idempotency-Key", key)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode created = json.readTree(first);
        Map<String, Object> before = snapshot(created.path("idCobranca").asText());
        mvc.perform(post(path(orderId)).header("Idempotency-Key", key)).andExpect(status().isOk()).andExpect(content().json(first));
        assertThat(snapshot(created.path("idCobranca").asText())).isEqualTo(before);
        assertThat(OS_REQUESTS).hasValue(1);
        mvc.perform(get(path(orderId))).andExpect(status().isOk()).andExpect(jsonPath("$.valor").value(530.12))
                .andExpect(jsonPath("$.checkout.idTentativa").value(created.path("idTentativa").asText()))
                .andExpect(jsonPath("$.checkout.urlCheckout").value(created.path("urlCheckout").asText()));
        assertThat(OS_REQUESTS).hasValue(1);
        assertThat(RECEIVED_EMPLOYEE.get()).isNull();
        assertThat(RECEIVED_AUTHORIZATION.get()).isNull();
        verify(validatedCheckout, times(1)).createCheckout(any(), any());
    }

    @Test
    void shouldReturnUnknownAndKeepOriginalAttemptAcrossHttpRequests() throws Exception {
        UUID orderId = approvedOrder();
        UUID key = UUID.randomUUID();
        doThrow(new CheckoutCreationException(CheckoutCreationException.Kind.TIMEOUT, null, true, null))
                .when(validatedCheckout).createCheckout(any(), any());
        String result = mvc.perform(post(path(orderId)).header("Idempotency-Key", key)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("UNKNOWN")).andReturn().getResponse().getContentAsString();
        mvc.perform(post(path(orderId)).header("Idempotency-Key", key)).andExpect(status().isAccepted()).andExpect(content().json(result));
        mvc.perform(get(path(orderId))).andExpect(status().isOk()).andExpect(jsonPath("$.checkout.situacao").value("RESULTADO_DESCONHECIDO"));
        mvc.perform(post(path(orderId)).header("Idempotency-Key", UUID.randomUUID())).andExpect(status().isConflict());
        verify(validatedCheckout, times(1)).createCheckout(any(), any());
    }

    @ParameterizedTest
    @CsvSource({"DISABLED,503,CHECKOUT_CONFIGURACAO_INDISPONIVEL", "AUTHORIZATION,503,CHECKOUT_CONFIGURACAO_INDISPONIVEL",
            "TIMEOUT,504,CHECKOUT_TIMEOUT", "COMMUNICATION,503,CHECKOUT_INDISPONIVEL",
            "RATE_LIMIT,503,CHECKOUT_INDISPONIVEL", "PROVIDER_FAILURE,503,CHECKOUT_INDISPONIVEL",
            "INVALID_RESPONSE,502,CHECKOUT_RESPOSTA_INVALIDA"})
    void shouldTranslatePreparationFailureWithoutPersistingChargeOrConsumingIdempotencyKey(
            CheckoutCreationException.Kind kind, int expectedStatus, String code) throws Exception {
        UUID orderId = approvedOrder();
        UUID key = UUID.randomUUID();
        int attemptsBefore = jdbc.queryForObject("SELECT count(*) FROM payment_attempt", Integer.class);
        when(checkout.validateSeller()).thenThrow(new CheckoutCreationException(kind, null, false, null));

        mvc.perform(post(path(orderId)).header("Idempotency-Key", key)).andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.status").value(expectedStatus)).andExpect(jsonPath("$.codigo").value(code))
                .andExpect(jsonPath("$.length()").value(3));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM charge WHERE service_order_id=?", Integer.class, orderId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_attempt", Integer.class)).isEqualTo(attemptsBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_attempt WHERE idempotency_key=?", Integer.class, key)).isZero();
        verifyNoInteractions(validatedCheckout);

        doReturn(validatedCheckout).when(checkout).validateSeller();
        mvc.perform(post(path(orderId)).header("Idempotency-Key", key)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_attempt WHERE idempotency_key=?", Integer.class, key)).isEqualTo(1);
        verify(validatedCheckout, times(1)).createCheckout(any(), any());
    }

    @Test
    void shouldMakeOneProviderCallForConcurrentHttpRequests() throws Exception {
        UUID orderId = approvedOrder();
        UUID key = UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown();
            assertThat(finish.await(15, TimeUnit.SECONDS)).isTrue();
            return new CheckoutOrder("concurrent-test", URI.create("https://www.mercadopago.com.br/checkout/v1/redirect?pref_id=concurrent"), "created");
        }).when(validatedCheckout).createCheckout(any(), any());
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Future<JsonNode> first = executor.submit(() -> json.readTree(mvc.perform(post(path(orderId))
                    .header("Idempotency-Key", key)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()));
            JsonNode pending;
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                pending = json.readTree(mvc.perform(post(path(orderId)).header("Idempotency-Key", key))
                        .andExpect(status().isAccepted()).andExpect(jsonPath("$.situacao").value("EM_PROCESSAMENTO"))
                        .andReturn().getResponse().getContentAsString());
            } finally { finish.countDown(); }
            assertThat(first.get(15, TimeUnit.SECONDS).path("idTentativa")).isEqualTo(pending.path("idTentativa"));
            verify(validatedCheckout, times(1)).createCheckout(any(), any());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"IN_PROGRESS", "FINALIZADA"})
    void shouldRejectUncompletedOsBeforeReserving(String state) throws Exception {
        UUID orderId = approvedOrder();
        OS_STATUS.set(state);
        mvc.perform(post(path(orderId)).header("Idempotency-Key", UUID.randomUUID())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("OS_NAO_CONCLUIDA"));
        assertNoCharge(orderId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "null", "\"invalid\""})
    void shouldRejectCancellationOrInvalidFlagBeforeReserving(String value) throws Exception {
        UUID orderId = approvedOrder();
        CANCELLATION.set(value);
        mvc.perform(post(path(orderId)).header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().is(value.equals("true") ? 409 : 502));
        assertNoCharge(orderId);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 500, 401})
    void shouldRejectOsLookupFailureBeforeReserving(int remoteStatus) throws Exception {
        UUID orderId = approvedOrder();
        OS_HTTP_STATUS.set(remoteStatus);
        mvc.perform(post(path(orderId)).header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().is(remoteStatus == 404 ? 404 : 503));
        assertNoCharge(orderId);
    }

    @Test
    void shouldRejectAbsentApprovedBudgetWithoutCallingPaymentProvider() throws Exception {
        UUID orderId = UUID.randomUUID();
        mvc.perform(post(path(orderId)).header("Idempotency-Key", UUID.randomUUID())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("ORCAMENTO_APROVADO_INDISPONIVEL"));
        assertNoCharge(orderId);
    }

    @Test
    void shouldIgnoreRepeatedAdministrativeHeadersOnActualMvcRoutes() throws Exception {
        UUID orderId = approvedOrder();
        mvc.perform(post(path(orderId)).header("Idempotency-Key", UUID.randomUUID())
                        .header("X-Authenticated-Subject", "", "context-only"))
                .andExpect(status().isOk());
        mvc.perform(get(path(orderId)).header("X-Authenticated-Subject", " ", "another-context"))
                .andExpect(status().isOk());
        assertThat(OS_REQUESTS).hasValue(1);
        assertThat(RECEIVED_EMPLOYEE.get()).isNull();
        verify(validatedCheckout).createCheckout(any(), any());
    }

    private void assertNoCharge(UUID orderId) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM charge WHERE service_order_id=?", Integer.class, orderId)).isZero();
        verifyNoInteractions(checkout);
    }

    private UUID approvedOrder() {
        UUID orderId = UUID.randomUUID();
        jdbc.update("INSERT INTO budget (budget_id,service_order_id,budget_version,quoted_amount,approved_amount,status,created_at) VALUES (?,?,1,530.12,530.12,'APPROVED',CURRENT_TIMESTAMP)", UUID.randomUUID(), orderId);
        return orderId;
    }

    private Map<String, Object> snapshot(String chargeId) {
        return jdbc.queryForMap("SELECT c.row_version,c.updated_at AS root_updated,a.* FROM charge c JOIN payment_attempt a ON a.charge_id=c.charge_id WHERE c.charge_id=?", UUID.fromString(chargeId));
    }

    private String path(UUID id) { return "/api/admin/ordens-servico/" + id + "/pagamentos/checkout"; }

    private static HttpServer startOsServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(OS_EXECUTOR);
            server.createContext("/internal/service-orders/", exchange -> {
                OS_REQUESTS.incrementAndGet();
                RECEIVED_EMPLOYEE.set(exchange.getRequestHeaders().getFirst("X-Authenticated-Subject"));
                RECEIVED_AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                String orderId = exchange.getRequestURI().getPath().substring("/internal/service-orders/".length());
                byte[] response = ("{\"serviceOrderId\":\"" + orderId + "\",\"status\":\"" + OS_STATUS.get() + "\",\"cancellationInProgress\":" + CANCELLATION.get() + "}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(OS_HTTP_STATUS.get(), response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
}
