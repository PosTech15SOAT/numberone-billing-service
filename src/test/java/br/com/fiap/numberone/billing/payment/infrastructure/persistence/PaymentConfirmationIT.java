package br.com.fiap.numberone.billing.payment.infrastructure.persistence;

import br.com.fiap.numberone.billing.budget.application.gateways.IntegrationEventPublicationGateway;
import br.com.fiap.numberone.billing.budget.infrastructure.config.OutboxPublisherProperties;
import br.com.fiap.numberone.billing.budget.infrastructure.outbox.IntegrationEventOutboxRepository;
import br.com.fiap.numberone.billing.budget.infrastructure.outbox.OutboxPublisher;
import br.com.fiap.numberone.billing.payment.application.exceptions.OrderLookupException;
import br.com.fiap.numberone.billing.payment.application.gateways.ChargeGateway;
import br.com.fiap.numberone.billing.payment.application.results.OrderObservation;
import br.com.fiap.numberone.billing.payment.application.results.PreparedCheckout;
import br.com.fiap.numberone.billing.payment.application.results.ProviderOrderSnapshot;
import br.com.fiap.numberone.billing.payment.application.results.ProviderPaymentSnapshot;
import br.com.fiap.numberone.billing.payment.application.usecases.ConfirmPaymentUseCase;
import br.com.fiap.numberone.billing.payment.domain.entities.Charge;
import br.com.fiap.numberone.billing.payment.domain.entities.PaymentAttempt;
import br.com.fiap.numberone.billing.payment.domain.enums.ChargeStatus;
import br.com.fiap.numberone.billing.payment.domain.enums.PaymentAttemptStatus;
import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentStateException;
import br.com.fiap.numberone.billing.payment.domain.references.ServiceOrderPaymentContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static br.com.fiap.numberone.billing.payment.support.PaymentConfirmationFixtures.approved;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = {"billing.notifications.enabled=false", "billing.outbox.enabled=false",
        "billing.integrations.os-base-url=http://os.test", "billing.integrations.inventory-base-url=http://inventory.test"})
class PaymentConfirmationIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired private ChargeGateway charges;
    @Autowired private ConfirmPaymentUseCase confirm;
    @Autowired private IntegrationEventOutboxRepository outbox;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private DataSource dataSource;
    private Charge charge;
    private UUID attemptId;
    private String paymentId;
    private OrderObservation observation;

    @BeforeEach void setUp() {
        UUID serviceOrderId = UUID.randomUUID();
        jdbc.update("INSERT INTO budget (budget_id,service_order_id,budget_version,quoted_amount,approved_amount,status,created_at) VALUES (?,?,1,10.00,10.00,'APPROVED',CURRENT_TIMESTAMP)", UUID.randomUUID(), serviceOrderId);
        PreparedCheckout prepared = charges.prepareCheckout(new ServiceOrderPaymentContext(serviceOrderId, "COMPLETED"), UUID.randomUUID());
        attemptId = prepared.attemptId();
        charge = charges.associateProviderOrder(prepared.charge().getChargeId(), attemptId, "ORD" + attemptId, "https://checkout.example.test/private-url");
        paymentId = "PAY" + attemptId;
        observation = approved(charge, attemptId, paymentId);
    }

    @Test void shouldCommitIntegralPaymentAndProposedEnvelopeOnceAndPreserveReplay(CapturedOutput output) {
        List<Map<String, Object>> budgetBefore = budgetRows();
        PaymentAttempt original = charge.getAttempts().getFirst();
        confirm.execute(observation);
        Charge paid = current();
        assertThat(paid.getStatus()).isEqualTo(ChargeStatus.PAID);
        PaymentAttempt attempt = paid.getAttempts().getFirst();
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PAID);
        assertThat(attempt.getProviderPaymentId()).isEqualTo(paymentId);
        assertThat(attempt.getIdempotencyKey()).isEqualTo(original.getIdempotencyKey());
        assertThat(attempt.getCheckoutCreationClaimedAt()).isEqualTo(original.getCheckoutCreationClaimedAt());
        assertThat(attempt.getProviderOrderId()).isEqualTo(original.getProviderOrderId());
        assertThat(attempt.getCheckoutUrl()).isEqualTo(original.getCheckoutUrl());
        assertThat(paid.getAmount()).isEqualTo(charge.getAmount());
        Map<String, Object> event = eventRows().getFirst();
        JsonNode envelope = JsonMapper.builder().build().readTree(event.get("envelope").toString());
        assertThat(envelope.size()).isEqualTo(6);
        assertThat(envelope.path("eventId").asText()).isEqualTo(event.get("event_id").toString());
        assertThat(envelope.path("eventType").asText()).isEqualTo("PagamentoConfirmado");
        assertThat(envelope.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(envelope.path("occurredAt").asText()).isEqualTo(paid.getUpdatedAt().toString());
        assertThat(envelope.path("correlationId").asText()).isEqualTo(charge.getChargeId().toString());
        JsonNode payload = envelope.path("payload");
        assertThat(payload.size()).isEqualTo(4);
        assertThat(payload.path("paymentId").asText()).isEqualTo(charge.getChargeId().toString());
        assertThat(payload.path("serviceOrderId").asText()).isEqualTo(charge.getServiceOrderId().toString());
        assertThat(payload.path("paymentStatus").asText()).isEqualTo("PAID");
        assertThat(payload.path("paymentVersion").asInt()).isEqualTo(1);
        assertThat(event.get("status")).isEqualTo("PENDING");
        assertThat(event.get("budget_id")).isNull();
        Map<String, Object> financialBeforeReplay = financialRow();
        for (int index = 0; index < 3; index++) confirm.execute(observation);
        assertThat(financialRow()).isEqualTo(financialBeforeReplay);
        assertThat(eventRows()).containsExactly(event);
        assertThat(budgetRows()).isEqualTo(budgetBefore);
        assertThat(output.getAll()).doesNotContain(original.getIdempotencyKey().toString(), "private-url", "client_token", "access_token");
        assertThat(output.getAll()).contains("\"provider_payment\":{\"id\":\"" + paymentId + "\"}");
        assertThat(output.getAll().split("Quitação confirmada e evento persistido na Outbox", -1)).hasSize(2);
    }

    @Test void shouldConfirmUnknownAttemptWithoutChangingReservation() {
        charges.markAttemptUnknown(charge.getChargeId(), attemptId);
        confirm.execute(observation);
        assertThat(current().getAttempts().getFirst().getStatus()).isEqualTo(PaymentAttemptStatus.PAID);
        assertThat(current().getAttempts().getFirst().getCheckoutCreationClaimedAt()).isEqualTo(charge.getAttempts().getFirst().getCheckoutCreationClaimedAt());
        assertThat(eventRows()).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = OrderObservation.Situation.class, names = {"APPROVED", "REFUNDED"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldKeepStateAndReservationForNonApprovedObservation(OrderObservation.Situation situation) {
        charges.markAttemptUnknown(charge.getChargeId(), attemptId);
        Map<String, Object> before = financialRow();
        confirm.execute(new OrderObservation(observation.providerOrderId(), observation.chargeId(), attemptId, observation.serviceOrderId(), situation, observation.snapshot()));
        assertThat(financialRow()).isEqualTo(before);
        assertThat(eventRows()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"payment", "attempt", "reference", "amount", "currency", "order", "serviceOrder", "split", "splitDivergent"})
    void shouldRevalidateEvidenceUnderLockAndRejectForgedApproval(String divergent) {
        if (divergent.equals("payment")) charges.recordPendingAttempt(charge.getChargeId(), attemptId, "PAYknown");
        Map<String, Object> before = financialRow();
        ProviderOrderSnapshot original = observation.snapshot();
        List<ProviderPaymentSnapshot> payments = original.payments();
        if (divergent.startsWith("split")) payments = List.of(
                new ProviderPaymentSnapshot("PAYone", "processed", "accredited", new BigDecimal("4.00"), new BigDecimal(divergent.equals("splitDivergent") ? "6.00" : "4.00")),
                new ProviderPaymentSnapshot("PAYtwo", "processed", "accredited", new BigDecimal("6.00"), new BigDecimal(divergent.equals("splitDivergent") ? "4.00" : "6.00")));
        ProviderOrderSnapshot snapshot = new ProviderOrderSnapshot(divergent.equals("order") ? "ORDother" : original.providerOrderId(), "processed", "accredited",
                divergent.equals("amount") ? BigDecimal.ONE : original.totalAmount(), original.totalPaidAmount(), divergent.equals("currency") ? "USD" : "BRL",
                divergent.equals("reference") ? UUID.randomUUID().toString() : original.externalReference(), payments, false);
        OrderObservation forged = new OrderObservation(observation.providerOrderId(), charge.getChargeId(), divergent.equals("attempt") ? UUID.randomUUID() : attemptId,
                divergent.equals("serviceOrder") ? UUID.randomUUID() : charge.getServiceOrderId(), OrderObservation.Situation.APPROVED, snapshot);
        assertThatThrownBy(() -> confirm.execute(forged)).isInstanceOf(OrderLookupException.class);
        assertThat(financialRow()).isEqualTo(before);
        assertThat(eventRows()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "EXPIRED"})
    void shouldNotReopenTerminalAttemptOnContradictoryApproval(String terminal) {
        if (terminal.equals("FAILED")) charges.confirmAttemptFailure(charge.getChargeId(), attemptId);
        else charges.confirmAttemptExpiration(charge.getChargeId(), attemptId);
        Map<String, Object> before = financialRow();
        assertThatThrownBy(() -> confirm.execute(observation)).isInstanceOf(InvalidPaymentStateException.class);
        assertThat(financialRow()).isEqualTo(before);
        assertThat(eventRows()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.00", "10.96"})
    void shouldSerializeEquivalentConcurrentConfirmations(String paid) throws Exception {
        usePaidTotal(paid);
        List<Throwable> failures = concurrently(() -> confirm.execute(observation), () -> confirm.execute(observation));
        assertThat(failures).containsOnlyNulls();
        assertThat(current().getStatus()).isEqualTo(ChargeStatus.PAID);
        assertThat(eventRows()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT row_version FROM charge WHERE charge_id=?", Long.class, charge.getChargeId())).isEqualTo(3);
    }

    @Test void shouldRefuseConcurrentConflictingFinancialIdentities() throws Exception {
        OrderObservation conflicting = approved(charge, attemptId, "PAYother");
        List<Throwable> failures = concurrently(() -> confirm.execute(observation), () -> confirm.execute(conflicting));
        assertThat(failures.stream().filter(failure -> failure != null).toList()).singleElement().isInstanceOf(OrderLookupException.class);
        assertThat(current().getAttempts().getFirst().getProviderPaymentId()).isIn(paymentId, "PAYother");
        assertThat(eventRows()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.00", "10.96"})
    void shouldRollbackAttemptChargeAndVersionWhenOutboxInsertFails(String paid, CapturedOutput output) {
        usePaidTotal(paid);
        Map<String, Object> before = financialRow();
        jdbc.execute("ALTER TABLE integration_event_outbox ADD CONSTRAINT test_payment_outbox_failure CHECK (charge_id IS DISTINCT FROM '" + charge.getChargeId() + "'::uuid) NOT VALID");
        try {
            assertThatThrownBy(() -> confirm.execute(observation)).isInstanceOf(DataIntegrityViolationException.class);
        } finally { jdbc.execute("ALTER TABLE integration_event_outbox DROP CONSTRAINT test_payment_outbox_failure"); }
        assertThat(financialRow()).isEqualTo(before);
        assertThat(eventRows()).isEmpty();
        assertThat(output.getAll()).doesNotContain("Quitação confirmada e evento persistido na Outbox");
        confirm.execute(observation);
        assertThat(eventRows()).hasSize(1);
    }

    @Test void shouldRollbackOutboxAndFinancialChangesWhenCallerFailsAfterAppend(CapturedOutput output) {
        Map<String, Object> before = financialRow();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            confirm.execute(observation);
            assertThat(eventRows()).hasSize(1);
            assertThat(current().getStatus()).isEqualTo(ChargeStatus.PAID);
            assertThat(output.getAll()).doesNotContain("Quitação confirmada e evento persistido na Outbox");
            throw new IllegalStateException("Falha controlada após append");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(financialRow()).isEqualTo(before);
        assertThat(eventRows()).isEmpty();
        assertThat(output.getAll()).doesNotContain("Quitação confirmada e evento persistido na Outbox");
    }

    @Test void shouldNeverAppendWhenFinancialFlushFails() {
        Map<String, Object> before = financialRow();
        jdbc.execute("ALTER TABLE charge ADD CONSTRAINT test_payment_state_failure CHECK (charge_id <> '" + charge.getChargeId() + "'::uuid OR status <> 'PAID') NOT VALID");
        try { assertThatThrownBy(() -> confirm.execute(observation)).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("ALTER TABLE charge DROP CONSTRAINT test_payment_state_failure"); }
        assertThat(financialRow()).isEqualTo(before);
        assertThat(eventRows()).isEmpty();
    }

    @Test void shouldKeepPaidStateAndOriginalEventForOldPendingAndRejectedObservations() {
        confirm.execute(observation);
        Map<String, Object> before = financialRow();
        List<Map<String, Object>> eventsBefore = eventRows();
        for (OrderObservation.Situation state : List.of(OrderObservation.Situation.PENDING, OrderObservation.Situation.REJECTED))
            confirm.execute(new OrderObservation(observation.providerOrderId(), charge.getChargeId(), attemptId, charge.getServiceOrderId(), state, observation.snapshot()));
        assertThat(financialRow()).isEqualTo(before);
        assertThat(eventRows()).isEqualTo(eventsBefore);
        assertThatThrownBy(() -> charges.startAttempt(charge.getChargeId(), UUID.randomUUID())).isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test void shouldPersistConfirmedRefundAndPublishOnceWithoutRegressingOnLateApproval() {
        confirm.execute(observation);
        var refund = fullRefund();
        confirm.execute(refund);
        confirm.execute(refund);
        confirm.execute(observation);

        assertThat(current().getStatus()).isEqualTo(ChargeStatus.REFUNDED);
        assertThat(eventRows()).hasSize(2);
        var row = eventRows().stream().filter(event -> "PagamentoAtualizado".equals(event.get("event_type")))
                .findFirst().orElseThrow();
        var payload = new JsonMapper().readTree(row.get("envelope").toString()).path("payload");
        assertThat(payload.path("paymentStatus").asText()).isEqualTo("REFUNDED");
        assertThat(payload.path("paymentVersion").asInt()).isEqualTo(2);
        assertThat(payload.path("paymentId").asText()).isEqualTo(charge.getChargeId().toString());
    }

    @Test void shouldPersistFullRefundWhenProviderObservationPrecedesPaymentConfirmation() {
        var refund = fullRefund();
        confirm.execute(refund);
        confirm.execute(observation);
        confirm.execute(refund);

        assertThat(current().getStatus()).isEqualTo(ChargeStatus.REFUNDED);
        assertThat(current().getAttempts().getFirst().getProviderPaymentId()).isEqualTo(paymentId);
        assertThat(eventRows()).extracting(event -> event.get("event_type")).containsExactly("PagamentoAtualizado");
    }

    private OrderObservation fullRefund() {
        var original = observation.snapshot();
        var refunded = new ProviderOrderSnapshot(original.providerOrderId(), "refunded", "refunded",
                original.totalAmount(), original.totalPaidAmount(), original.currency(), original.externalReference(),
                List.of(new ProviderPaymentSnapshot(paymentId, "refunded", "refunded", original.totalAmount(),
                        original.totalPaidAmount())), true);
        return new OrderObservation(original.providerOrderId(), charge.getChargeId(), attemptId,
                charge.getServiceOrderId(), OrderObservation.Situation.REFUNDED, refunded);
    }

    @Test void shouldEnforceUniqueLogicalEventInPostgres() {
        confirm.execute(observation);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO integration_event_outbox (event_id,event_type,schema_version,service_order_id,charge_id,envelope)
                SELECT gen_random_uuid(),event_type,schema_version,service_order_id,charge_id,envelope
                FROM integration_event_outbox WHERE charge_id=?
                """, charge.getChargeId())).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_outbox_charge_confirmation");
        assertThat(eventRows()).hasSize(1);
    }

    @Test void shouldPublishPaymentWithExistingOutboxWorkerOutsideTransaction() {
        confirm.execute(observation);
        IntegrationEventPublicationGateway publication = mock(IntegrationEventPublicationGateway.class);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(publication).publish(anyString());
        OutboxPublisher publisher = new OutboxPublisher(outbox, publication,
                new OutboxPublisherProperties(100, Duration.ofMinutes(5), Duration.ofSeconds(1), 3, Duration.ofSeconds(1), Duration.ofMinutes(1)));
        String originalEnvelope = eventRows().getFirst().get("envelope").toString();
        publisher.publishPendingEvents();
        verify(publication).publish(originalEnvelope);
        assertThat(eventRows().getFirst().get("status")).isEqualTo("PUBLISHED");
    }

    @Test void shouldPreserveLegacyPaidWithoutInventingAnEvent() {
        jdbc.update("UPDATE payment_attempt SET status='PAID',provider_payment_id=? WHERE attempt_id=?", paymentId, attemptId);
        jdbc.update("UPDATE charge SET status='PAID' WHERE charge_id=?", charge.getChargeId());
        Map<String, Object> before = financialRow();
        confirm.execute(observation);
        assertThat(financialRow()).isEqualTo(before);
        assertThat(eventRows()).isEmpty();
    }

    @Test void shouldRollbackOnDatabaseCommitFailureAndNeverLogConfirmation(CapturedOutput output) {
        Map<String, Object> before = financialRow();
        jdbc.execute("CREATE FUNCTION test_payment_commit_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.charge_id = '" + charge.getChargeId() + "'::uuid THEN RAISE EXCEPTION 'Falha controlada no commit'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE CONSTRAINT TRIGGER test_payment_commit_failure AFTER INSERT ON integration_event_outbox DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION test_payment_commit_failure()");
        try { assertThatThrownBy(() -> confirm.execute(observation)).isInstanceOf(RuntimeException.class); }
        finally {
            jdbc.execute("DROP TRIGGER test_payment_commit_failure ON integration_event_outbox");
            jdbc.execute("DROP FUNCTION test_payment_commit_failure()");
        }
        assertThat(financialRow()).isEqualTo(before);
        assertThat(eventRows()).isEmpty();
        assertThat(output.getAll()).doesNotContain("Quitação confirmada e evento persistido na Outbox");
    }

    @Test void shouldUpgradeV11WithoutRewritingHistoryOrBackfillingLegacyPaidCharge() {
        String schema = "payment_v12_" + UUID.randomUUID().toString().replace("-", "");
        Flyway previous = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("11").load();
        previous.migrate();
        jdbc.execute("INSERT INTO " + schema + ".budget SELECT * FROM public.budget WHERE budget_id='" + charge.getBudgetId() + "'");
        jdbc.execute("INSERT INTO " + schema + ".charge SELECT * FROM public.charge WHERE charge_id='" + charge.getChargeId() + "'");
        jdbc.execute("INSERT INTO " + schema + ".payment_attempt SELECT * FROM public.payment_attempt WHERE attempt_id='" + attemptId + "'");
        jdbc.update("UPDATE " + schema + ".payment_attempt SET status='PAID',provider_payment_id=? WHERE attempt_id=?", paymentId, attemptId);
        jdbc.update("UPDATE " + schema + ".charge SET status='PAID' WHERE charge_id=?", charge.getChargeId());
        List<Map<String, Object>> chargesBefore = jdbc.queryForList("SELECT * FROM " + schema + ".charge");
        List<Map<String, Object>> attemptsBefore = jdbc.queryForList("SELECT * FROM " + schema + ".payment_attempt");
        List<Map<String, Object>> checksums = jdbc.queryForList("SELECT version,checksum FROM " + schema + ".flyway_schema_history ORDER BY installed_rank");
        Flyway current = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(2);
        assertThat(current.validateWithResult().validationSuccessful).isTrue();
        assertThat(jdbc.queryForList("SELECT * FROM " + schema + ".charge")).isEqualTo(chargesBefore);
        assertThat(jdbc.queryForList("SELECT * FROM " + schema + ".payment_attempt")).isEqualTo(attemptsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM " + schema + ".integration_event_outbox")).isEmpty();
        assertThat(jdbc.queryForList("SELECT version,checksum FROM " + schema + ".flyway_schema_history WHERE installed_rank <= 11 ORDER BY installed_rank")).isEqualTo(checksums);
    }

    private void usePaidTotal(String paid) {
        ProviderOrderSnapshot original = observation.snapshot();
        ProviderOrderSnapshot snapshot = new ProviderOrderSnapshot(original.providerOrderId(), "processed", "accredited", original.totalAmount(),
                new BigDecimal(paid), original.currency(), original.externalReference(),
                List.of(new ProviderPaymentSnapshot(paymentId, "processed", "accredited", charge.getAmount(), new BigDecimal(paid))), false);
        observation = new OrderObservation(observation.providerOrderId(), charge.getChargeId(), attemptId, charge.getServiceOrderId(), OrderObservation.Situation.APPROVED, snapshot);
    }

    private List<Throwable> concurrently(Runnable first, Runnable second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<java.util.concurrent.Future<Throwable>> results = executor.invokeAll(List.of(task(first, barrier), task(second, barrier)), 20, TimeUnit.SECONDS);
            return java.util.Arrays.asList(results.getFirst().get(15, TimeUnit.SECONDS), results.getLast().get(15, TimeUnit.SECONDS));
        }
    }
    private Callable<Throwable> task(Runnable work, CyclicBarrier barrier) {
        return () -> { try { barrier.await(10, TimeUnit.SECONDS); work.run(); return null; } catch (Throwable failure) { return failure; } };
    }
    private Charge current() { return charges.findById(charge.getChargeId()).orElseThrow(); }
    private Map<String, Object> financialRow() { return jdbc.queryForMap("SELECT c.row_version,c.status AS charge_status,c.updated_at AS charge_updated,a.* FROM charge c JOIN payment_attempt a ON a.charge_id=c.charge_id WHERE a.attempt_id=?", attemptId); }
    private List<Map<String, Object>> eventRows() { return jdbc.queryForList("SELECT * FROM integration_event_outbox WHERE charge_id=?", charge.getChargeId()); }
    private List<Map<String, Object>> budgetRows() { return jdbc.queryForList("SELECT * FROM budget WHERE budget_id=?", charge.getBudgetId()); }
}
