package br.com.fiap.numberone.billing.payment.infrastructure.persistence;

import br.com.fiap.numberone.billing.payment.application.exceptions.CheckoutCreationException;
import br.com.fiap.numberone.billing.payment.application.exceptions.CheckoutOutcomeUnknownException;
import br.com.fiap.numberone.billing.payment.application.gateways.ChargeGateway;
import br.com.fiap.numberone.billing.payment.application.gateways.CheckoutGateway;
import br.com.fiap.numberone.billing.payment.application.gateways.ServiceOrderPaymentGateway;
import br.com.fiap.numberone.billing.payment.application.results.CheckoutOrder;
import br.com.fiap.numberone.billing.payment.application.results.CheckoutResult;
import br.com.fiap.numberone.billing.payment.application.results.PreparedCheckout;
import br.com.fiap.numberone.billing.payment.application.usecases.CreateCheckoutUseCase;
import br.com.fiap.numberone.billing.payment.domain.entities.Charge;
import br.com.fiap.numberone.billing.payment.domain.entities.PaymentAttempt;
import br.com.fiap.numberone.billing.payment.domain.enums.ChargeStatus;
import br.com.fiap.numberone.billing.payment.domain.enums.PaymentAttemptStatus;
import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentDataException;
import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentStateException;
import br.com.fiap.numberone.billing.payment.domain.exceptions.PaymentIdempotencyConflictException;
import br.com.fiap.numberone.billing.payment.domain.references.ServiceOrderPaymentContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(properties = {"billing.notifications.enabled=false",
        "billing.integrations.os-base-url=http://os.test", "billing.integrations.inventory-base-url=http://inventory.test"})
class CreateCheckoutIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private CreateCheckoutUseCase useCase;
    @Autowired private ChargeGateway charges;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private DataSource dataSource;
    @MockitoBean private ServiceOrderPaymentGateway serviceOrders;
    @MockitoBean private CheckoutGateway checkout;
    private final CheckoutGateway.ValidatedCheckout validatedCheckout = mock(CheckoutGateway.ValidatedCheckout.class);

    @BeforeEach
    void setUp() {
        when(checkout.validateSeller()).thenReturn(validatedCheckout);
        when(serviceOrders.findById(any())).thenAnswer(invocation -> completed(invocation.getArgument(0)));
        when(validatedCheckout.createCheckout(any(), any())).thenAnswer(invocation -> remoteOrder());
    }

    @Test
    void shouldCommitIntentBeforeProviderAndPersistResultInSeparateTransaction() {
        UUID orderId = approvedOrder();
        UUID key = UUID.randomUUID();
        when(validatedCheckout.createCheckout(any(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Charge snapshot = invocation.getArgument(0);
            UUID attemptId = invocation.getArgument(1);
            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM payment_attempt WHERE attempt_id = ?", attemptId);
            assertThat(row.get("idempotency_key")).isEqualTo(key);
            assertThat(row.get("checkout_creation_claimed_at")).isNotNull();
            assertThat(charges.findById(snapshot.getChargeId()).orElseThrow().getAttempts()).hasSize(1);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return remoteOrder();
        });

        CheckoutResult result = useCase.execute(orderId, key);
        Charge stored = charges.findById(result.chargeId()).orElseThrow();
        assertThat(result.checkoutUrl()).isNotNull();
        assertThat(stored.getAmount()).isEqualByComparingTo("530.12");
        assertThat(stored.getStatus()).isEqualTo(ChargeStatus.OPEN);
        assertThat(stored.getAttempts().getFirst().getProviderOrderId()).isEqualTo(result.providerOrderId());
        assertThat(stored.getAttempts().getFirst().getProviderPaymentId()).isNull();
        assertThat(stored.getAttempts().getFirst().getPaymentMethod()).isNull();
        Map<String, Object> before = snapshot(result.chargeId());
        assertThat(useCase.execute(orderId, key)).isEqualTo(result);
        assertThat(snapshot(result.chargeId())).isEqualTo(before);
        verify(validatedCheckout, times(1)).createCheckout(any(), any());
    }

    @Test
    void shouldReuseExistingChargeWithoutChangingCommercialData() {
        UUID orderId = approvedOrder();
        UUID budgetId = jdbc.queryForObject("SELECT budget_id FROM budget WHERE service_order_id=?", UUID.class, orderId);
        Charge charge = charges.createCharge(completed(orderId), budgetId, Currency.getInstance("BRL"));
        assertThat(useCase.execute(orderId, UUID.randomUUID()).chargeId()).isEqualTo(charge.getChargeId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM charge WHERE service_order_id=?", Integer.class, orderId)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABSENT", "DRAFT", "SENT", "REJECTED", "CANCELLED"})
    void shouldRejectMissingOrUnapprovedBudget(String status) {
        UUID orderId = UUID.randomUUID();
        if (!status.equals("ABSENT")) insertBudget(orderId, status, 1);
        assertThatThrownBy(() -> useCase.execute(orderId, UUID.randomUUID())).isInstanceOf(InvalidPaymentDataException.class);
        verifyNoInteractions(checkout);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM charge WHERE service_order_id=?", Integer.class, orderId)).isZero();
    }

    @Test
    void shouldRejectAmbiguousApprovedBudgets() {
        UUID orderId = approvedOrder();
        insertBudget(orderId, "APPROVED", 2);
        assertThatThrownBy(() -> useCase.execute(orderId, UUID.randomUUID())).isInstanceOf(InvalidPaymentDataException.class);
        verifyNoInteractions(checkout);
    }

    @Test
    void shouldRejectSameKeyForAnotherServiceOrder() {
        UUID key = UUID.randomUUID();
        CheckoutResult first = useCase.execute(approvedOrder(), key);
        clearInvocations(checkout, validatedCheckout);
        assertThatThrownBy(() -> useCase.execute(approvedOrder(), key)).isInstanceOf(PaymentIdempotencyConflictException.class);
        assertThat(charges.findById(first.chargeId()).orElseThrow().getAttempts()).hasSize(1);
        verifyNoInteractions(checkout);
    }

    @Test
    void shouldRejectChangedApprovedAmountForSameRequest() {
        UUID orderId = approvedOrder();
        UUID key = UUID.randomUUID();
        CheckoutResult first = useCase.execute(orderId, key);
        jdbc.update("UPDATE budget SET approved_amount=999 WHERE service_order_id=?", orderId);
        clearInvocations(checkout, validatedCheckout);
        assertThatThrownBy(() -> useCase.execute(orderId, key)).isInstanceOf(PaymentIdempotencyConflictException.class);
        assertThat(charges.findById(first.chargeId()).orElseThrow().getAmount()).isEqualByComparingTo("530.12");
        verifyNoInteractions(checkout);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TIMEOUT", "COMMUNICATION", "INVALID_RESPONSE", "IDEMPOTENCY_CONFLICT", "REQUEST_REJECTED"})
    void shouldPersistUnknownAndBlockAnyNewPostAfterProviderFailure(String kind) {
        UUID orderId = approvedOrder();
        UUID key = UUID.randomUUID();
        when(validatedCheckout.createCheckout(any(), any())).thenThrow(new CheckoutCreationException(
                CheckoutCreationException.Kind.valueOf(kind), null, true, null));
        CheckoutResult result = useCase.execute(orderId, key);
        assertThat(result.status()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        Map<String, Object> before = snapshot(result.chargeId());
        assertThat(useCase.execute(orderId, key)).isEqualTo(result);
        assertThat(snapshot(result.chargeId())).isEqualTo(before);
        assertThatThrownBy(() -> useCase.execute(orderId, UUID.randomUUID())).isInstanceOf(InvalidPaymentStateException.class);
        PaymentAttempt attempt = charges.findById(result.chargeId()).orElseThrow().getAttempts().getFirst();
        assertThat(attempt.getAttemptId()).isEqualTo(result.attemptId());
        assertThat(attempt.getIdempotencyKey()).isEqualTo(key);
        assertThat(attempt.getCheckoutCreationClaimedAt()).isNotNull();
        verify(validatedCheckout, times(1)).createCheckout(any(), any());
    }

    @Test
    void shouldLeaveCommittedReservationUntouchedAfterCrashBeforeExternalCall() {
        UUID orderId = approvedOrder();
        UUID key = UUID.randomUUID();
        PreparedCheckout committed = charges.prepareCheckout(completed(orderId), key);
        assertThat(committed.acquired()).isTrue();
        Map<String, Object> beforeCrash = snapshot(committed.charge().getChargeId());
        // Simula novo processo: somente identidades recebidas e banco sobrevivem; acquired foi perdido.
        CreateCheckoutUseCase restarted = new CreateCheckoutUseCase(serviceOrders, charges, checkout);
        CheckoutResult replay = restarted.execute(orderId, key);
        assertThat(replay.attemptId()).isEqualTo(committed.attemptId());
        assertThat(replay.status()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(replay.checkoutUrl()).isNull();
        assertThat(snapshot(replay.chargeId())).isEqualTo(beforeCrash);
        verifyNoInteractions(checkout);
        assertThatThrownBy(() -> restarted.execute(orderId, UUID.randomUUID())).isInstanceOf(InvalidPaymentStateException.class);
        verify(checkout).validateSeller();
        verifyNoInteractions(validatedCheckout);
    }

    @Test
    void shouldNotAssumeLegacyPendingAttemptWithoutReservation() {
        UUID orderId = approvedOrder();
        UUID budgetId = jdbc.queryForObject("SELECT budget_id FROM budget WHERE service_order_id=?", UUID.class, orderId);
        Charge charge = charges.createCharge(completed(orderId), budgetId, Currency.getInstance("BRL"));
        UUID key = UUID.randomUUID();
        PaymentAttempt legacy = charges.startAttempt(charge.getChargeId(), key);
        Map<String, Object> before = snapshot(charge.getChargeId());
        assertThat(useCase.execute(orderId, key).attemptId()).isEqualTo(legacy.getAttemptId());
        assertThat(snapshot(charge.getChargeId())).isEqualTo(before);
        assertThat(charges.findById(charge.getChargeId()).orElseThrow().getAttempts().getFirst().getCheckoutCreationClaimedAt()).isNull();
        verifyNoInteractions(checkout);
    }

    @Test
    void shouldReturnKnownPartialOrderWithoutPostingAgain() {
        UUID orderId = approvedOrder();
        UUID key = UUID.randomUUID();
        PreparedCheckout prepared = charges.prepareCheckout(completed(orderId), key);
        charges.associateProviderOrder(prepared.charge().getChargeId(), prepared.attemptId(), "partial-" + UUID.randomUUID(), null);
        CheckoutResult result = useCase.execute(orderId, key);
        assertThat(result.providerOrderId()).isNotNull();
        assertThat(result.checkoutUrl()).isNull();
        verifyNoInteractions(checkout);
    }

    @Test
    void shouldKeepPendingReservationAfterSimulatedProcessDeathDuringProviderCall() {
        UUID orderId = approvedOrder();
        UUID key = UUID.randomUUID();
        doThrow(new LinkageError("simulated process death")).when(validatedCheckout).createCheckout(any(), any());
        assertThatThrownBy(() -> useCase.execute(orderId, key)).isInstanceOf(LinkageError.class);
        Charge stored = charges.findByServiceOrderId(orderId).orElseThrow();
        PaymentAttempt attempt = stored.getAttempts().getFirst();
        assertThat(attempt.getCheckoutCreationClaimedAt()).isNotNull();
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        clearInvocations(checkout, validatedCheckout);
        CheckoutResult result = new CreateCheckoutUseCase(serviceOrders, charges, checkout).execute(orderId, key);
        assertThat(result.attemptId()).isEqualTo(attempt.getAttemptId());
        assertThat(result.status()).isEqualTo(PaymentAttemptStatus.PENDING);
        verifyNoInteractions(checkout);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldMakeOneExternalCallAcrossConcurrentRequestsAndNeverRenewReservation(boolean existingCharge) throws Exception {
        UUID orderId = approvedOrder();
        if (existingCharge) {
            UUID budgetId = jdbc.queryForObject("SELECT budget_id FROM budget WHERE service_order_id=?", UUID.class, orderId);
            charges.createCharge(completed(orderId), budgetId, Currency.getInstance("BRL"));
        }
        UUID key = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch enteredProvider = new CountDownLatch(1);
        CountDownLatch finishProvider = new CountDownLatch(1);
        when(validatedCheckout.createCheckout(any(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            enteredProvider.countDown();
            assertThat(finishProvider.await(20, TimeUnit.SECONDS)).isTrue();
            return remoteOrder();
        });
        try (java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<CheckoutResult>> requests = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                requests.add(executor.submit(() -> {
                    start.await();
                    // Instâncias independentes de orquestração; coordenação reside exclusivamente no PostgreSQL.
                    return new CreateCheckoutUseCase(serviceOrders, charges, checkout).execute(orderId, key);
                }));
            }
            start.countDown();
            try {
                assertThat(enteredProvider.await(15, TimeUnit.SECONDS)).isTrue();
                Charge committed = charges.findByServiceOrderId(orderId).orElseThrow();
                Map<String, Object> before = snapshot(committed.getChargeId());
                // Outra chamada termina enquanto o provedor ainda está bloqueado, sem lock mantido no HTTP.
                CheckoutResult concurrentReplay = useCase.execute(orderId, key);
                assertThat(concurrentReplay.checkoutUrl()).isNull();
                assertThat(concurrentReplay.status()).isEqualTo(PaymentAttemptStatus.PENDING);
                assertThat(snapshot(committed.getChargeId())).isEqualTo(before);
                assertThatThrownBy(() -> useCase.execute(orderId, UUID.randomUUID())).isInstanceOf(InvalidPaymentStateException.class);
                verify(validatedCheckout, times(1)).createCheckout(any(), any());
            } finally {
                finishProvider.countDown();
            }
            List<CheckoutResult> results = new ArrayList<>();
            for (Future<CheckoutResult> request : requests) results.add(request.get(20, TimeUnit.SECONDS));
            assertThat(results).extracting(CheckoutResult::attemptId).containsOnly(results.getFirst().attemptId());
            assertThat(results).extracting(CheckoutResult::chargeId).containsOnly(results.getFirst().chargeId());
            assertThat(charges.findById(results.getFirst().chargeId()).orElseThrow().getAttempts()).hasSize(1);
            assertThat(useCase.execute(orderId, key).checkoutUrl()).isNotNull();
            verify(validatedCheckout, times(1)).createCheckout(any(), any());
        }
    }

    @Test
    void shouldRejectAmbientTransactionBeforeReservingOrCallingProvider() {
        UUID orderId = approvedOrder();
        TransactionTemplate outer = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> outer.execute(transaction -> useCase.execute(orderId, UUID.randomUUID())))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(charges.findByServiceOrderId(orderId)).isEmpty();
        verifyNoInteractions(checkout);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shouldRollbackReservationIfCommitFailsBeforeProvider(CapturedOutput output) {
        UUID orderId = approvedOrder();
        // Trigger diferida falha no commit, depois do flush da tentativa/reserva.
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION reject_test_checkout_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'simulated commit failure'; END; $$
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER reject_test_checkout_commit AFTER INSERT ON payment_attempt
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_test_checkout_commit()
                """);
        try {
            assertThatThrownBy(() -> useCase.execute(orderId, UUID.randomUUID())).isInstanceOf(RuntimeException.class);
            assertThat(charges.findByServiceOrderId(orderId)).isEmpty();
            verify(checkout).validateSeller();
            verifyNoInteractions(validatedCheckout);
            assertThat(output.getOut().lines().filter(line -> line.contains("CHECKOUT_CREATION_RESERVED")
                    && line.contains(orderId.toString()))).isEmpty();
        } finally {
            jdbc.execute("DROP TRIGGER reject_test_checkout_commit ON payment_attempt");
            jdbc.execute("DROP FUNCTION reject_test_checkout_commit()");
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shouldLogCommittedMilestonesOnceAsEcsWithoutPrivateData(CapturedOutput output) {
        UUID orderId = approvedOrder();
        UUID key = UUID.randomUUID();
        String url = "https://www.mercadopago.com.br/checkout/v1/redirect?pref_id=private-checkout-test";
        when(validatedCheckout.createCheckout(any(), any())).thenAnswer(invocation -> {
            assertThat(output.getOut()).contains("CHECKOUT_CREATION_RESERVED").doesNotContain("CHECKOUT_CREATED");
            return new CheckoutOrder("log-order-" + UUID.randomUUID(), URI.create(url), "created");
        });
        CheckoutResult result = useCase.execute(orderId, key);
        useCase.execute(orderId, key);
        JsonMapper json = new JsonMapper();
        List<JsonNode> events = output.getOut().lines().filter(line -> line.startsWith("{"))
                .map(json::readTree).filter(node -> node.path("service_order").path("id").asText().equals(orderId.toString())).toList();
        assertThat(events).extracting(node -> node.path("event").path("code").asText())
                .containsExactly("CHECKOUT_CREATION_RESERVED", "CHECKOUT_CREATED", "CHECKOUT_REUSED");
        assertThat(events).allSatisfy(node -> {
            assertThat(node.path("charge").path("id").asText()).isEqualTo(result.chargeId().toString());
            assertThat(node.path("payment_attempt").path("id").asText()).isEqualTo(result.attemptId().toString());
            assertThat(node.path("ecs").path("version").asText()).isEqualTo("8.11");
        });
        UUID uncertainOrder = approvedOrder();
        doThrow(new CheckoutCreationException(CheckoutCreationException.Kind.TIMEOUT, null, true,
                new IllegalStateException("private-external-exception-test"))).when(validatedCheckout).createCheckout(any(), any());
        useCase.execute(uncertainOrder, UUID.randomUUID());
        assertThat(output.getOut()).contains("CHECKOUT_OUTCOME_UNKNOWN")
                .doesNotContain(url, key.toString(), "private-external-exception-test");
    }

    @Test
    void shouldKeepReservationWhenRemoteSuccessCannotBePersisted() {
        CheckoutResult first = useCase.execute(approvedOrder(), UUID.randomUUID());
        UUID orderId = approvedOrder();
        UUID key = UUID.randomUUID();
        when(validatedCheckout.createCheckout(any(), any())).thenReturn(
                new CheckoutOrder(first.providerOrderId(), URI.create(first.checkoutUrl()), "created"));
        assertThatThrownBy(() -> useCase.execute(orderId, key)).isInstanceOf(CheckoutOutcomeUnknownException.class)
                .hasCauseInstanceOf(RuntimeException.class);
        Charge reserved = charges.findByServiceOrderId(orderId).orElseThrow();
        PaymentAttempt attempt = reserved.getAttempts().getFirst();
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(attempt.getCheckoutCreationClaimedAt()).isNotNull();
        assertThat(attempt.getProviderOrderId()).isNull();
        assertThat(attempt.getIdempotencyKey()).isEqualTo(key);
        clearInvocations(checkout, validatedCheckout);
        assertThat(useCase.execute(orderId, key).status()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        verifyNoInteractions(checkout);
    }

    @Test
    void shouldAllowOnlyManualNewKeyAfterConfirmedTerminalFailureAndRetainOldClaim() {
        UUID orderId = approvedOrder();
        UUID originalKey = UUID.randomUUID();
        CheckoutResult first = useCase.execute(orderId, originalKey);
        Instant claim = charges.findById(first.chargeId()).orElseThrow().getAttempts().getFirst().getCheckoutCreationClaimedAt();
        charges.confirmAttemptFailure(first.chargeId(), first.attemptId());
        clearInvocations(checkout, validatedCheckout);
        assertThat(useCase.execute(orderId, originalKey).attemptId()).isEqualTo(first.attemptId());
        verifyNoInteractions(checkout);
        CheckoutResult next = useCase.execute(orderId, UUID.randomUUID());
        assertThat(next.attemptId()).isNotEqualTo(first.attemptId());
        List<PaymentAttempt> history = charges.findById(first.chargeId()).orElseThrow().getAttempts();
        assertThat(history).hasSize(2);
        assertThat(history.getFirst().getCheckoutCreationClaimedAt()).isEqualTo(claim);
    }

    @Test
    void shouldUpgradeV10WithoutChangingLegacyRowsOrPriorMigrationChecksums() {
        String schema = "checkout_v11_" + UUID.randomUUID().toString().replace("-", "");
        Flyway previous = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("10").load();
        previous.migrate();
        UUID budget = UUID.randomUUID();
        UUID charge = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".budget (budget_id,service_order_id,budget_version,quoted_amount,approved_amount,status,created_at) VALUES (?,?,1,10,10,'APPROVED',CURRENT_TIMESTAMP)", budget, UUID.randomUUID());
        jdbc.update("INSERT INTO " + schema + ".charge (charge_id,service_order_id,budget_id,amount,currency,status,created_at,updated_at) VALUES (?,?,?,10,'BRL','OPEN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", charge, UUID.randomUUID(), budget);
        jdbc.update("INSERT INTO " + schema + ".payment_attempt (attempt_id,charge_id,idempotency_key,status,created_at,updated_at) VALUES (?,?,?,'UNKNOWN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", attempt, charge, UUID.randomUUID());
        List<Map<String, Object>> budgetBefore = jdbc.queryForList("SELECT * FROM " + schema + ".budget");
        List<Map<String, Object>> chargeBefore = jdbc.queryForList("SELECT * FROM " + schema + ".charge");
        Map<String, Object> attemptBefore = jdbc.queryForMap("SELECT * FROM " + schema + ".payment_attempt");
        List<Map<String, Object>> checksums = jdbc.queryForList("SELECT version,checksum FROM " + schema + ".flyway_schema_history ORDER BY installed_rank");
        Flyway current = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("11").load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(current.validateWithResult().validationSuccessful).isTrue();
        assertThat(jdbc.queryForList("SELECT * FROM " + schema + ".budget")).isEqualTo(budgetBefore);
        assertThat(jdbc.queryForList("SELECT * FROM " + schema + ".charge")).isEqualTo(chargeBefore);
        Map<String, Object> attemptAfter = jdbc.queryForMap("SELECT * FROM " + schema + ".payment_attempt");
        assertThat(attemptAfter.remove("checkout_creation_claimed_at")).isNull();
        assertThat(attemptAfter).isEqualTo(attemptBefore);
        assertThat(jdbc.queryForList("SELECT version,checksum FROM " + schema + ".flyway_schema_history WHERE version IS DISTINCT FROM '11' ORDER BY installed_rank")).isEqualTo(checksums);
    }

    private Map<String, Object> snapshot(UUID chargeId) {
        return jdbc.queryForMap("SELECT c.row_version,c.updated_at AS root_updated,a.* FROM charge c JOIN payment_attempt a ON a.charge_id=c.charge_id WHERE c.charge_id=? ORDER BY a.created_at LIMIT 1", chargeId);
    }

    private UUID approvedOrder() {
        UUID orderId = UUID.randomUUID();
        insertBudget(orderId, "APPROVED", 1);
        return orderId;
    }

    private void insertBudget(UUID orderId, String status, int version) {
        jdbc.update("INSERT INTO budget (budget_id,service_order_id,budget_version,quoted_amount,approved_amount,status,created_at) VALUES (?,?,?,530.12,530.12,?,CURRENT_TIMESTAMP)", UUID.randomUUID(), orderId, version, status);
    }

    private ServiceOrderPaymentContext completed(UUID orderId) { return new ServiceOrderPaymentContext(orderId, "COMPLETED"); }
    private CheckoutOrder remoteOrder() {
        String id = "order-" + UUID.randomUUID();
        return new CheckoutOrder(id, URI.create("https://www.mercadopago.com.br/checkout/v1/redirect?pref_id=" + id), "created");
    }
}
