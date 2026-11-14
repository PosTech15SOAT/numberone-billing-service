package br.com.fiap.numberone.billing.budget.infrastructure.persistence;

import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.infrastructure.outbox.BudgetIntegrationEventFactory;
import br.com.fiap.numberone.billing.budget.infrastructure.outbox.IntegrationEventOutboxRepository;
import tools.jackson.databind.json.JsonMapper;
import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetNotFoundException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetStatusException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.ObsoleteBudgetRevisionException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.ApprovedBudgetExistsException;
import br.com.fiap.numberone.billing.budget.infrastructure.persistence.mappers.BudgetPersistenceMapper;
import jakarta.persistence.OptimisticLockException;
import br.com.fiap.numberone.billing.budget.application.usecases.SendBudgetForApprovalUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.ApproveBudgetUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.RejectBudgetUseCase;
import br.com.fiap.numberone.billing.budget.infrastructure.persistence.entities.BudgetEntity;
import jakarta.persistence.EntityManager;
import br.com.fiap.numberone.billing.budget.domain.exceptions.CustomerEmailException;
import br.com.fiap.numberone.billing.budget.application.usecases.CreateBudgetUseCase;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.enums.BudgetStatus;
import br.com.fiap.numberone.billing.budget.domain.enums.BudgetApprovalOrigin;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetDataException;
import br.com.fiap.numberone.billing.budget.domain.references.InventoryItemPrice;
import br.com.fiap.numberone.billing.budget.domain.references.ServiceOrderQuotationContext;
import br.com.fiap.numberone.billing.budget.domain.valueobjects.BudgetComposition;
import br.com.fiap.numberone.billing.budget.domain.exceptions.BudgetCalculationException;
import br.com.fiap.numberone.billing.budget.infrastructure.persistence.repositories.BudgetRevisionCounterRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.function.Function;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(properties = {
        "billing.notifications.enabled=false",
        "billing.notifications.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "billing.notifications.approval-base-url=https://billing.test/decision",
        "billing.integrations.os-base-url=http://os.test",
        "billing.integrations.inventory-base-url=http://inventory.test"
})
class BudgetPersistenceIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private BudgetGateway gateway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private BudgetRevisionCounterRepository counterRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private IntegrationEventOutboxRepository outboxRepository;

    @Autowired
    private BudgetIntegrationEventFactory eventFactory;

    @Autowired
    private JsonMapper jsonMapper;

    @ParameterizedTest
    @ValueSource(strings = {"0.001", "1.234", "1.2301"})
    void shouldRejectIncompatibleNewAmountAndRollbackRevisionReservationBeforePersistence(String value) {
        UUID orderId = UUID.randomUUID();
        assertThatThrownBy(() -> gateway.createDraft(orderId, new BigDecimal(value), new BudgetComposition(List.of(), 1)))
                .isInstanceOf(br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetDataException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM budget WHERE service_order_id=?", Integer.class, orderId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM budget_revision_counter WHERE service_order_id=?", Integer.class, orderId)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "SENT"})
    void shouldRegisterInPersonApprovalAtomicallyWithAuditAndOnlyOneNewApprovalEvent(BudgetStatus initial) {
        UUID orderId = UUID.randomUUID();
        var context = quotationContext(orderId, UUID.randomUUID());
        Budget draft = new CreateBudgetUseCase(id -> context, ids -> List.of(), gateway).execute(orderId, new BigDecimal("350.12"));
        if (initial == BudgetStatus.SENT) gateway.sendForApproval(draft.getBudgetId(), "customer@example.test");
        Budget before = gateway.findById(draft.getBudgetId()).orElseThrow();
        long versionBefore = rowVersion(draft.getBudgetId());
        String snapshot = jdbcTemplate.queryForObject("SELECT commercial_snapshot::text FROM budget WHERE budget_id = ?", String.class, draft.getBudgetId());
        var previousEvents = outboxRows(draft.getBudgetId());

        Budget approved = new br.com.fiap.numberone.billing.budget.application.usecases.RegisterBudgetApprovalUseCase(
                gateway, () -> "validated-subject", id -> context).execute(draft.getBudgetId());

        Budget stored = gateway.findById(draft.getBudgetId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(BudgetStatus.APPROVED);
        assertThat(stored.getApprovalOrigin()).isEqualTo(BudgetApprovalOrigin.IN_PERSON);
        assertThat(stored.getApprovedByEmployeeId()).isEqualTo("validated-subject");
        assertThat(stored.getApprovedAt()).isNotNull();
        assertThat(stored.getSentAt()).isNotNull();
        if (initial == BudgetStatus.SENT) assertThat(stored.getSentAt()).isEqualTo(before.getSentAt());
        assertThat(stored.getApprovedAmount()).isEqualByComparingTo(before.getQuotedAmount());
        assertThat(stored.getQuotedAmount()).isEqualByComparingTo(before.getQuotedAmount());
        assertThat(stored.getComposition()).isEqualTo(before.getComposition());
        assertThat(stored.getBudgetId()).isEqualTo(before.getBudgetId());
        assertThat(stored.getBudgetVersion()).isEqualTo(before.getBudgetVersion());
        assertThat(lastVersion(orderId)).isEqualTo(before.getBudgetVersion());
        assertThat(rowVersion(draft.getBudgetId())).isEqualTo(versionBefore + 1);
        assertThat(jdbcTemplate.queryForObject("SELECT commercial_snapshot::text FROM budget WHERE budget_id = ?", String.class, draft.getBudgetId())).isEqualTo(snapshot);
        var events = outboxRows(draft.getBudgetId());
        assertThat(events).hasSize(previousEvents.size() + 1);
        assertThat(events).extracting(event -> event.get("event_type")).containsExactlyInAnyOrderElementsOf(
                initial == BudgetStatus.DRAFT ? List.of("OrcamentoAprovado") : List.of("OrcamentoEnviado", "OrcamentoAprovado"));
        assertThat(events).containsAll(previousEvents);
        String approvalEnvelope = jdbcTemplate.queryForObject("SELECT envelope::text FROM integration_event_outbox WHERE budget_id = ? AND event_type = 'OrcamentoAprovado'", String.class, draft.getBudgetId());
        var approvalPayload = jsonMapper.readTree(approvalEnvelope).get("payload");
        assertThat(approvalPayload.size()).isEqualTo(6);
        assertThat(approvalPayload.get("compositionVersion").asInt()).isEqualTo(1);
        assertThat(approvalPayload.get("currency").asString()).isEqualTo("BRL");
        assertThat(approvalEnvelope).doesNotContain("validated-subject", "approvalOrigin", "IN_PERSON");
        assertThat(approved.getApprovedByEmployeeId()).isEqualTo(stored.getApprovedByEmployeeId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "SENT"})
    void shouldRollbackRegisteredApprovalAndAuditWhenOutboxInsertFails(BudgetStatus initial) {
        Budget draft = createDraft(UUID.randomUUID(), BigDecimal.TEN);
        if (initial == BudgetStatus.SENT) gateway.sendForApproval(draft.getBudgetId(), "customer@example.test");
        Budget before = gateway.findById(draft.getBudgetId()).orElseThrow();
        long versionBefore = rowVersion(draft.getBudgetId());
        var eventsBefore = outboxRows(draft.getBudgetId());
        String constraint = "test_in_person_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("ALTER TABLE integration_event_outbox ADD CONSTRAINT " + constraint +
                " CHECK (budget_id <> '" + draft.getBudgetId() + "'::uuid OR event_type <> 'OrcamentoAprovado')");
        try {
            assertThatThrownBy(() -> gateway.registerApproval(draft.getBudgetId(), "validated-subject")).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(gateway.findById(draft.getBudgetId()).orElseThrow()).usingRecursiveComparison().isEqualTo(before);
            assertThat(rowVersion(draft.getBudgetId())).isEqualTo(versionBefore);
            assertThat(outboxRows(draft.getBudgetId())).isEqualTo(eventsBefore);
        } finally {
            jdbcTemplate.execute("ALTER TABLE integration_event_outbox DROP CONSTRAINT " + constraint);
        }
    }

    @Test
    void shouldRollbackRegisteredApprovalAndItsAlreadyInsertedEventIfCallerFails() {
        Budget draft = createDraft(UUID.randomUUID(), BigDecimal.TEN);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            gateway.registerApproval(draft.getBudgetId(), "validated-subject");
            assertThat(outboxRows(draft.getBudgetId())).hasSize(1);
            throw new IllegalStateException("Falha após persistir decisão e evento");
        })).isInstanceOf(IllegalStateException.class);
        Budget stored = gateway.findById(draft.getBudgetId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(BudgetStatus.DRAFT);
        assertThat(stored.getSentAt()).isNull();
        assertThat(stored.getApprovedAt()).isNull();
        assertThat(stored.getApprovalOrigin()).isNull();
        assertThat(stored.getApprovedByEmployeeId()).isNull();
        assertThat(rowVersion(draft.getBudgetId())).isZero();
        assertThat(outboxRows(draft.getBudgetId())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "SENT"})
    void shouldNotRegisterApprovalOfAnObsoleteRevision(BudgetStatus initial) {
        UUID orderId = UUID.randomUUID();
        Budget older = createDraft(orderId, BigDecimal.ONE);
        Budget newer = createDraft(orderId, BigDecimal.TEN);
        if (initial == BudgetStatus.SENT) gateway.sendForApproval(older.getBudgetId(), "customer@example.test");
        gateway.sendForApproval(newer.getBudgetId(), "customer@example.test");
        Budget before = gateway.findById(older.getBudgetId()).orElseThrow();
        var events = outboxRows(older.getBudgetId());
        assertThatThrownBy(() -> gateway.registerApproval(older.getBudgetId(), "validated-subject")).isInstanceOf(ObsoleteBudgetRevisionException.class);
        assertThat(gateway.findById(older.getBudgetId()).orElseThrow()).usingRecursiveComparison().isEqualTo(before);
        assertThat(outboxRows(older.getBudgetId())).isEqualTo(events);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "REJECTED"})
    void shouldNotOverwriteDecidedBudgetOrItsApprovalAudit(BudgetStatus state) {
        Budget draft = createDraft(UUID.randomUUID(), BigDecimal.TEN);
        if (state == BudgetStatus.APPROVED) gateway.registerApproval(draft.getBudgetId(), "original-subject");
        else {
            gateway.sendForApproval(draft.getBudgetId(), "customer@example.test");
            gateway.reject(draft.getBudgetId());
        }
        Budget before = gateway.findById(draft.getBudgetId()).orElseThrow();
        var events = outboxRows(draft.getBudgetId());
        assertThatThrownBy(() -> gateway.registerApproval(draft.getBudgetId(), "another-subject")).isInstanceOf(InvalidBudgetStatusException.class);
        assertThat(gateway.findById(draft.getBudgetId()).orElseThrow()).usingRecursiveComparison().isEqualTo(before);
        assertThat(outboxRows(draft.getBudgetId())).isEqualTo(events);
    }

    @Test
    void shouldSerializeTwoRegisteredApprovalsAndKeepOnlyTheWinningEmployeeAndEvent() throws Exception {
        Budget draft = createDraft(UUID.randomUUID(), BigDecimal.TEN);
        var results = transitionConcurrently(draft.getBudgetId(), 0,
                id -> gateway.registerApproval(id, "subject-one"), id -> gateway.registerApproval(id, "subject-two"));
        var winners = results.stream().filter(result -> result.failure() == null).toList();
        assertThat(winners).hasSize(1);
        assertThat(results.stream().filter(result -> result.failure() != null)).singleElement().satisfies(
                result -> assertThat(result.failure()).isInstanceOf(InvalidBudgetStatusException.class));
        Budget stored = gateway.findById(draft.getBudgetId()).orElseThrow();
        assertThat(stored.getApprovedByEmployeeId()).isEqualTo(winners.getFirst().budget().getApprovedByEmployeeId());
        assertThat(stored.getApprovalOrigin()).isEqualTo(BudgetApprovalOrigin.IN_PERSON);
        assertThat(rowVersion(draft.getBudgetId())).isEqualTo(1);
        assertThat(stored.getBudgetVersion()).isEqualTo(1);
        assertThat(outboxRows(draft.getBudgetId())).extracting(row -> row.get("event_type")).containsExactly("OrcamentoAprovado");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldCoordinateRegisteredApprovalWithDecisionOnAnotherRevision(boolean registrationFirst) throws Exception {
        UUID orderId = UUID.randomUUID();
        Budget first = createDraft(orderId, BigDecimal.ONE);
        Budget second = createDraft(orderId, BigDecimal.TEN);
        gateway.sendForApproval(first.getBudgetId(), "customer@example.test");
        var results = registrationFirst
                ? inLockOrder(() -> gateway.registerApproval(second.getBudgetId(), "validated-subject"), () -> gateway.approve(first.getBudgetId()))
                : inLockOrder(() -> gateway.approve(first.getBudgetId()), () -> gateway.registerApproval(second.getBudgetId(), "validated-subject"));
        assertThat(results.getFirst().failure()).isNull();
        assertThat(results.getLast().failure()).isInstanceOf(registrationFirst ? ObsoleteBudgetRevisionException.class : ApprovedBudgetExistsException.class);
        assertThat(gateway.findById(second.getBudgetId()).orElseThrow().getStatus())
                .isEqualTo(registrationFirst ? BudgetStatus.APPROVED : BudgetStatus.DRAFT);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM integration_event_outbox WHERE service_order_id = ? AND event_type = 'OrcamentoAprovado'", Integer.class, orderId)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00", "100000000000000000.00"})
    void shouldKeepDraftWhenApprovalAmountCannotBeConsumedBySaga(String amount) {
        var budget = gateway.createDraft(UUID.randomUUID(), new BigDecimal(amount), new BudgetComposition(List.of(), 1));

        assertThatThrownBy(() -> gateway.registerApproval(budget.getBudgetId(), "integration-test"))
                .isInstanceOf(BudgetCalculationException.class);
        assertThat(gateway.findById(budget.getBudgetId()).orElseThrow().getStatus()).isEqualTo(BudgetStatus.DRAFT);
        assertThat(outboxRows(budget.getBudgetId())).isEmpty();
    }

    @Test
    void shouldKeepLegacyApprovalOriginUnknownAndPreventEmployeeWithoutOriginInPostgres() {
        Budget draft = createDraft(UUID.randomUUID(), BigDecimal.TEN);
        gateway.sendForApproval(draft.getBudgetId(), "customer@example.test");
        Budget approved = gateway.approve(draft.getBudgetId());
        assertThat(approved.getApprovalOrigin()).isNull();
        assertThat(approved.getApprovedByEmployeeId()).isNull();
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE budget SET approved_by_employee_id = 'untrusted' WHERE budget_id = ?", draft.getBudgetId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @CsvSource({"SENT,OrcamentoEnviado", "APPROVED,OrcamentoAprovado", "REJECTED,OrcamentoRejeitado"})
    void shouldCommitTransitionAndCompletePendingEnvelope(BudgetStatus target, String type) {
        Budget draft = createDraft(UUID.randomUUID(), new BigDecimal("550.24"));
        if (target != BudgetStatus.SENT) gateway.sendForApproval(draft.getBudgetId(), "customer@example.test");
        String sentEnvelope = target == BudgetStatus.SENT ? null : outboxRows(draft.getBudgetId()).getFirst().get("envelope").toString();

        operation(target).apply(draft.getBudgetId());

        var row = jdbcTemplate.queryForMap("SELECT *, envelope::text AS envelope_json FROM integration_event_outbox WHERE budget_id = ? AND event_type = ?",
                draft.getBudgetId(), type);
        var envelope = jsonMapper.readTree(row.get("envelope_json").toString());
        assertThat(envelope.size()).isEqualTo(6);
        assertThat(UUID.fromString(envelope.get("eventId").asString())).isEqualTo(row.get("event_id"));
        assertThat(UUID.fromString(envelope.get("correlationId").asString())).isNotNull();
        assertThat(envelope.get("occurredAt").asString()).endsWith("Z");
        var occurredAt = java.time.Instant.parse(envelope.get("occurredAt").asString());
        assertThat(occurredAt).isCloseTo(java.time.Instant.now(), org.assertj.core.api.Assertions.within(10, java.time.temporal.ChronoUnit.SECONDS));
        assertThat(((java.sql.Timestamp) row.get("created_at")).toInstant()).isEqualTo(occurredAt);
        assertThat(envelope.get("eventType").asString()).isEqualTo(type);
        assertThat(envelope.get("schemaVersion").asInt()).isEqualTo(1);
        var payload = envelope.get("payload");
        assertThat(payload.size()).isEqualTo(target == BudgetStatus.APPROVED ? 6 : 4);
        assertThat(payload.get("compositionVersion").asInt()).isEqualTo(1);
        if (target == BudgetStatus.APPROVED) assertThat(payload.get("currency").asString()).isEqualTo("BRL");
        assertThat(payload.get("serviceOrderId").asString()).isEqualTo(draft.getServiceOrderId().toString());
        assertThat(payload.get("budgetId").asString()).isEqualTo(draft.getBudgetId().toString());
        assertThat(payload.get("budgetVersion").asInt()).isEqualTo(draft.getBudgetVersion());
        assertThat(row.get("budget_version")).isEqualTo(draft.getBudgetVersion());
        assertThat(row.get("service_order_id")).isEqualTo(draft.getServiceOrderId());
        assertThat(row.get("schema_version")).isEqualTo(1);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempt_count")).isEqualTo(0);
        assertThat(row.get("next_attempt_at")).isEqualTo(row.get("created_at"));
        assertThat(row.get("published_at")).isNull();
        assertThat(row.get("last_error")).isNull();
        assertThat(row.get("lease_token")).isNull();
        assertThat(row.get("lease_until")).isNull();
        if (target == BudgetStatus.APPROVED) assertThat(payload.get("approvedAmount").asString()).isEqualTo("550.24");
        if (sentEnvelope != null) {
            assertThat(jdbcTemplate.queryForObject("SELECT envelope::text FROM integration_event_outbox WHERE budget_id = ? AND event_type = 'OrcamentoEnviado'",
                    String.class, draft.getBudgetId())).isEqualTo(sentEnvelope);
        }
        var beforeRead = outboxRows(draft.getBudgetId());
        assertThat(gateway.findById(draft.getBudgetId()).orElseThrow().getBudgetVersion()).isEqualTo(1);
        gateway.findAllByServiceOrderId(draft.getServiceOrderId());
        assertThat(outboxRows(draft.getBudgetId())).isEqualTo(beforeRead);
        assertThat(rowVersion(draft.getBudgetId())).isEqualTo(target == BudgetStatus.SENT ? 1 : 2);
    }

    @ParameterizedTest
    @CsvSource({"SENT,OrcamentoEnviado", "APPROVED,OrcamentoAprovado", "REJECTED,OrcamentoRejeitado"})
    void shouldRollbackTransitionWhenPostgresRejectsOutboxInsert(BudgetStatus target, String type) {
        Budget draft = createDraft(UUID.randomUUID(), BigDecimal.TEN);
        if (target != BudgetStatus.SENT) gateway.sendForApproval(draft.getBudgetId(), "customer@example.test");
        Budget before = gateway.findById(draft.getBudgetId()).orElseThrow();
        var eventsBefore = outboxRows(draft.getBudgetId());
        long versionBefore = rowVersion(draft.getBudgetId());
        String constraint = "test_outbox_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("ALTER TABLE integration_event_outbox ADD CONSTRAINT " + constraint +
                " CHECK (budget_id <> '" + draft.getBudgetId() + "'::uuid OR event_type <> '" + type + "')");
        try {
            assertThatThrownBy(() -> operation(target).apply(draft.getBudgetId())).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(gateway.findById(draft.getBudgetId()).orElseThrow()).usingRecursiveComparison().isEqualTo(before);
            assertThat(rowVersion(draft.getBudgetId())).isEqualTo(versionBefore);
            assertThat(outboxRows(draft.getBudgetId())).isEqualTo(eventsBefore);
        } finally {
            jdbcTemplate.execute("ALTER TABLE integration_event_outbox DROP CONSTRAINT " + constraint);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SENT", "APPROVED", "REJECTED"})
    void shouldNotRecordEventWhenPostgresRejectsBudgetUpdate(BudgetStatus target) {
        Budget draft = createDraft(UUID.randomUUID(), BigDecimal.TEN);
        if (target != BudgetStatus.SENT) gateway.sendForApproval(draft.getBudgetId(), "customer@example.test");
        Budget before = gateway.findById(draft.getBudgetId()).orElseThrow();
        var eventsBefore = outboxRows(draft.getBudgetId());
        long versionBefore = rowVersion(draft.getBudgetId());
        String constraint = "test_budget_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("ALTER TABLE budget ADD CONSTRAINT " + constraint +
                " CHECK (budget_id <> '" + draft.getBudgetId() + "'::uuid OR status <> '" + target.name() + "')");
        try {
            assertThatThrownBy(() -> operation(target).apply(draft.getBudgetId())).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(gateway.findById(draft.getBudgetId()).orElseThrow()).usingRecursiveComparison().isEqualTo(before);
            assertThat(rowVersion(draft.getBudgetId())).isEqualTo(versionBefore);
            assertThat(outboxRows(draft.getBudgetId())).isEqualTo(eventsBefore);
        } finally {
            jdbcTemplate.execute("ALTER TABLE budget DROP CONSTRAINT " + constraint);
        }
    }

    @Test
    void shouldKeepOutboxUnchangedForInvalidAndObsoleteTransitions() {
        UUID orderId = UUID.randomUUID();
        Budget first = createDraft(orderId, BigDecimal.TEN);
        Budget second = createDraft(orderId, BigDecimal.ONE);
        assertThatThrownBy(() -> gateway.approve(first.getBudgetId())).isInstanceOf(InvalidBudgetStatusException.class);
        assertThat(outboxRows(first.getBudgetId())).isEmpty();
        gateway.sendForApproval(first.getBudgetId(), "customer@example.test");
        gateway.sendForApproval(second.getBudgetId(), "customer@example.test");
        var before = outboxRows(first.getBudgetId());
        assertThatThrownBy(() -> gateway.sendForApproval(first.getBudgetId(), "customer@example.test")).isInstanceOf(InvalidBudgetStatusException.class);
        assertThatThrownBy(() -> gateway.approve(first.getBudgetId())).isInstanceOf(ObsoleteBudgetRevisionException.class);
        assertThatThrownBy(() -> gateway.reject(first.getBudgetId())).isInstanceOf(ObsoleteBudgetRevisionException.class);
        assertThat(outboxRows(first.getBudgetId())).isEqualTo(before);
    }

    @Test
    void shouldRequireExistingTransactionAndRejectDuplicateFactInPostgres() {
        Budget draft = createDraft(UUID.randomUUID(), BigDecimal.TEN);
        Budget sent = gateway.sendForApproval(draft.getBudgetId(), "customer@example.test");
        var anotherEvent = eventFactory.create(sent, UUID.randomUUID());
        var before = outboxRows(draft.getBudgetId());
        assertThatThrownBy(() -> outboxRepository.append(anotherEvent)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> outboxRepository.append(anotherEvent))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(outboxRows(draft.getBudgetId())).isEqualTo(before);
    }

    private List<java.util.Map<String, Object>> outboxRows(UUID budgetId) {
        return jdbcTemplate.queryForList("""
                SELECT event_id, event_type, schema_version, service_order_id, budget_id, budget_version,
                    envelope::text AS envelope, status, created_at, published_at, attempt_count,
                    next_attempt_at, last_error, lease_token, lease_until
                FROM integration_event_outbox WHERE budget_id = ? ORDER BY event_id
                """, budgetId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SENT", "APPROVED", "REJECTED"})
    void shouldReadOrderedHistoryAndReferenceWithoutChangingPersistentData(String state) {
        UUID orderId = UUID.randomUUID();
        var context = quotationContext(orderId, UUID.randomUUID());
        var create = new CreateBudgetUseCase(id -> context, ids -> List.of(), gateway);
        Budget first = create.execute(orderId, new BigDecimal("125.50"));
        Budget reference = create.execute(orderId, new BigDecimal("225.50"));
        Budget newer = create.execute(orderId, new BigDecimal("325.50"));
        Budget otherOrder = createDraft(UUID.randomUUID(), BigDecimal.ONE);
        gateway.sendForApproval(first.getBudgetId(), "customer@example.test");
        gateway.sendForApproval(reference.getBudgetId(), "customer@example.test");
        if (state.equals("APPROVED")) gateway.approve(reference.getBudgetId());
        if (state.equals("REJECTED")) gateway.reject(reference.getBudgetId());
        var before = jdbcTemplate.queryForList("SELECT * FROM budget WHERE service_order_id = ? ORDER BY budget_version", orderId);
        int counterBefore = lastVersion(orderId);

        var history = new br.com.fiap.numberone.billing.budget.application.usecases.GetBudgetHistoryUseCase(gateway)
                .execute(orderId);
        var found = new br.com.fiap.numberone.billing.budget.application.usecases.GetBudgetUseCase(gateway, id -> java.util.Optional.empty())
                .execute(reference.getBudgetId()).budget();

        assertThat(history.budgets()).extracting(Budget::getBudgetId)
                .containsExactly(first.getBudgetId(), reference.getBudgetId(), newer.getBudgetId());
        assertThat(history.budgets()).extracting(Budget::getBudgetVersion).containsExactly(1, 2, 3);
        assertThat(history.referenceBudgetId()).isEqualTo(reference.getBudgetId());
        assertThat(found.getStatus()).isEqualTo(BudgetStatus.valueOf(state));
        assertThat(found.getQuotedAmount()).isEqualByComparingTo("225.50");
        assertThat(found.getComposition()).isEqualTo(reference.getComposition());
        assertThat(history.budgets().getFirst().getComposition()).isEqualTo(first.getComposition());
        assertThat(history.budgets().getLast().getComposition()).isEqualTo(newer.getComposition());
        assertThat(lastVersion(orderId)).isEqualTo(counterBefore);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM budget WHERE service_order_id = ? ORDER BY budget_version", orderId))
                .isEqualTo(before);
        assertThat(rowVersion(otherOrder.getBudgetId())).isZero();
    }

    @Test
    void shouldReadEmptyHistoryWithoutCreatingCounterAndDraftHistoryWithoutReference() {
        UUID orderId = UUID.randomUUID();
        var query = new br.com.fiap.numberone.billing.budget.application.usecases.GetBudgetHistoryUseCase(gateway);
        assertThat(query.execute(orderId).budgets()).isEmpty();
        assertThat(query.execute(orderId).referenceBudgetId()).isNull();
        assertThat(counterCount(orderId)).isZero();
        Budget draft = createDraft(orderId, BigDecimal.TEN);
        var history = query.execute(orderId);
        assertThat(history.budgets()).extracting(Budget::getBudgetId).containsExactly(draft.getBudgetId());
        assertThat(history.referenceBudgetId()).isNull();
        assertThat(rowVersion(draft.getBudgetId())).isZero();
        assertThat(lastVersion(orderId)).isEqualTo(1);
    }

    @Test
    void shouldKeepSentRevisionEligibleWhenANewerDraftExists() {
        UUID orderId = UUID.randomUUID();
        Budget first = createDraft(orderId, BigDecimal.TEN);
        gateway.sendForApproval(first.getBudgetId(), "customer@example.test");
        Budget newer = createDraft(orderId, BigDecimal.ONE);
        Budget newerBefore = gateway.findById(newer.getBudgetId()).orElseThrow();

        gateway.approve(first.getBudgetId());

        assertThat(gateway.findById(first.getBudgetId()).orElseThrow().getStatus()).isEqualTo(BudgetStatus.APPROVED);
        assertThat(gateway.findById(newer.getBudgetId()).orElseThrow()).usingRecursiveComparison().isEqualTo(newerBefore);
        assertThat(rowVersion(first.getBudgetId())).isEqualTo(2);
        assertThat(rowVersion(newer.getBudgetId())).isZero();
        assertThat(lastVersion(orderId)).isEqualTo(2);
    }

    @Test
    void shouldKeepTheRejectedReferenceWithoutReactivatingAnOlderSentRevision() {
        UUID orderId = UUID.randomUUID();
        Budget first = createDraft(orderId, BigDecimal.TEN);
        gateway.sendForApproval(first.getBudgetId(), "customer@example.test");
        Budget second = createDraft(orderId, BigDecimal.ONE);
        gateway.sendForApproval(second.getBudgetId(), "customer@example.test");
        Budget firstBefore = gateway.findById(first.getBudgetId()).orElseThrow();

        gateway.reject(second.getBudgetId());

        assertThatThrownBy(() -> gateway.approve(first.getBudgetId())).isInstanceOf(ObsoleteBudgetRevisionException.class);
        assertThatThrownBy(() -> gateway.reject(first.getBudgetId())).isInstanceOf(ObsoleteBudgetRevisionException.class);
        assertThat(gateway.findById(first.getBudgetId()).orElseThrow()).usingRecursiveComparison().isEqualTo(firstBefore);
        assertThat(gateway.findById(second.getBudgetId()).orElseThrow().getStatus()).isEqualTo(BudgetStatus.REJECTED);
        assertThat(rowVersion(first.getBudgetId())).isEqualTo(1);
        assertThat(rowVersion(second.getBudgetId())).isEqualTo(2);
        Budget third = createDraft(orderId, new BigDecimal("30"));
        gateway.sendForApproval(third.getBudgetId(), "customer@example.test");
        gateway.approve(third.getBudgetId());
        assertThat(third.getBudgetVersion()).isEqualTo(3);
        assertThat(gateway.findAllByServiceOrderId(orderId)).extracting(Budget::getStatus)
                .containsExactly(BudgetStatus.SENT, BudgetStatus.REJECTED, BudgetStatus.APPROVED);
    }

    @Test
    void shouldMakeAnOlderSentRevisionObsoleteWithoutUpdatingItsHistory() {
        UUID orderId = UUID.randomUUID();
        Budget first = gateway.createDraft(orderId, new BigDecimal("99.99"),
                new BudgetComposition(List.of(new BudgetComposition.ServiceItem(
                        UUID.randomUUID(), UUID.randomUUID(), null, "PENDING", List.of())), 1));
        gateway.sendForApproval(first.getBudgetId(), "customer@example.test");
        Budget before = gateway.findById(first.getBudgetId()).orElseThrow();
        Budget second = createDraft(orderId, new BigDecimal("150"));

        gateway.sendForApproval(second.getBudgetId(), "customer@example.test");

        assertThatThrownBy(() -> gateway.approve(first.getBudgetId())).isInstanceOf(ObsoleteBudgetRevisionException.class);
        assertThatThrownBy(() -> gateway.reject(first.getBudgetId())).isInstanceOf(ObsoleteBudgetRevisionException.class);
        assertThat(gateway.findById(first.getBudgetId()).orElseThrow()).usingRecursiveComparison().isEqualTo(before);
        assertThat(rowVersion(first.getBudgetId())).isEqualTo(1);
        assertThat(rowVersion(second.getBudgetId())).isEqualTo(1);
        assertThat(lastVersion(orderId)).isEqualTo(2);
        gateway.approve(second.getBudgetId());
        assertThat(gateway.findById(first.getBudgetId()).orElseThrow()).usingRecursiveComparison().isEqualTo(before);
    }

    @Test
    void shouldBlockLateDraftSendingEvenAfterTheNewerRevisionIsRejected() {
        UUID orderId = UUID.randomUUID();
        Budget first = createDraft(orderId, BigDecimal.TEN);
        Budget second = createDraft(orderId, BigDecimal.ONE);
        gateway.sendForApproval(second.getBudgetId(), "customer@example.test");
        Budget before = gateway.findById(first.getBudgetId()).orElseThrow();
        assertThatThrownBy(() -> gateway.sendForApproval(first.getBudgetId(), "customer@example.test")).isInstanceOf(ObsoleteBudgetRevisionException.class);
        gateway.reject(second.getBudgetId());
        assertThatThrownBy(() -> gateway.sendForApproval(first.getBudgetId(), "customer@example.test")).isInstanceOf(ObsoleteBudgetRevisionException.class);
        assertThat(gateway.findById(first.getBudgetId()).orElseThrow()).usingRecursiveComparison().isEqualTo(before);
        assertThat(rowVersion(first.getBudgetId())).isZero();
        assertThat(rowVersion(second.getBudgetId())).isEqualTo(2);
        assertThat(lastVersion(orderId)).isEqualTo(2);
    }

    @Test
    void shouldKeepThePreviousReferenceWhenSendingANewerRevisionRollsBack() {
        UUID orderId = UUID.randomUUID();
        Budget first = createDraft(orderId, BigDecimal.TEN);
        gateway.sendForApproval(first.getBudgetId(), "customer@example.test");
        Budget second = createDraft(orderId, BigDecimal.ONE);
        var transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            gateway.sendForApproval(second.getBudgetId(), "customer@example.test");
            throw new IllegalStateException("Falha simulada após novo envio");
        })).isInstanceOf(IllegalStateException.class);

        gateway.approve(first.getBudgetId());
        assertThat(gateway.findById(second.getBudgetId()).orElseThrow().getStatus()).isEqualTo(BudgetStatus.DRAFT);
        assertThat(rowVersion(second.getBudgetId())).isZero();
        assertThat(rowVersion(first.getBudgetId())).isEqualTo(2);
        assertThat(lastVersion(orderId)).isEqualTo(2);
    }

    @Test
    void shouldBlockNewDraftsAndExistingDraftSendingAfterApprovalWithoutConsumingVersion() {
        UUID orderId = UUID.randomUUID();
        Budget first = createDraft(orderId, BigDecimal.TEN);
        Budget unusedDraft = createDraft(orderId, BigDecimal.ONE);
        gateway.sendForApproval(first.getBudgetId(), "customer@example.test");
        gateway.approve(first.getBudgetId());
        var before = gateway.findAllByServiceOrderId(orderId);

        assertThatThrownBy(() -> createDraft(orderId, new BigDecimal("20"))).isInstanceOf(ApprovedBudgetExistsException.class);
        assertThatThrownBy(() -> gateway.sendForApproval(unusedDraft.getBudgetId(), "customer@example.test")).isInstanceOf(ApprovedBudgetExistsException.class);

        assertThat(gateway.findAllByServiceOrderId(orderId)).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(before);
        assertThat(rowVersion(first.getBudgetId())).isEqualTo(2);
        assertThat(rowVersion(unusedDraft.getBudgetId())).isZero();
        assertThat(lastVersion(orderId)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldSerializeSendingOfDifferentRevisions(boolean oldestFirst) throws Exception {
        UUID orderId = UUID.randomUUID();
        Budget first = createDraft(orderId, BigDecimal.TEN);
        Budget second = createDraft(orderId, BigDecimal.ONE);
        var results = inLockOrder(
                () -> gateway.sendForApproval(oldestFirst ? first.getBudgetId() : second.getBudgetId(), "customer@example.test"),
                () -> gateway.sendForApproval(oldestFirst ? second.getBudgetId() : first.getBudgetId(), "customer@example.test"));

        assertThat(results.getFirst().failure()).isNull();
        if (oldestFirst) assertThat(results.getLast().failure()).isNull();
        else assertThat(results.getLast().failure()).isInstanceOf(ObsoleteBudgetRevisionException.class);
        assertThat(gateway.findById(second.getBudgetId()).orElseThrow().getStatus()).isEqualTo(BudgetStatus.SENT);
        assertThat(rowVersion(second.getBudgetId())).isEqualTo(1);
        assertThat(rowVersion(first.getBudgetId())).isEqualTo(oldestFirst ? 1 : 0);
        assertThat(lastVersion(orderId)).isEqualTo(2);
        assertThatThrownBy(() -> gateway.approve(first.getBudgetId()))
                .isInstanceOf(oldestFirst ? ObsoleteBudgetRevisionException.class : InvalidBudgetStatusException.class);
        gateway.approve(second.getBudgetId());
        assertThat(gateway.findById(second.getBudgetId()).orElseThrow().getBudgetVersion()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldSerializeOldApprovalAgainstSendingANewerRevision(boolean approvalFirst) throws Exception {
        UUID orderId = UUID.randomUUID();
        Budget first = createDraft(orderId, BigDecimal.TEN);
        gateway.sendForApproval(first.getBudgetId(), "customer@example.test");
        Budget second = createDraft(orderId, BigDecimal.ONE);
        var results = approvalFirst
                ? inLockOrder(() -> gateway.approve(first.getBudgetId()), () -> gateway.sendForApproval(second.getBudgetId(), "customer@example.test"))
                : inLockOrder(() -> gateway.sendForApproval(second.getBudgetId(), "customer@example.test"), () -> gateway.approve(first.getBudgetId()));

        assertThat(results.getFirst().failure()).isNull();
        assertThat(results.getLast().failure()).isInstanceOf(approvalFirst
                ? ApprovedBudgetExistsException.class : ObsoleteBudgetRevisionException.class);
        assertThat(gateway.findById(first.getBudgetId()).orElseThrow().getStatus())
                .isEqualTo(approvalFirst ? BudgetStatus.APPROVED : BudgetStatus.SENT);
        assertThat(gateway.findById(second.getBudgetId()).orElseThrow().getStatus())
                .isEqualTo(approvalFirst ? BudgetStatus.DRAFT : BudgetStatus.SENT);
        assertThat(rowVersion(first.getBudgetId())).isEqualTo(approvalFirst ? 2 : 1);
        assertThat(rowVersion(second.getBudgetId())).isEqualTo(approvalFirst ? 0 : 1);
        assertThat(lastVersion(orderId)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldSerializeDecisionsOnDifferentRevisions(boolean approveNewest) throws Exception {
        UUID orderId = UUID.randomUUID();
        Budget first = createDraft(orderId, BigDecimal.TEN);
        gateway.sendForApproval(first.getBudgetId(), "customer@example.test");
        Budget second = createDraft(orderId, BigDecimal.ONE);
        gateway.sendForApproval(second.getBudgetId(), "customer@example.test");
        var results = approveNewest
                ? inLockOrder(() -> gateway.approve(second.getBudgetId()), () -> gateway.reject(first.getBudgetId()))
                : inLockOrder(() -> gateway.reject(second.getBudgetId()), () -> gateway.approve(first.getBudgetId()));

        assertThat(results.getFirst().failure()).isNull();
        assertThat(results.getLast().failure()).isInstanceOf(ObsoleteBudgetRevisionException.class);
        assertThat(gateway.findById(first.getBudgetId()).orElseThrow().getStatus()).isEqualTo(BudgetStatus.SENT);
        assertThat(gateway.findById(second.getBudgetId()).orElseThrow().getStatus())
                .isEqualTo(approveNewest ? BudgetStatus.APPROVED : BudgetStatus.REJECTED);
        assertThat(rowVersion(first.getBudgetId())).isEqualTo(1);
        assertThat(rowVersion(second.getBudgetId())).isEqualTo(2);
        assertThat(lastVersion(orderId)).isEqualTo(2);
    }

    @Test
    void shouldBlockConcurrentDraftCreationWhenApprovalCommitsFirst() throws Exception {
        UUID orderId = UUID.randomUUID();
        Budget first = createDraft(orderId, BigDecimal.TEN);
        gateway.sendForApproval(first.getBudgetId(), "customer@example.test");
        var results = inLockOrder(() -> gateway.approve(first.getBudgetId()), () -> createDraft(orderId, BigDecimal.ONE));
        assertThat(results.getFirst().failure()).isNull();
        assertThat(results.getLast().failure()).isInstanceOf(ApprovedBudgetExistsException.class);
        assertThat(lastVersion(orderId)).isEqualTo(1);
        assertThat(gateway.findAllByServiceOrderId(orderId)).hasSize(1);
    }

    @Test
    void shouldKeepANewDraftUnusableWhenItsCreationCommitsBeforeApproval() throws Exception {
        UUID orderId = UUID.randomUUID();
        Budget first = createDraft(orderId, BigDecimal.TEN);
        gateway.sendForApproval(first.getBudgetId(), "customer@example.test");
        var results = inLockOrder(() -> createDraft(orderId, BigDecimal.ONE), () -> gateway.approve(first.getBudgetId()));
        assertThat(results).allSatisfy(result -> assertThat(result.failure()).isNull());
        Budget second = results.getFirst().budget();
        assertThat(second.getBudgetVersion()).isEqualTo(2);
        assertThat(lastVersion(orderId)).isEqualTo(2);
        assertThatThrownBy(() -> gateway.sendForApproval(second.getBudgetId(), "customer@example.test")).isInstanceOf(ApprovedBudgetExistsException.class);
        assertThat(rowVersion(second.getBudgetId())).isZero();
    }

    @Test
    void shouldNotBlockTransitionsOnAnotherServiceOrder() throws Exception {
        Budget first = createDraft(UUID.randomUUID(), BigDecimal.TEN);
        Budget second = createDraft(UUID.randomUUID(), BigDecimal.ONE);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            try {
                Future<Budget> blockedOrder = workers.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                    Budget sent = gateway.sendForApproval(first.getBudgetId(), "customer@example.test");
                    locked.countDown();
                    awaitSignal(release);
                    return sent;
                }));
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                Future<Budget> independent = workers.submit(() -> {
                    gateway.sendForApproval(second.getBudgetId(), "customer@example.test");
                    return gateway.approve(second.getBudgetId());
                });
                assertThat(independent.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(BudgetStatus.APPROVED);
                assertThat(blockedOrder.isDone()).isFalse();
                release.countDown();
                assertThat(blockedOrder.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(BudgetStatus.SENT);
            } finally {
                release.countDown();
            }
        }
        assertThat(rowVersion(first.getBudgetId())).isEqualTo(1);
        assertThat(rowVersion(second.getBudgetId())).isEqualTo(2);
        assertThat(lastVersion(first.getServiceOrderId())).isEqualTo(1);
        assertThat(lastVersion(second.getServiceOrderId())).isEqualTo(1);
    }

    private List<TransitionResult> inLockOrder(java.util.function.Supplier<Budget> first,
                                              java.util.function.Supplier<Budget> second) throws Exception {
        CountDownLatch applied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            try {
                Future<TransitionResult> leading = workers.submit(() -> attempt(() ->
                        new TransactionTemplate(transactionManager).execute(status -> {
                            Budget result = first.get();
                            applied.countDown();
                            awaitSignal(release);
                            return result;
                        })));
                assertThat(applied.await(10, TimeUnit.SECONDS)).isTrue();
                Future<TransitionResult> following = workers.submit(() -> {
                    started.countDown();
                    return attempt(second);
                });
                assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> following.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                release.countDown();
                return List.of(leading.get(20, TimeUnit.SECONDS), following.get(20, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
        }
    }

    private TransitionResult attempt(java.util.function.Supplier<Budget> operation) {
        try {
            return new TransitionResult(operation.get(), null);
        } catch (RuntimeException failure) {
            return new TransitionResult(null, failure);
        }
    }

    private void awaitSignal(CountDownLatch signal) {
        try {
            if (!signal.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Tempo esgotado aguardando teste concorrente");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @Autowired
    private BudgetPersistenceMapper persistenceMapper;

    @Test
    void shouldKeepOptimisticProtectionAgainstAStaleManagedEntity() throws Exception {
        Budget draft = createDraft(UUID.randomUUID(), BigDecimal.TEN);
        gateway.sendForApproval(draft.getBudgetId(), "customer@example.test");
        Function<UUID, Budget> approveDirectly = id -> {
            BudgetEntity entity = entityManager.find(BudgetEntity.class, id);
            Budget budget = persistenceMapper.toDomain(entity);
            budget.approve();
            persistenceMapper.updateState(budget, entity);
            entityManager.flush();
            return budget;
        };
        Function<UUID, Budget> rejectDirectly = id -> {
            BudgetEntity entity = entityManager.find(BudgetEntity.class, id);
            Budget budget = persistenceMapper.toDomain(entity);
            budget.reject();
            persistenceMapper.updateState(budget, entity);
            entityManager.flush();
            return budget;
        };
        // Teste específico de @Version: escrita JPA direta não representa o protocolo do gateway.
        var results = transitionConcurrently(draft.getBudgetId(), 1, approveDirectly, rejectDirectly);
        assertThat(results.stream().filter(result -> result.failure() == null)).hasSize(1);
        var failures = results.stream().filter(result -> result.failure() != null).toList();
        assertThat(failures).hasSize(1);
        assertThat(failures.getFirst().failure()).isInstanceOf(OptimisticLockException.class);
        assertThat(rowVersion(draft.getBudgetId())).isEqualTo(2);
    }

    @ParameterizedTest(name = "{0} concorrente com {1}")
    @CsvSource({"APPROVED,REJECTED", "APPROVED,APPROVED", "SENT,SENT"})
    void shouldSerializeConcurrentTransitionsAndPreserveTheConfirmedResult(BudgetStatus first, BudgetStatus second)
            throws Exception {
        var composition = new BudgetComposition(List.of(new BudgetComposition.ServiceItem(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("50.25"), "PENDING",
                List.of(new BudgetComposition.Supply(UUID.randomUUID(), 2, new BigDecimal("40.00"), "BRL")))), 1);
        Budget draft = gateway.createDraft(UUID.randomUUID(), new BigDecimal("130.25"), composition);
        if (first != BudgetStatus.SENT) gateway.sendForApproval(draft.getBudgetId(), "customer@example.test");
        Budget before = gateway.findById(draft.getBudgetId()).orElseThrow();
        long initialRowVersion = rowVersion(before.getBudgetId());
        String snapshot = jdbcTemplate.queryForObject(
                "SELECT commercial_snapshot::text FROM budget WHERE budget_id = ?", String.class, before.getBudgetId());

        List<TransitionResult> results = transitionConcurrently(before.getBudgetId(), initialRowVersion,
                operation(first), operation(second));

        var successes = results.stream().filter(result -> result.failure() == null).toList();
        var failures = results.stream().filter(result -> result.failure() != null).toList();
        assertThat(successes).hasSize(1);
        assertThat(failures).hasSize(1);
        assertThat(failures.getFirst().failure()).isInstanceOf(InvalidBudgetStatusException.class);
        Budget winner = successes.getFirst().budget();
        var committedEvents = outboxRows(before.getBudgetId());
        assertThat(committedEvents).hasSize(first == BudgetStatus.SENT ? 1 : 2);
        assertThat(committedEvents).extracting(row -> row.get("event_type")).doesNotHaveDuplicates();
        String winningType = switch (winner.getStatus()) {
            case SENT -> "OrcamentoEnviado";
            case APPROVED -> "OrcamentoAprovado";
            case REJECTED -> "OrcamentoRejeitado";
            default -> throw new AssertionError("Estado inesperado");
        };
        assertThat(committedEvents).extracting(row -> row.get("event_type")).contains(winningType);
        Budget stored = gateway.findById(before.getBudgetId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(winner.getStatus());
        assertThat(stored.getBudgetId()).isEqualTo(before.getBudgetId());
        assertThat(stored.getServiceOrderId()).isEqualTo(before.getServiceOrderId());
        assertThat(stored.getBudgetVersion()).isEqualTo(before.getBudgetVersion());
        assertThat(stored.getQuotedAmount()).isEqualByComparingTo(before.getQuotedAmount());
        assertThat(stored.getApprovedAmount()).isEqualTo(winner.getApprovedAmount());
        assertThat(stored.getComposition()).isEqualTo(composition);
        assertThat(stored.getCreatedAt()).isEqualTo(before.getCreatedAt());
        assertThat(stored.getSentAt()).isNotNull();
        assertThat(stored.getSentAt()).isCloseTo(winner.getSentAt(),
                org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MICROS));
        if (winner.getStatus() == BudgetStatus.APPROVED) {
            assertThat(stored.getApprovedAmount()).isEqualByComparingTo(stored.getQuotedAmount());
            assertThat(stored.getApprovedAt()).isNotNull().isCloseTo(winner.getApprovedAt(),
                    org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MICROS));
        } else {
            assertThat(stored.getApprovedAt()).isNull();
            assertThat(stored.getApprovedAmount()).isNull();
        }
        if (first != BudgetStatus.SENT) assertThat(stored.getSentAt()).isEqualTo(before.getSentAt());
        assertThat(rowVersion(before.getBudgetId())).isEqualTo(initialRowVersion + 1);
        assertThat(lastVersion(before.getServiceOrderId())).isEqualTo(before.getBudgetVersion());
        assertThat(jdbcTemplate.queryForObject("SELECT commercial_snapshot::text FROM budget WHERE budget_id = ?",
                String.class, before.getBudgetId())).isEqualTo(snapshot);
        assertThat(gateway.findAllByServiceOrderId(before.getServiceOrderId())).hasSize(1);

        assertThatThrownBy(() -> operation(first).apply(before.getBudgetId())).isInstanceOf(InvalidBudgetStatusException.class);
        assertThat(rowVersion(before.getBudgetId())).isEqualTo(initialRowVersion + 1);
        assertThat(gateway.findById(before.getBudgetId()).orElseThrow()).usingRecursiveComparison().isEqualTo(stored);
    }

    @Test
    void shouldPersistRejectionAndKeepCommercialValuesAndSendingDate() {
        Budget draft = createDraft(UUID.randomUUID(), new BigDecimal("123.45"));
        gateway.sendForApproval(draft.getBudgetId(), "customer@example.test");
        Budget before = gateway.findById(draft.getBudgetId()).orElseThrow();

        new RejectBudgetUseCase(gateway).execute(draft.getBudgetId());

        Budget rejected = gateway.findById(draft.getBudgetId()).orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo(BudgetStatus.REJECTED);
        assertThat(rejected.getApprovedAmount()).isNull();
        assertThat(rejected.getApprovedAt()).isNull();
        assertThat(rejected.getSentAt()).isEqualTo(before.getSentAt());
        assertThat(rejected.getQuotedAmount()).isEqualByComparingTo(before.getQuotedAmount());
        assertThat(rejected.getComposition()).isEqualTo(before.getComposition());
        assertThat(rowVersion(draft.getBudgetId())).isEqualTo(2);
    }

    @Test
    void shouldRollbackApprovalAndItsTechnicalVersion() {
        Budget draft = createDraft(UUID.randomUUID(), BigDecimal.TEN);
        gateway.sendForApproval(draft.getBudgetId(), "customer@example.test");
        Budget before = gateway.findById(draft.getBudgetId()).orElseThrow();
        var transaction = new TransactionTemplate(transactionManager);
        var eventsBefore = outboxRows(draft.getBudgetId());

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            new ApproveBudgetUseCase(gateway).execute(draft.getBudgetId());
            throw new IllegalStateException("Falha simulada após aprovação");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(gateway.findById(draft.getBudgetId()).orElseThrow()).usingRecursiveComparison().isEqualTo(before);
        assertThat(rowVersion(draft.getBudgetId())).isEqualTo(1);
        assertThat(outboxRows(draft.getBudgetId())).isEqualTo(eventsBefore);
    }

    private List<TransitionResult> transitionConcurrently(UUID budgetId, long initialRowVersion,
                                                          Function<UUID, Budget> first,
                                                          Function<UUID, Budget> second) throws Exception {
        CyclicBarrier loaded = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            List<Future<TransitionResult>> futures = new ArrayList<>();
            for (Function<UUID, Budget> operation : List.of(first, second)) {
                futures.add(workers.submit(() -> {
                    try {
                        Budget result = new TransactionTemplate(transactionManager).execute(status -> {
                            BudgetEntity entity = entityManager.find(BudgetEntity.class, budgetId);
                            assertThat(entity.getRowVersion()).isEqualTo(initialRowVersion);
                            // As duas transações leem a mesma versão antes de executar o gateway real.
                            try {
                                loaded.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(exception);
                            } catch (Exception exception) {
                                throw new IllegalStateException(exception);
                            }
                            return operation.apply(budgetId);
                        });
                        return new TransitionResult(result, null);
                    } catch (RuntimeException exception) {
                        return new TransitionResult(null, exception);
                    }
                }));
            }
            return List.of(futures.get(0).get(25, TimeUnit.SECONDS), futures.get(1).get(25, TimeUnit.SECONDS));
        }
    }

    private Function<UUID, Budget> operation(BudgetStatus targetStatus) {
        return switch (targetStatus) {
            case APPROVED -> new ApproveBudgetUseCase(gateway)::execute;
            case REJECTED -> new RejectBudgetUseCase(gateway)::execute;
            case SENT -> new SendBudgetForApprovalUseCase(gateway, id ->
                    new ServiceOrderQuotationContext(id, "IN_DIAGNOSIS", List.of(), "cliente@example.com", 1))::execute;
            default -> throw new IllegalArgumentException("Transição não exercitada neste teste: " + targetStatus);
        };
    }

    private long rowVersion(UUID budgetId) {
        return jdbcTemplate.queryForObject("SELECT row_version FROM budget WHERE budget_id = ?", Long.class, budgetId);
    }

    private record TransitionResult(Budget budget, RuntimeException failure) {
    }

    @Test
    void shouldPersistSendingWithoutChangingCommercialSnapshotIdentityOrVersion() {
        UUID orderId = UUID.randomUUID();
        var composition = new BudgetComposition(List.of(new BudgetComposition.ServiceItem(
                UUID.randomUUID(), UUID.randomUUID(), null, null, List.of())), 1);
        Budget draft = gateway.createDraft(orderId, new BigDecimal("123.45"), composition);
        var persistedCreatedAt = gateway.findById(draft.getBudgetId()).orElseThrow().getCreatedAt();
        String snapshot = jdbcTemplate.queryForObject(
                "SELECT commercial_snapshot::text FROM budget WHERE budget_id = ?", String.class, draft.getBudgetId());
        var useCase = new SendBudgetForApprovalUseCase(gateway, id ->
                new ServiceOrderQuotationContext(id, "IN_DIAGNOSIS", List.of(), "cliente@example.com", 1));

        Budget sent = useCase.execute(draft.getBudgetId());
        Budget stored = gateway.findById(draft.getBudgetId()).orElseThrow();

        assertThat(stored.getStatus()).isEqualTo(BudgetStatus.SENT);
        assertThat(stored.getSentAt()).isNotNull().isCloseTo(sent.getSentAt(),
                org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MICROS));
        assertThat(stored.getUpdatedAt()).isEqualTo(stored.getSentAt());
        assertThat(stored.getBudgetId()).isEqualTo(draft.getBudgetId());
        assertThat(stored.getServiceOrderId()).isEqualTo(orderId);
        assertThat(stored.getBudgetVersion()).isEqualTo(draft.getBudgetVersion());
        assertThat(stored.getQuotedAmount()).isEqualByComparingTo(draft.getQuotedAmount());
        assertThat(stored.getComposition()).isEqualTo(composition);
        assertThat(stored.getCreatedAt()).isEqualTo(persistedCreatedAt);
        assertThat(jdbcTemplate.queryForObject("SELECT commercial_snapshot::text FROM budget WHERE budget_id = ?",
                String.class, draft.getBudgetId())).isEqualTo(snapshot);
        assertThat(gateway.findAllByServiceOrderId(orderId)).hasSize(1);
        assertThatThrownBy(() -> useCase.execute(draft.getBudgetId()))
                .isInstanceOf(br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetStatusException.class);
        assertThat(gateway.findById(draft.getBudgetId()).orElseThrow().getSentAt()).isEqualTo(stored.getSentAt());
    }

    @Test
    void shouldNotPersistSendingWithoutEmail() {
        Budget draft = gateway.createDraft(UUID.randomUUID(), BigDecimal.TEN, new BudgetComposition(List.of(), 1));
        var useCase = new SendBudgetForApprovalUseCase(gateway, id ->
                new ServiceOrderQuotationContext(id, "IN_DIAGNOSIS", List.of(), null, 1));

        assertThatThrownBy(() -> useCase.execute(draft.getBudgetId())).isInstanceOf(CustomerEmailException.class);
        Budget stored = gateway.findById(draft.getBudgetId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(BudgetStatus.DRAFT);
        assertThat(stored.getSentAt()).isNull();
    }

    @Test
    void shouldRollbackSendingWhenLocalTransactionFails() {
        Budget draft = gateway.createDraft(UUID.randomUUID(), BigDecimal.TEN, new BudgetComposition(List.of(), 1));
        var useCase = new SendBudgetForApprovalUseCase(gateway, id ->
                new ServiceOrderQuotationContext(id, "IN_DIAGNOSIS", List.of(), "cliente@example.com", 1));
        var transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            useCase.execute(draft.getBudgetId());
            throw new IllegalStateException("Falha simulada após persistência");
        })).isInstanceOf(IllegalStateException.class);
        Budget stored = gateway.findById(draft.getBudgetId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(BudgetStatus.DRAFT);
        assertThat(stored.getSentAt()).isNull();
    }

    @Test
    void shouldConnectToPostgresAndApplyBudgetMigration() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }

        Integer migrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history",
                Integer.class
        );

        assertThat(migrationCount).isEqualTo(13);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("13");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void shouldPersistHistoricalRevisionWithoutChangingItsValuesOrDates() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 11, 1, 10, 0);
        Budget historical = Budget.restore(UUID.randomUUID(), UUID.randomUUID(), 3,
                new BigDecimal("350.123456"), new BigDecimal("300.123456"), BudgetStatus.APPROVED,
                createdAt.plusHours(1), createdAt.plusHours(2), createdAt, createdAt.plusHours(3));

        insertHistoricalBudget(historical);
        Budget restored = gateway.findById(historical.getBudgetId()).orElseThrow();

        assertThat(restored).usingRecursiveComparison().isEqualTo(historical);
        assertThat(restored.getComposition()).isNull();
    }

    @Test
    void shouldUpdateTheSameRevisionThroughItsLifecycle() {
        Budget draft = createDraft(UUID.randomUUID(), new BigDecimal("350.00"));
        gateway.sendForApproval(draft.getBudgetId(), "customer@example.test");
        Budget sent = gateway.findById(draft.getBudgetId()).orElseThrow();
        assertThat(sent.getStatus()).isEqualTo(BudgetStatus.SENT);
        assertThat(sent.getSentAt()).isNotNull();

        gateway.approve(sent.getBudgetId());
        Budget approved = gateway.findById(sent.getBudgetId()).orElseThrow();

        assertThat(approved.getStatus()).isEqualTo(BudgetStatus.APPROVED);
        assertThat(approved.getBudgetVersion()).isEqualTo(1);
        assertThat(approved.getApprovedAmount()).isEqualByComparingTo("350.00");
        assertThat(approved.getApprovedAt()).isNotNull();
        assertThat(gateway.findAllByServiceOrderId(draft.getServiceOrderId())).hasSize(1);
    }

    @Test
    void shouldListRevisionsByVersionAndPreserveEarlierDecision() {
        UUID serviceOrderId = UUID.randomUUID();
        Budget rejected = createDraft(serviceOrderId, new BigDecimal("200.00"));
        gateway.sendForApproval(rejected.getBudgetId(), "customer@example.test");
        gateway.reject(rejected.getBudgetId());
        Budget approved = createDraft(serviceOrderId, new BigDecimal("250.00"));
        gateway.sendForApproval(approved.getBudgetId(), "customer@example.test");
        gateway.approve(approved.getBudgetId());
        createDraft(UUID.randomUUID(), BigDecimal.TEN);

        assertThat(gateway.findAllByServiceOrderId(serviceOrderId))
                .extracting(Budget::getBudgetVersion).containsExactly(1, 2);
        Budget first = gateway.findByServiceOrderIdAndBudgetVersion(serviceOrderId, 1).orElseThrow();
        Budget second = gateway.findByServiceOrderIdAndBudgetVersion(serviceOrderId, 2).orElseThrow();
        assertThat(first.getBudgetId()).isEqualTo(rejected.getBudgetId());
        assertThat(first.getStatus()).isEqualTo(BudgetStatus.REJECTED);
        assertThat(first.getQuotedAmount()).isEqualByComparingTo("200.00");
        assertThat(first.getApprovedAmount()).isNull();
        assertThat(second.getBudgetId()).isEqualTo(approved.getBudgetId());
        assertThat(second.getStatus()).isEqualTo(BudgetStatus.APPROVED);
    }

    @Test
    void shouldRejectDuplicateVersionForSameServiceOrder() {
        UUID serviceOrderId = UUID.randomUUID();
        Budget original = createDraft(serviceOrderId, BigDecimal.TEN);

        assertThatThrownBy(() -> insertHistoricalBudget(draftFixture(serviceOrderId, 1, BigDecimal.ONE)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_budget_service_order_version");
        assertThat(gateway.findAllByServiceOrderId(serviceOrderId))
                .extracting(Budget::getBudgetId).containsExactly(original.getBudgetId());
    }

    @Test
    void shouldAllowSameVersionForDifferentServiceOrders() {
        Budget first = createDraft(UUID.randomUUID(), BigDecimal.TEN);
        Budget second = createDraft(UUID.randomUUID(), BigDecimal.ONE);

        assertThat(gateway.findByServiceOrderIdAndBudgetVersion(first.getServiceOrderId(), 1))
                .isPresent().get().extracting(Budget::getBudgetId).isEqualTo(first.getBudgetId());
        assertThat(gateway.findByServiceOrderIdAndBudgetVersion(second.getServiceOrderId(), 1))
                .isPresent().get().extracting(Budget::getBudgetId).isEqualTo(second.getBudgetId());
    }

    @Test
    void shouldReturnEmptyForUnknownBudgetOrRevision() {
        assertThat(gateway.findById(UUID.randomUUID())).isEmpty();
        assertThat(gateway.findByServiceOrderIdAndBudgetVersion(UUID.randomUUID(), 1)).isEmpty();
        assertThat(gateway.findAllByServiceOrderId(UUID.randomUUID())).isEmpty();
        Budget saved = createDraft(UUID.randomUUID(), BigDecimal.TEN);
        assertThat(gateway.findByServiceOrderIdAndBudgetVersion(saved.getServiceOrderId(), 2)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void shouldEnforcePositiveVersionInPostgres(int version) {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO budget (budget_id, service_order_id, budget_version, quoted_amount, status, created_at)
                VALUES (?, ?, ?, 10, 'DRAFT', CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), UUID.randomUUID(), version))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_budget_version_positive");
    }

    @Test
    void shouldAllocateVersionsConcurrentlyStartingWithAnAbsentCounter() throws Exception {
        UUID serviceOrderId = UUID.randomUUID();

        List<Budget> created = createConcurrently(Collections.nCopies(8, serviceOrderId));

        assertThat(created).extracting(Budget::getBudgetId).doesNotHaveDuplicates();
        assertThat(created).extracting(Budget::getBudgetVersion)
                .containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(gateway.findAllByServiceOrderId(serviceOrderId))
                .extracting(Budget::getBudgetVersion).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(lastVersion(serviceOrderId)).isEqualTo(8);
    }

    @Test
    void shouldMaintainIndependentSequencesForConcurrentServiceOrders() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        createConcurrently(List.of(first, second, third, first, second, third));

        for (UUID serviceOrderId : List.of(first, second, third)) {
            assertThat(gateway.findAllByServiceOrderId(serviceOrderId))
                    .extracting(Budget::getBudgetVersion).containsExactly(1, 2);
            assertThat(lastVersion(serviceOrderId)).isEqualTo(2);
        }
    }

    @Test
    void shouldNotBlockAnotherServiceOrderWhileFirstTransactionIsOpen() throws Exception {
        UUID firstOrderId = UUID.randomUUID();
        UUID secondOrderId = UUID.randomUUID();
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<Budget> first = workers.submit(() -> transaction.execute(status -> {
                Budget budget = createDraft(firstOrderId, BigDecimal.TEN);
                firstInserted.countDown();
                await(releaseFirst);
                return budget;
            }));
            try {
                await(firstInserted);
                Future<Budget> second = workers.submit(() -> createDraft(secondOrderId, BigDecimal.ONE));
                Budget independent = second.get(10, TimeUnit.SECONDS);
                assertThat(independent.getBudgetVersion()).isEqualTo(1);
                assertThat(gateway.findById(independent.getBudgetId())).isPresent();
                assertThat(releaseFirst.getCount()).isEqualTo(1);
            } finally {
                releaseFirst.countDown();
            }
            assertThat(first.get(10, TimeUnit.SECONDS).getBudgetVersion()).isEqualTo(1);
        }
    }

    @Test
    void shouldRollbackFirstCounterWhenBudgetInsertFails() {
        UUID serviceOrderId = UUID.randomUUID();
        insertHistoricalBudget(draftFixture(serviceOrderId, 1, BigDecimal.TEN));

        assertThatThrownBy(() -> createDraft(serviceOrderId, BigDecimal.ONE))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_budget_service_order_version");

        assertThat(counterCount(serviceOrderId)).isZero();
        assertThat(gateway.findAllByServiceOrderId(serviceOrderId)).hasSize(1);
    }

    @Test
    void shouldRollbackIncrementWhenBudgetInsertFailsAndAllowRetry() {
        UUID serviceOrderId = UUID.randomUUID();
        createDraft(serviceOrderId, BigDecimal.TEN);
        Budget conflicting = draftFixture(serviceOrderId, 2, BigDecimal.ONE);
        insertHistoricalBudget(conflicting);

        assertThatThrownBy(() -> createDraft(serviceOrderId, BigDecimal.TEN))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_budget_service_order_version");

        assertThat(lastVersion(serviceOrderId)).isEqualTo(1);
        assertThat(gateway.findAllByServiceOrderId(serviceOrderId)).hasSize(2);
        jdbcTemplate.update("DELETE FROM budget WHERE budget_id = ?", conflicting.getBudgetId());
        assertThat(createDraft(serviceOrderId, BigDecimal.TEN).getBudgetVersion()).isEqualTo(2);
        assertThat(lastVersion(serviceOrderId)).isEqualTo(2);
    }

    @Test
    void shouldRequireTransactionToIncrementCounter() {
        UUID serviceOrderId = UUID.randomUUID();

        assertThatThrownBy(() -> counterRepository.nextVersion(serviceOrderId))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(counterCount(serviceOrderId)).isZero();
    }

    @Test
    void shouldRejectUpdatingAnUnknownBudgetInsteadOfInsertingIt() {
        Budget unknown = draftFixture(UUID.randomUUID(), 15, BigDecimal.ONE);

        assertThatThrownBy(() -> gateway.sendForApproval(unknown.getBudgetId(), "customer@example.test")).isInstanceOf(BudgetNotFoundException.class);
        assertThatThrownBy(() -> gateway.approve(unknown.getBudgetId())).isInstanceOf(BudgetNotFoundException.class);
        assertThatThrownBy(() -> gateway.reject(unknown.getBudgetId())).isInstanceOf(BudgetNotFoundException.class);
        assertThat(gateway.findById(unknown.getBudgetId())).isEmpty();
        assertThat(counterCount(unknown.getServiceOrderId())).isZero();
    }

    @Test
    void shouldApplyTransitionToPersistedStateInsteadOfDetachedChanges() {
        Budget original = createDraft(UUID.randomUUID(), BigDecimal.TEN);
        Budget detached = gateway.findById(original.getBudgetId()).orElseThrow();
        detached.markAsSent();

        assertThatThrownBy(() -> gateway.approve(detached.getBudgetId())).isInstanceOf(InvalidBudgetStatusException.class);
        Budget persistedDraft = gateway.findById(original.getBudgetId()).orElseThrow();
        assertThat(persistedDraft.getStatus()).isEqualTo(BudgetStatus.DRAFT);
        assertThat(persistedDraft.getSentAt()).isNull();
        gateway.sendForApproval(detached.getBudgetId(), "customer@example.test");
        gateway.approve(detached.getBudgetId());
        Budget persisted = gateway.findById(original.getBudgetId()).orElseThrow();
        assertThat(persisted.getServiceOrderId()).isEqualTo(original.getServiceOrderId());
        assertThat(persisted.getBudgetVersion()).isEqualTo(1);
        assertThat(persisted.getStatus()).isEqualTo(BudgetStatus.APPROVED);
        assertThat(lastVersion(original.getServiceOrderId())).isEqualTo(1);
    }

    @Test
    void shouldInitializeV2CountersFromBudgetsPersistedAfterV1() {
        String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA " + schema);
        Flyway v1 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .createSchemas(false).locations("classpath:db/migration").target("1").load();
        v1.migrate();
        UUID firstOrder = UUID.randomUUID();
        UUID secondOrder = UUID.randomUUID();
        for (int version : List.of(1, 3)) {
            jdbcTemplate.update("INSERT INTO " + schema + ".budget " +
                            "(budget_id, service_order_id, budget_version, quoted_amount, status, created_at) " +
                            "VALUES (?, ?, ?, 10, 'REJECTED', CURRENT_TIMESTAMP)",
                    UUID.randomUUID(), firstOrder, version);
        }
        jdbcTemplate.update("INSERT INTO " + schema + ".budget " +
                        "(budget_id, service_order_id, budget_version, quoted_amount, status, created_at) " +
                        "VALUES (?, ?, 5, 20, 'APPROVED', CURRENT_TIMESTAMP)", UUID.randomUUID(), secondOrder);

        Flyway v2 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .createSchemas(false).locations("classpath:db/migration").target("2").load();
        assertThat(v2.migrate().migrationsExecuted).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject("SELECT last_version FROM " + schema +
                ".budget_revision_counter WHERE service_order_id = ?", Integer.class, firstOrder)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT last_version FROM " + schema +
                ".budget_revision_counter WHERE service_order_id = ?", Integer.class, secondOrder)).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + schema + ".budget", Integer.class))
                .isEqualTo(3);
        assertThat(v2.info().current().getVersion().getVersion()).isEqualTo("2");
        assertThat(v2.validateWithResult().validationSuccessful).isTrue();
        assertThat(v2.info().pending()).isEmpty();
        Flyway v3 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .createSchemas(false).locations("classpath:db/migration").target("3").load();
        assertThat(v3.info().pending()).hasSize(1);
        assertThat(v3.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + schema +
                ".budget WHERE commercial_snapshot IS NULL", Integer.class)).isEqualTo(3);
        assertThat(v3.info().current().getVersion().getVersion()).isEqualTo("3");
        assertThat(v3.validateWithResult().validationSuccessful).isTrue();
        assertThat(v3.info().pending()).isEmpty();

        jdbcTemplate.update("UPDATE " + schema + ".budget SET commercial_snapshot = ?::jsonb WHERE service_order_id = ?",
                "{\"items\":[]}", firstOrder);
        String historicalRows = "SELECT budget_id, service_order_id, budget_version, quoted_amount, approved_amount, " +
                "status, sent_at, approved_at, created_at, updated_at, commercial_snapshot::text FROM " +
                schema + ".budget ORDER BY budget_id";
        var beforeV4 = jdbcTemplate.queryForList(historicalRows);
        Flyway v4 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .createSchemas(false).locations("classpath:db/migration").target("4").load();
        assertThat(v4.info().pending()).hasSize(1);
        assertThat(v4.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList(historicalRows)).isEqualTo(beforeV4);
        assertThat(jdbcTemplate.queryForList("SELECT row_version FROM " + schema + ".budget", Long.class))
                .containsExactly(0L, 0L, 0L);
        assertThat(v4.info().current().getVersion().getVersion()).isEqualTo("4");
        assertThat(v4.validateWithResult().validationSuccessful).isTrue();
        assertThat(v4.info().pending()).isEmpty();
        var beforeV5 = jdbcTemplate.queryForList("SELECT * FROM " + schema + ".budget ORDER BY budget_id");
        Flyway v5 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .createSchemas(false).locations("classpath:db/migration").target("5").load();
        assertThat(v5.info().pending()).hasSize(1);
        assertThat(v5.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM " + schema + ".budget ORDER BY budget_id")).isEqualTo(beforeV5);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + schema + ".integration_event_outbox", Integer.class)).isZero();
        assertThat(v5.info().current().getVersion().getVersion()).isEqualTo("5");
        assertThat(v5.validateWithResult().validationSuccessful).isTrue();
        assertThat(v5.info().pending()).isEmpty();
        assertThat(jdbcTemplate.queryForList("SELECT data_type FROM information_schema.columns WHERE table_schema = ? AND table_name = 'integration_event_outbox' AND column_name IN ('created_at', 'published_at', 'next_attempt_at', 'lease_until')", String.class, schema))
                .containsExactlyInAnyOrder("timestamp with time zone", "timestamp with time zone", "timestamp with time zone", "timestamp with time zone");
        String originalColumns = "SELECT budget_id, service_order_id, budget_version, quoted_amount, approved_amount, status, " +
                "sent_at, approved_at, created_at, updated_at, commercial_snapshot::text, row_version FROM " + schema + ".budget ORDER BY budget_id";
        var beforeV6 = jdbcTemplate.queryForList(originalColumns);
        Flyway v6 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .createSchemas(false).locations("classpath:db/migration").target("6").load();
        assertThat(v6.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList(originalColumns)).isEqualTo(beforeV6);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + schema + ".budget WHERE approval_origin IS NULL AND approved_by_employee_id IS NULL", Integer.class)).isEqualTo(3);
        assertThat(v6.validateWithResult().validationSuccessful).isTrue();
        assertThat(v6.info().pending()).isEmpty();
    }

    private List<Budget> createConcurrently(List<UUID> serviceOrderIds) throws Exception {
        CountDownLatch ready = new CountDownLatch(serviceOrderIds.size());
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(serviceOrderIds.size())) {
            List<Future<Budget>> results = new ArrayList<>();
            for (UUID serviceOrderId : serviceOrderIds) {
                results.add(workers.submit(() -> {
                    ready.countDown();
                    await(start);
                    return createDraft(serviceOrderId, BigDecimal.TEN);
                }));
            }
            try {
                await(ready);
            } finally {
                start.countDown();
            }
            List<Budget> budgets = new ArrayList<>();
            for (Future<Budget> result : results) {
                budgets.add(result.get(20, TimeUnit.SECONDS));
            }
            return budgets;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new AssertionError("Tempo limite na sincronização do teste concorrente");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Teste concorrente interrompido", exception);
        }
    }

    private int lastVersion(UUID serviceOrderId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_version FROM budget_revision_counter WHERE service_order_id = ?",
                Integer.class, serviceOrderId);
    }

    private int counterCount(UUID serviceOrderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM budget_revision_counter WHERE service_order_id = ?",
                Integer.class, serviceOrderId);
    }

    private void insertHistoricalBudget(Budget budget) {
        jdbcTemplate.update("""
                INSERT INTO budget (budget_id, service_order_id, budget_version, quoted_amount, approved_amount,
                                    status, sent_at, approved_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, budget.getBudgetId(), budget.getServiceOrderId(), budget.getBudgetVersion(),
                budget.getQuotedAmount(), budget.getApprovedAmount(), budget.getStatus().name(),
                budget.getSentAt(), budget.getApprovedAt(), budget.getCreatedAt(), budget.getUpdatedAt());
    }
    @Test
    void shouldLogCreationOnlyAfterCommitAndKeepRolledBackCreationSilent() throws Exception {
        try (var logs = new br.com.fiap.numberone.billing.budget.support.EcsLogCapture(
                br.com.fiap.numberone.billing.budget.infrastructure.persistence.gateways.BudgetGatewayImpl.class)) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                createDraft(UUID.randomUUID(), BigDecimal.TEN);
                assertThat(logs.events()).isEmpty();
                status.setRollbackOnly();
            });
            assertThat(logs.events()).isEmpty();
            UUID order = UUID.randomUUID();
            createDraft(order, BigDecimal.TEN);
            createDraft(order, BigDecimal.TEN);
            assertThat(logs.events()).hasSize(2);
            assertThat(logs.events().getFirst().path("event").path("code").asText()).isEqualTo("BUDGET_CREATED");
            assertThat(logs.events().getLast().path("event").path("code").asText()).isEqualTo("BUDGET_REVISION_CREATED");
        }
    }

    @Test
    void shouldLinkCommittedApprovalLogToOutboxWithoutEmployeeData() throws Exception {
        Budget draft = createDraft(UUID.randomUUID(), BigDecimal.TEN);
        try (var logs = new br.com.fiap.numberone.billing.budget.support.EcsLogCapture(
                br.com.fiap.numberone.billing.budget.infrastructure.persistence.gateways.BudgetGatewayImpl.class)) {
            gateway.registerApproval(draft.getBudgetId(), "private-employee-context");
            assertThat(logs.events()).hasSize(1);
            var event = logs.events().getFirst();
            assertThat(event.path("budget").path("previous_status").asText()).isEqualTo("DRAFT");
            assertThat(event.path("budget").path("status").asText()).isEqualTo("APPROVED");
            UUID eventId = jdbcTemplate.queryForObject("SELECT event_id FROM integration_event_outbox WHERE budget_id=?",
                    UUID.class, draft.getBudgetId());
            assertThat(event.path("event").path("id").asText()).isEqualTo(eventId.toString());
            assertThat(event.toString()).doesNotContain("private-employee-context");
        }
    }

    private Budget createDraft(UUID serviceOrderId, BigDecimal amount) {
        return gateway.createDraft(serviceOrderId, amount, new BudgetComposition(List.of(), 1));
    }

    private Budget draftFixture(UUID serviceOrderId, int version, BigDecimal amount) {
        return Budget.createDraft(serviceOrderId, version, amount, new BudgetComposition(List.of(), 1));
    }

    @Test
    void shouldPersistManualCompositionAsJsonbWithoutInventingPrices() {
        UUID orderId = UUID.randomUUID();
        UUID partId = UUID.randomUUID();
        ServiceOrderQuotationContext context = quotationContext(orderId, partId);
        CreateBudgetUseCase useCase = new CreateBudgetUseCase(id -> context, ids -> {
            throw new AssertionError("Orçamento manual não deve depender do Inventory");
        }, gateway);

        Budget created = useCase.execute(orderId, new BigDecimal("99.99"));
        Budget restored = gateway.findById(created.getBudgetId()).orElseThrow();

        assertThat(restored.getComposition()).isEqualTo(created.getComposition());
        assertThat(restored.getStatus()).isEqualTo(BudgetStatus.DRAFT);
        assertThat(restored.getQuotedAmount()).isEqualByComparingTo("99.99");
        assertThat(restored.getBudgetVersion()).isEqualTo(1);
        assertThat(restored.getComposition().items().getFirst().supplies().getFirst().salePrice()).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT pg_typeof(commercial_snapshot)::text FROM budget WHERE budget_id = ?",
                String.class, created.getBudgetId())).isEqualTo("jsonb");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT jsonb_typeof(commercial_snapshot) FROM budget WHERE budget_id = ?",
                String.class, created.getBudgetId())).isEqualTo("object");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT commercial_snapshot #> '{items,0,supplies,0,salePrice}' = 'null'::jsonb
                FROM budget WHERE budget_id = ?
                """, Boolean.class, created.getBudgetId())).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT commercial_snapshot #>> '{items,0,supplies,0,inventoryItemId}' FROM budget WHERE budget_id = ?",
                String.class, created.getBudgetId())).isEqualTo(partId.toString());
        assertThat(lastVersion(orderId)).isEqualTo(1);
    }

    @Test
    void shouldPersistAutomaticCompositionAndKeepEarlierRevisionPricesUnchanged() {
        UUID orderId = UUID.randomUUID();
        UUID partId = UUID.randomUUID();
        ServiceOrderQuotationContext context = quotationContext(orderId, partId);
        CreateBudgetUseCase firstCalculation = new CreateBudgetUseCase(id -> context,
                ids -> List.of(new InventoryItemPrice(partId, new BigDecimal("200.12"), "BRL")), gateway);
        CreateBudgetUseCase nextCalculation = new CreateBudgetUseCase(id -> context,
                ids -> List.of(new InventoryItemPrice(partId, new BigDecimal("300.00"), "BRL")), gateway);

        Budget first = firstCalculation.execute(orderId, null);
        Budget next = nextCalculation.execute(orderId, null);

        Budget restored = gateway.findById(first.getBudgetId()).orElseThrow();
        assertThat(restored.getQuotedAmount()).isEqualByComparingTo("550.24");
        assertThat(next.getQuotedAmount()).isEqualByComparingTo("750.00");
        assertThat(next.getBudgetVersion()).isEqualTo(2);
        assertThat(restored.getComposition()).isEqualTo(first.getComposition());
        assertThat(restored.getComposition().items().getFirst().supplies().getFirst().salePrice())
                .isEqualByComparingTo("200.12");
        assertThat(next.getComposition().items().getFirst().supplies().getFirst().salePrice())
                .isEqualByComparingTo("300.00");
        assertThat(lastVersion(orderId)).isEqualTo(2);

        gateway.sendForApproval(restored.getBudgetId(), "customer@example.test");
        Budget sent = gateway.findById(first.getBudgetId()).orElseThrow();
        assertThat(sent.getQuotedAmount()).isEqualByComparingTo("550.24");
        assertThat(sent.getComposition()).isEqualTo(first.getComposition());
    }

    @Test
    void shouldNotAllocateVersionWhenUseCaseCannotCalculatePrice() {
        UUID orderId = UUID.randomUUID();
        var context = quotationContext(orderId, UUID.randomUUID());
        var useCase = new CreateBudgetUseCase(id -> context, ids -> List.of(), gateway);

        assertThatThrownBy(() -> useCase.execute(orderId, null)).isInstanceOf(InvalidBudgetDataException.class);
        assertThat(gateway.findAllByServiceOrderId(orderId)).isEmpty();
        assertThat(counterCount(orderId)).isZero();
    }

    @Test
    void shouldRollbackCounterWhenCompositionIsMissingForNewBudget() {
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> gateway.createDraft(orderId, BigDecimal.TEN, null))
                .isInstanceOf(InvalidBudgetDataException.class);
        assertThat(gateway.findAllByServiceOrderId(orderId)).isEmpty();
        assertThat(counterCount(orderId)).isZero();
    }

    private ServiceOrderQuotationContext quotationContext(UUID orderId, UUID partId) {
        var item = new ServiceOrderQuotationContext.Item(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("150.00"),
                "PENDING", List.of(new ServiceOrderQuotationContext.Supply(partId, 2)));
        return new ServiceOrderQuotationContext(orderId, "IN_DIAGNOSIS", List.of(item), null, 1);
    }
}
