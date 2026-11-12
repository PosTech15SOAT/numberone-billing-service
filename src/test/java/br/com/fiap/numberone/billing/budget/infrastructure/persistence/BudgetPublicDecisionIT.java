package br.com.fiap.numberone.billing.budget.infrastructure.persistence;

import br.com.fiap.numberone.billing.budget.api.publicview.PublicBudgetRequestFilter;
import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetAuthorizationException;
import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.enums.BudgetApprovalOrigin;
import br.com.fiap.numberone.billing.budget.domain.enums.BudgetStatus;
import br.com.fiap.numberone.billing.budget.domain.exceptions.ApprovedBudgetExistsException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetStatusException;
import br.com.fiap.numberone.billing.budget.domain.valueobjects.BudgetComposition;
import br.com.fiap.numberone.billing.budget.infrastructure.notifications.ApprovalTokenProtection;
import br.com.fiap.numberone.billing.budget.infrastructure.notifications.BudgetNotificationRepository;
import br.com.fiap.numberone.billing.budget.infrastructure.persistence.repositories.BudgetRevisionCounterRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(properties = {
        "billing.integrations.os-base-url=http://os.test", "billing.integrations.inventory-base-url=http://inventory.test",
        "billing.notifications.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "billing.notifications.approval-base-url=https://billing.test/api/public/orcamentos-ordem-servico/decisao",
        "billing.notifications.resend-interval=30s", "billing.notifications.enabled=false", "billing.outbox.enabled=false"
})
class BudgetPublicDecisionIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired BudgetGateway gateway;
    @Autowired BudgetNotificationRepository notifications;
    @Autowired ApprovalTokenProtection protection;
    @Autowired BudgetRevisionCounterRepository counters;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource dataSource;
    @Autowired WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        jdbc.execute("TRUNCATE payment_attempt, charge, budget_email_notification, budget_approval_authorization, integration_event_outbox, budget, budget_revision_counter");
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new PublicBudgetRequestFilter()).build();
    }

    @Test void shouldRenderEmailLinkRepeatedlyWithoutConsumingOrChangingAnyPersistedData() throws Exception {
        Budget budget = sent(); String token = token(budget);
        var before = jdbc.queryForList("SELECT * FROM budget_approval_authorization");
        for (int i = 0; i < 2; i++) mvc.perform(get(PublicBudgetRequestFilter.BASE_PATH + "/decisao").queryParam("token", token))
                .andExpect(status().isOk()).andExpect(content().string(containsString("123,45")));
        assertThat(jdbc.queryForList("SELECT * FROM budget_approval_authorization")).isEqualTo(before);
        assertThat(events()).isEqualTo(1); assertThat(rowVersion(budget)).isEqualTo(1);
        assertThat(notifications.isAuthorizationUsable(protection.hash(token))).isTrue();
        Budget draft = draft(budget.getServiceOrderId());
        assertThat(gateway.getAuthorizedBudget(token).budgetId()).isEqualTo(budget.getBudgetId()).isNotEqualTo(draft.getBudgetId());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void shouldApplyRemoteDecisionConsumeAuthorizationAndPreserveCommercialData(boolean approval) throws Exception {
        Budget budget = sent(); String token = token(budget);
        Budget before = gateway.findById(budget.getBudgetId()).orElseThrow();
        String snapshot = jdbc.queryForObject("SELECT commercial_snapshot::text FROM budget WHERE budget_id = ?", String.class, budget.getBudgetId());
        String eventBefore = jdbc.queryForObject("SELECT envelope::text FROM integration_event_outbox", String.class);
        mvc.perform(post(PublicBudgetRequestFilter.BASE_PATH + (approval ? "/aprovar" : "/rejeitar"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("token", token).param("confirmacao", "sim")
                        .param("budgetId", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(content().string(containsString(approval ? "Orçamento aprovado" : "Orçamento rejeitado")));
        Budget after = gateway.findById(budget.getBudgetId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(approval ? BudgetStatus.APPROVED : BudgetStatus.REJECTED);
        assertThat(after.getBudgetId()).isEqualTo(before.getBudgetId()); assertThat(after.getBudgetVersion()).isEqualTo(before.getBudgetVersion());
        assertThat(after.getQuotedAmount()).isEqualByComparingTo(before.getQuotedAmount());
        assertThat(after.getComposition()).isEqualTo(before.getComposition()); assertThat(after.getSentAt()).isEqualTo(before.getSentAt());
        assertThat(after.getApprovedByEmployeeId()).isNull();
        if (approval) {
            assertThat(after.getApprovedAmount()).isEqualByComparingTo("123.45");
            assertThat(after.getApprovedAt()).isNotNull(); assertThat(after.getApprovalOrigin()).isEqualTo(BudgetApprovalOrigin.REMOTE);
        } else {
            assertThat(after.getApprovedAmount()).isNull(); assertThat(after.getApprovedAt()).isNull();
            assertThat(after.getUpdatedAt()).isAfterOrEqualTo(before.getUpdatedAt()); assertThat(after.getApprovalOrigin()).isNull();
        }
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM budget_approval_authorization", OffsetDateTime.class)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM budget_approval_authorization", OffsetDateTime.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT commercial_snapshot::text FROM budget WHERE budget_id = ?", String.class, budget.getBudgetId())).isEqualTo(snapshot);
        assertThat(rowVersion(budget)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT last_version FROM budget_revision_counter", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT event_type FROM integration_event_outbox", String.class))
                .containsExactlyInAnyOrder("OrcamentoEnviado", approval ? "OrcamentoAprovado" : "OrcamentoRejeitado");
        assertThat(jdbc.queryForObject("SELECT envelope::text FROM integration_event_outbox WHERE event_type='OrcamentoEnviado'", String.class)).isEqualTo(eventBefore);
        if (approval) assertThat(jdbc.queryForObject("SELECT envelope->'payload'->>'approvedAmount' FROM integration_event_outbox WHERE event_type='OrcamentoAprovado'", String.class)).isEqualTo("123.45");
        assertThat(notifications.isAuthorizationUsable(protection.hash(token))).isFalse();
        assertThat(notifications.findLatest(budget.getBudgetId()).orElseThrow().status().name()).isEqualTo("CANCELLED");
        expectReason(() -> gateway.getAuthorizedBudget(token), BudgetAuthorizationException.Reason.CONSUMED);
        expectReason(() -> gateway.approveRemotely(token), BudgetAuthorizationException.Reason.CONSUMED);
        expectReason(() -> gateway.rejectRemotely(token), BudgetAuthorizationException.Reason.CONSUMED);
        assertThat(events()).isEqualTo(2);
    }

    @Test void shouldRejectInvalidExpiredRevokedObsoleteAndAlreadyDecidedLinks() {
        Budget budget = sent(); String token = token(budget);
        expectReason(() -> gateway.getAuthorizedBudget(null), BudgetAuthorizationException.Reason.INVALID);
        expectReason(() -> gateway.approveRemotely("malformed"), BudgetAuthorizationException.Reason.INVALID);
        expectReason(() -> gateway.getAuthorizedBudget(protection.generateToken()), BudgetAuthorizationException.Reason.INVALID);
        expire();
        expectReason(() -> gateway.getAuthorizedBudget(token), BudgetAuthorizationException.Reason.EXPIRED);
        expectReason(() -> gateway.rejectRemotely(token), BudgetAuthorizationException.Reason.EXPIRED);
        allowResend(); gateway.resendForApproval(budget.getBudgetId(), "customer@example.test");
        expectReason(() -> gateway.getAuthorizedBudget(token), BudgetAuthorizationException.Reason.REVOKED);
        expectReason(() -> gateway.approveRemotely(token), BudgetAuthorizationException.Reason.REVOKED);
        String replacement = token(budget);
        Budget newer = draft(budget.getServiceOrderId()); gateway.sendForApproval(newer.getBudgetId(), "customer@example.test");
        expectReason(() -> gateway.getAuthorizedBudget(replacement), BudgetAuthorizationException.Reason.OBSOLETE);
        expectReason(() -> gateway.rejectRemotely(replacement), BudgetAuthorizationException.Reason.OBSOLETE);
        String current = token(newer); gateway.approve(newer.getBudgetId());
        expectReason(() -> gateway.getAuthorizedBudget(current), BudgetAuthorizationException.Reason.ALREADY_DECIDED);
        expectReason(() -> gateway.rejectRemotely(current), BudgetAuthorizationException.Reason.ALREADY_DECIDED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_approval_authorization WHERE consumed_at IS NOT NULL", Integer.class)).isZero();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void shouldRollbackDecisionConsumptionAndOutboxWhenEventInsertFails(boolean approval) {
        Budget budget = sent(); String token = token(budget);
        var authorization = jdbc.queryForList("SELECT * FROM budget_approval_authorization");
        var before = gateway.findById(budget.getBudgetId()).orElseThrow();
        jdbc.execute("ALTER TABLE integration_event_outbox ADD CONSTRAINT test_only_sent CHECK (event_type='OrcamentoEnviado')");
        try {
            assertThatThrownBy(() -> decide(token, approval)).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(gateway.findById(budget.getBudgetId()).orElseThrow()).usingRecursiveComparison().isEqualTo(before);
            assertThat(jdbc.queryForList("SELECT * FROM budget_approval_authorization")).isEqualTo(authorization);
            assertThat(rowVersion(budget)).isEqualTo(1); assertThat(events()).isEqualTo(1);
            assertThat(notifications.isAuthorizationUsable(protection.hash(token))).isTrue();
            assertThat(notifications.findLatest(budget.getBudgetId()).orElseThrow().status().name()).isEqualTo("PENDING");
        } finally { jdbc.execute("ALTER TABLE integration_event_outbox DROP CONSTRAINT test_only_sent"); }
    }

    @Test void shouldRollbackAfterEventAndConsumptionWereAlreadyPersisted() {
        Budget budget = sent(); String token = token(budget);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            gateway.approveRemotely(token); assertThat(events()).isEqualTo(2); throw new IllegalStateException("Test rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(gateway.getAuthorizedBudget(token).budgetId()).isEqualTo(budget.getBudgetId());
        assertThat(events()).isEqualTo(1); assertThat(rowVersion(budget)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void shouldAllowOnlyOneDecisionForSameAuthorizationAcrossConcurrentTabs(boolean twoApprovals) throws Exception {
        Budget budget = sent(); String token = token(budget); var barrier = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return attempt(() -> gateway.approveRemotely(token)); });
            var second = workers.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return attempt(() -> decide(token, twoApprovals)); });
            var results = java.util.Arrays.asList(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(results.stream().filter(failure -> failure == null)).hasSize(1);
            assertThat(results.stream().filter(failure -> failure != null)).singleElement().satisfies(failure -> {
                assertThat(failure).isInstanceOf(BudgetAuthorizationException.class);
                assertThat(((BudgetAuthorizationException) failure).getReason()).isEqualTo(BudgetAuthorizationException.Reason.CONSUMED);
            });
        }
        assertThat(events()).isEqualTo(2); assertThat(rowVersion(budget)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_approval_authorization WHERE consumed_at IS NOT NULL", Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void shouldCoordinateRemoteAndInPersonDecisions(boolean remoteFirst) throws Exception {
        Budget budget = sent(); String token = token(budget);
        Runnable remote = () -> gateway.approveRemotely(token);
        Runnable inPerson = () -> gateway.registerApproval(budget.getBudgetId(), "validated-subject");
        RuntimeException loser = inOrder(budget.getServiceOrderId(), remoteFirst ? remote : inPerson, remoteFirst ? inPerson : remote);
        if (remoteFirst) assertThat(loser).isInstanceOf(InvalidBudgetStatusException.class);
        else assertThat(((BudgetAuthorizationException) loser).getReason()).isEqualTo(BudgetAuthorizationException.Reason.ALREADY_DECIDED);
        Budget winner = gateway.findById(budget.getBudgetId()).orElseThrow();
        assertThat(winner.getApprovalOrigin()).isEqualTo(remoteFirst ? BudgetApprovalOrigin.REMOTE : BudgetApprovalOrigin.IN_PERSON);
        assertThat(winner.getApprovedByEmployeeId()).isEqualTo(remoteFirst ? null : "validated-subject");
        assertThat(events()).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void shouldCoordinateResendAndRemoteDecision(boolean resendFirst) throws Exception {
        Budget budget = sent(); String token = token(budget); allowResend();
        Runnable resend = () -> gateway.resendForApproval(budget.getBudgetId(), "customer@example.test");
        Runnable decision = () -> gateway.approveRemotely(token);
        RuntimeException loser = inOrder(budget.getServiceOrderId(), resendFirst ? resend : decision, resendFirst ? decision : resend);
        if (resendFirst) assertThat(((BudgetAuthorizationException) loser).getReason()).isEqualTo(BudgetAuthorizationException.Reason.REVOKED);
        else assertThat(loser).isInstanceOf(InvalidBudgetStatusException.class);
        assertThat(gateway.findById(budget.getBudgetId()).orElseThrow().getStatus()).isEqualTo(resendFirst ? BudgetStatus.SENT : BudgetStatus.APPROVED);
        assertThat(events()).isEqualTo(resendFirst ? 1 : 2);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void shouldCoordinateNewRevisionSendingAndRemoteDecision(boolean newRevisionFirst) throws Exception {
        Budget first = sent(); String token = token(first); Budget newer = draft(first.getServiceOrderId());
        Runnable send = () -> gateway.sendForApproval(newer.getBudgetId(), "customer@example.test");
        Runnable decision = () -> gateway.approveRemotely(token);
        RuntimeException loser = inOrder(first.getServiceOrderId(), newRevisionFirst ? send : decision, newRevisionFirst ? decision : send);
        if (newRevisionFirst) assertThat(((BudgetAuthorizationException) loser).getReason()).isEqualTo(BudgetAuthorizationException.Reason.OBSOLETE);
        else assertThat(loser).isInstanceOf(ApprovedBudgetExistsException.class);
        assertThat(gateway.findById(newer.getBudgetId()).orElseThrow().getStatus()).isEqualTo(newRevisionFirst ? BudgetStatus.SENT : BudgetStatus.DRAFT);
        assertThat(jdbc.queryForObject("SELECT last_version FROM budget_revision_counter", Integer.class)).isEqualTo(2);
    }

    @Test void shouldRevalidateExpiryAfterWaitingForOrderLock() throws Exception {
        Budget budget = sent(); String token = token(budget);
        RuntimeException loser = inOrder(budget.getServiceOrderId(), this::expire, () -> gateway.approveRemotely(token));
        assertThat(((BudgetAuthorizationException) loser).getReason()).isEqualTo(BudgetAuthorizationException.Reason.EXPIRED);
        assertThat(events()).isEqualTo(1); assertThat(rowVersion(budget)).isEqualTo(1);
    }

    @Test void shouldMigrateV7AuthorizationsAndProtectedNotificationsWithoutChangingHistoricalData() {
        String schema = "public_migration_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);
        Flyway v7 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .createSchemas(false).locations("classpath:db/migration").target("7").load();
        v7.migrate();
        UUID budgetId = UUID.randomUUID(), authorizationId = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".budget (budget_id,service_order_id,budget_version,quoted_amount,status,created_at,sent_at) "
                + "VALUES (?,?,1,123.456700,'SENT',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", budgetId, UUID.randomUUID());
        String token = protection.generateToken();
        byte[] protectedContent = protection.protect(authorizationId,
                new br.com.fiap.numberone.billing.budget.infrastructure.notifications.BudgetApprovalEmail(
                        "customer@example.test", budgetId, new BigDecimal("123.456700"),
                        "https://billing.test/api/public/orcamentos-ordem-servico/decisao?token=" + token, Instant.now().plusSeconds(172800)));
        jdbc.update("INSERT INTO " + schema + ".budget_approval_authorization (authorization_id,budget_id,token_hash,expires_at) "
                + "VALUES (?,?,?,CURRENT_TIMESTAMP+INTERVAL '48 hours')", authorizationId, budgetId, protection.hash(token));
        jdbc.update("INSERT INTO " + schema + ".budget_email_notification (notification_id,authorization_id,budget_id,protected_content) VALUES (?,?,?,?)",
                UUID.randomUUID(), authorizationId, budgetId, protectedContent);
        var budgets = jdbc.queryForList("SELECT * FROM " + schema + ".budget");
        var authorizations = jdbc.queryForList("SELECT authorization_id,budget_id,token_hash,created_at,expires_at,revoked_at FROM " + schema + ".budget_approval_authorization");
        Flyway v8 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .createSchemas(false).locations("classpath:db/migration").target("8").load();
        assertThat(v8.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(v8.validateWithResult().validationSuccessful).isTrue();
        assertThat(jdbc.queryForList("SELECT * FROM " + schema + ".budget")).isEqualTo(budgets);
        assertThat(jdbc.queryForList("SELECT authorization_id,budget_id,token_hash,created_at,expires_at,revoked_at FROM " + schema + ".budget_approval_authorization")).isEqualTo(authorizations);
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM " + schema + ".budget_approval_authorization", OffsetDateTime.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT protected_content FROM " + schema + ".budget_email_notification", byte[].class)).isEqualTo(protectedContent);
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname=? AND indexname='uk_budget_active_authorization'", String.class, schema))
                .contains("consumed_at IS NULL", "revoked_at IS NULL");
    }

    private RuntimeException inOrder(UUID orderId, Runnable first, Runnable second) throws Exception {
        var holding = new CountDownLatch(1); var release = new CountDownLatch(1); var starting = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var firstResult = workers.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                counters.lockByServiceOrderId(orderId); first.run(); holding.countDown(); await(release);
            }));
            try {
                assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
                var secondResult = workers.submit(() -> { starting.countDown(); return attempt(second); });
                assertThat(starting.await(10, TimeUnit.SECONDS)).isTrue(); release.countDown();
                firstResult.get(15, TimeUnit.SECONDS); return secondResult.get(15, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
    }

    private void expectReason(Runnable action, BudgetAuthorizationException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BudgetAuthorizationException.class, error -> assertThat(error.getReason()).isEqualTo(reason));
    }
    private RuntimeException attempt(Runnable action) { try { action.run(); return null; } catch (RuntimeException failure) { return failure; } }
    private void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test timeout"); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }
    private Budget decide(String token, boolean approval) { return approval ? gateway.approveRemotely(token) : gateway.rejectRemotely(token); }
    private Budget draft(UUID orderId) { return gateway.createDraft(orderId, new BigDecimal("123.45"), new BudgetComposition(List.of())); }
    private Budget sent() { return gateway.sendForApproval(draft(UUID.randomUUID()).getBudgetId(), "customer@example.test"); }
    private String token(Budget budget) {
        return jdbc.query("SELECT authorization_id, protected_content FROM budget_email_notification WHERE budget_id=? ORDER BY created_at DESC LIMIT 1",
                (row, index) -> protection.unprotect(row.getObject(1, UUID.class), row.getBytes(2)).approvalUrl().split("\\?token=")[1],
                budget.getBudgetId()).getFirst();
    }
    private int events() { return jdbc.queryForObject("SELECT COUNT(*) FROM integration_event_outbox", Integer.class); }
    private long rowVersion(Budget budget) { return jdbc.queryForObject("SELECT row_version FROM budget WHERE budget_id=?", Long.class, budget.getBudgetId()); }
    private void expire() { jdbc.execute("UPDATE budget_approval_authorization SET created_at=statement_timestamp()-INTERVAL '49 hours', expires_at=statement_timestamp()-INTERVAL '1 hour'"); }
    private void allowResend() { jdbc.execute("UPDATE budget_email_notification SET created_at=statement_timestamp()-INTERVAL '31 seconds'"); }
}
