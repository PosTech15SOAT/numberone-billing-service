package br.com.fiap.numberone.billing.budget.infrastructure.notifications;

import br.com.fiap.numberone.billing.infrastructure.observability.support.FailingLogAppender;
import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetResendTooSoonException;
import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.application.usecases.GetBudgetUseCase;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.enums.BudgetStatus;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetStatusException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.ObsoleteBudgetRevisionException;
import br.com.fiap.numberone.billing.budget.domain.valueobjects.BudgetComposition;
import br.com.fiap.numberone.billing.budget.infrastructure.config.BudgetNotificationProperties;
import br.com.fiap.numberone.billing.budget.infrastructure.persistence.gateways.BudgetGatewayImpl;
import br.com.fiap.numberone.billing.budget.support.EcsLogCapture;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(properties = {
        "billing.integrations.os-base-url=http://os.test",
        "billing.integrations.inventory-base-url=http://inventory.test",
        "billing.notifications.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "billing.notifications.approval-base-url=https://billing.test/decision",
        "billing.notifications.enabled=false", "billing.outbox.enabled=false"
})
class BudgetNotificationIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired BudgetGateway budgets;
    @Autowired BudgetNotificationRepository notifications;
    @Autowired ApprovalTokenProtection protection;
    @Autowired BudgetNotificationProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Flyway flyway;

    @BeforeEach
    void isolateFixturesInDisposableDatabase() {
        jdbc.execute("TRUNCATE payment_attempt, charge, budget_email_notification, budget_approval_authorization, integration_event_outbox, budget, budget_revision_counter");
    }

    @Test
    void shouldLogCommercialMilestonesOnceAfterCommitWithBusinessIds() throws Exception {
        try (var logs = new EcsLogCapture(BudgetGatewayImpl.class)) {
            Budget first = draft(UUID.randomUUID());
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                budgets.sendForApproval(first.getBudgetId(), "private@example.test");
                assertThat(logs.events()).hasSize(1);
            });
            assertThat(logs.events()).hasSize(2);
            var sent = logs.events().getLast();
            assertThat(sent.path("event").path("code").asText()).isEqualTo("BUDGET_APPROVAL_REQUESTED");
            assertThat(sent.path("budget").path("id").asText()).isEqualTo(first.getBudgetId().toString());
            assertThat(sent.path("budget").path("version").asInt()).isEqualTo(first.getBudgetVersion());
            assertThat(sent.path("service_order").path("id").asText()).isEqualTo(first.getServiceOrderId().toString());
            budgets.reject(first.getBudgetId());
            Budget second = draft(UUID.randomUUID());
            budgets.sendForApproval(second.getBudgetId(), "private@example.test");
            budgets.approve(second.getBudgetId());
            assertThat(logs.events()).extracting(json -> json.path("event").path("code").asText())
                    .containsExactly("BUDGET_CREATED", "BUDGET_APPROVAL_REQUESTED", "BUDGET_REJECTED", "BUDGET_CREATED", "BUDGET_APPROVAL_REQUESTED", "BUDGET_APPROVED");
            assertThat(logs.events().toString()).doesNotContain("private@example.test", "protected_content", "token_hash");
        }
    }

    @Test
    void shouldCommitSentEventHashedAuthorizationAndProtectedNotificationTogether() {
        Budget draft = draft(UUID.randomUUID());
        Budget sent = budgets.sendForApproval(draft.getBudgetId(), "customer@example.test");
        assertThat(sent.getStatus()).isEqualTo(BudgetStatus.SENT);
        assertThat(count("integration_event_outbox")).isEqualTo(1);
        assertThat(count("budget_approval_authorization")).isEqualTo(1);
        assertThat(count("budget_email_notification")).isEqualTo(1);
        var row = notification();
        var email = protection.unprotect((UUID) row.get("authorization_id"), (byte[]) row.get("protected_content"));
        String token = email.approvalUrl().substring(email.approvalUrl().indexOf("?token=") + 7);
        String hash = hash();
        assertThat(hash).isEqualTo(protection.hash(token)).isNotEqualTo(token);
        assertThat(new String((byte[]) row.get("protected_content"), StandardCharsets.ISO_8859_1)).doesNotContain(token);
        assertThat(notifications.isAuthorizationUsable(hash)).isTrue();
        assertThat(email.quotedAmount()).isEqualByComparingTo(draft.getQuotedAmount());
        assertThat(jdbc.queryForObject("SELECT expires_at - created_at = INTERVAL '48 hours' FROM budget_approval_authorization", Boolean.class)).isTrue();
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempt_count")).isEqualTo(0);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("13");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void shouldRollbackAllFourWritesWhenNotificationInsertFails() {
        Budget draft = draft(UUID.randomUUID());
        Budget before = budgets.findById(draft.getBudgetId()).orElseThrow();
        jdbc.execute("ALTER TABLE budget_email_notification ADD CONSTRAINT test_reject_notification CHECK (false)");
        try {
            assertThatThrownBy(() -> budgets.sendForApproval(draft.getBudgetId(), "customer@example.test"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class);
            assertThat(budgets.findById(draft.getBudgetId()).orElseThrow()).usingRecursiveComparison().isEqualTo(before);
            assertThat(count("integration_event_outbox")).isZero();
            assertThat(count("budget_approval_authorization")).isZero();
            assertThat(count("budget_email_notification")).isZero();
            assertThat(rowVersion(draft)).isZero();
        } finally { jdbc.execute("ALTER TABLE budget_email_notification DROP CONSTRAINT test_reject_notification"); }
    }

    @Test
    void shouldRollbackSentAndEventWhenRecipientValidationFails() {
        Budget draft = draft(UUID.randomUUID());
        assertThatThrownBy(() -> budgets.sendForApproval(draft.getBudgetId(), "private@example.test\r\nBcc: secret"))
                .isInstanceOf(br.com.fiap.numberone.billing.budget.application.exceptions.BudgetNotificationUnavailableException.class)
                .hasMessage("Destinatário da notificação ausente ou inválido");
        assertThat(budgets.findById(draft.getBudgetId()).orElseThrow().getStatus()).isEqualTo(BudgetStatus.DRAFT);
        assertThat(count("integration_event_outbox")).isZero();
        assertThat(count("budget_approval_authorization")).isZero();
        assertThat(count("budget_email_notification")).isZero();
        assertThat(rowVersion(draft)).isZero();
    }

    @Test
    void shouldRollbackEvenAfterAllWritesHaveSucceededInsideCallerTransaction() throws Exception {
        Budget draft = draft(UUID.randomUUID());
        try (var logs = new EcsLogCapture(BudgetGatewayImpl.class)) {
            assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                budgets.sendForApproval(draft.getBudgetId(), "customer@example.test");
                assertThat(count("budget_email_notification")).isEqualTo(1);
                assertThat(logs.events()).isEmpty();
                throw new IllegalStateException("Test rollback");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(logs.events()).isEmpty();
        }
        assertThat(budgets.findById(draft.getBudgetId()).orElseThrow().getStatus()).isEqualTo(BudgetStatus.DRAFT);
        assertThat(count("integration_event_outbox")).isZero();
        assertThat(count("budget_approval_authorization")).isZero();
        assertThat(count("budget_email_notification")).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRevokeAndResendBeforeOrAfterExpiryWithoutChangingBudgetOrEmittingEvent(boolean expired) {
        Budget budget = sent();
        Budget before = budgets.findById(budget.getBudgetId()).orElseThrow();
        long rowVersion = rowVersion(budget);
        String oldHash = hash();
        allowResend();
        if (expired) expireAuthorization();
        assertThat(notifications.isAuthorizationUsable(oldHash)).isEqualTo(!expired);
        budgets.resendForApproval(budget.getBudgetId(), "customer@example.test");
        assertThat(budgets.findById(budget.getBudgetId()).orElseThrow()).usingRecursiveComparison().isEqualTo(before);
        assertThat(rowVersion(budget)).isEqualTo(rowVersion);
        assertThat(count("integration_event_outbox")).isEqualTo(1);
        assertThat(count("budget_approval_authorization")).isEqualTo(2);
        assertThat(notifications.isAuthorizationUsable(oldHash)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_approval_authorization WHERE revoked_at IS NULL", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_email_notification WHERE status = 'CANCELLED' AND protected_content IS NULL", Integer.class)).isEqualTo(1);
        assertThat(notifications.findLatest(budget.getBudgetId()).orElseThrow().attempts()).isZero();
        assertThat(new GetBudgetUseCase(budgets, notifications).execute(budget.getBudgetId()).canResend()).isFalse();
    }

    @Test
    void shouldRollbackRevocationWhenReplacementNotificationFails() {
        Budget budget = sent();
        allowResend();
        String oldHash = hash();
        // Uma constraint por authorization_id permite conservar a linha original e recusar a substituta.
        UUID original = (UUID) notification().get("authorization_id");
        jdbc.execute("ALTER TABLE budget_email_notification ADD CONSTRAINT test_no_new_notifications CHECK (authorization_id = '" + original + "'::uuid)");
        try {
            assertThatThrownBy(() -> budgets.resendForApproval(budget.getBudgetId(), "customer@example.test"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(notifications.isAuthorizationUsable(oldHash)).isTrue();
            assertThat(notification().get("status")).isEqualTo("PENDING");
            assertThat(count("budget_approval_authorization")).isEqualTo(1);
        } finally { jdbc.execute("ALTER TABLE budget_email_notification DROP CONSTRAINT test_no_new_notifications"); }
    }

    @Test
    void shouldRejectRepeatedClicksAndSerializeConcurrentResends() throws Exception {
        Budget budget = sent();
        assertThatThrownBy(() -> budgets.resendForApproval(budget.getBudgetId(), "customer@example.test"))
                .isInstanceOf(BudgetResendTooSoonException.class);
        allowResend();
        var barrier = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> resendAfterBarrier(budget, barrier));
            var second = workers.submit(() -> resendAfterBarrier(budget, barrier));
            var results = java.util.Arrays.asList(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(result -> result == null)).hasSize(1);
            assertThat(results.stream().filter(result -> result != null)).singleElement().isInstanceOf(BudgetResendTooSoonException.class);
        }
        assertThat(count("budget_approval_authorization")).isEqualTo(2);
        assertThat(count("integration_event_outbox")).isEqualTo(1);
        assertThat(rowVersion(budget)).isEqualTo(1);
    }

    @Test
    void shouldKeepOrdersIndependentAndCancelObsoleteAuthorization() {
        Budget first = sent();
        String oldHash = hash();
        Budget other = sent();
        Budget newer = draft(first.getServiceOrderId());
        budgets.sendForApproval(newer.getBudgetId(), "customer@example.test");
        assertThat(notifications.isAuthorizationUsable(oldHash)).isFalse();
        assertThatThrownBy(() -> budgets.resendForApproval(first.getBudgetId(), "customer@example.test"))
                .isInstanceOf(ObsoleteBudgetRevisionException.class);
        assertThat(notifications.findLatest(first.getBudgetId()).orElseThrow().status().name()).isEqualTo("CANCELLED");
        assertThat(notifications.findLatest(other.getBudgetId()).orElseThrow().status().name()).isEqualTo("PENDING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "REJECTED", "IN_PERSON"})
    void shouldInvalidateAuthorizationAfterAnyDecisionAndNotNotifyDraftInPersonApproval(String decision) {
        Budget budget = sent();
        String oldHash = hash();
        if (decision.equals("APPROVED")) budgets.approve(budget.getBudgetId());
        else if (decision.equals("REJECTED")) budgets.reject(budget.getBudgetId());
        else budgets.registerApproval(budget.getBudgetId(), "validated-subject");
        assertThat(notifications.isAuthorizationUsable(oldHash)).isFalse();
        assertThat(notifications.findLatest(budget.getBudgetId()).orElseThrow().status().name()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> budgets.resendForApproval(budget.getBudgetId(), "customer@example.test"))
                .isInstanceOf(InvalidBudgetStatusException.class);
        Budget inPerson = draft(UUID.randomUUID());
        budgets.registerApproval(inPerson.getBudgetId(), "validated-subject");
        assertThat(notifications.findLatest(inPerson.getBudgetId())).isEmpty();
        assertThat(new GetBudgetUseCase(budgets, notifications).execute(inPerson.getBudgetId()).notification()).isNull();
    }

    @Test
    void shouldSendThroughSimulatedSmtpOutsideTransactionAndEraseProtectedContentAfterAcceptance() throws Exception {
        Budget budget = sent();
        try (var server = new SimulatedSmtpServer()) {
            processor(server).processPending();
            assertThat(server.messages()).singleElement().satisfies(body -> {
                assertThat(body).contains(budget.getBudgetId().toString(), "123.45", "https://billing.test/decision?token=", "UTC");
            });
            var summary = new GetBudgetUseCase(budgets, notifications).execute(budget.getBudgetId());
            assertThat(summary.notification().status().name()).isEqualTo("SENT");
            assertThat(summary.notification().attempts()).isEqualTo(1);
            assertThat(summary.notification().sentAt()).isNotNull();
            assertThat(summary.notification().lastAttemptAt()).isNotNull();
            assertThat(notification().get("protected_content")).isNull();
            assertThat(notification().get("lease_token")).isNull();
            assertThat(budgets.findById(budget.getBudgetId()).orElseThrow().getStatus()).isEqualTo(BudgetStatus.SENT);
        }
    }

    @Test
    void shouldRetryWithBackoffAndReachFinalFailureWithoutExposingSmtpErrors() throws Exception {
        Budget budget = sent();
        try (var server = new SimulatedSmtpServer()) {
            server.reject = true;
            var processor = processor(server);
            processor.processPending();
            assertThat(notification().get("status")).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT next_attempt_at > last_attempt_at FROM budget_email_notification", Boolean.class)).isTrue();
            processor.processPending();
            assertThat(notification().get("attempt_count")).isEqualTo(1);
            for (int attempt = 2; attempt <= properties.maxAttempts(); attempt++) {
                makeDue(); processor.processPending();
            }
            assertThat(notification().get("status")).isEqualTo("FAILED");
            assertThat(notification().get("last_error")).isEqualTo("SMTP_SEND_FAILED");
            assertThat(notification().get("protected_content")).isNull();
            assertThat(notifications.findLatest(budget.getBudgetId()).orElseThrow().sentAt()).isNull();
            assertThat(server.messages()).isEmpty();
        }
    }

    @Test
    void shouldFailAuthenticationPermanentlyWithoutRetryingOrLeakingCredential() {
        sent();
        var sender = mock(SmtpBudgetEmailSender.class);
        doThrow(new MailAuthenticationException("sensitive-password-and-recipient")).when(sender).send(any());
        new BudgetNotificationProcessor(notifications, protection, sender, properties).processPending();
        assertThat(notification().get("status")).isEqualTo("FAILED");
        assertThat(notification().get("last_error")).isEqualTo("SMTP_AUTHENTICATION_FAILED");
        assertThat(notification().get("attempt_count")).isEqualTo(1);
    }

    @Test
    void shouldCancelExpiredAuthorizationBeforeSmtpWithoutChangingBudget() {
        Budget budget = sent();
        expireAuthorization();
        var sender = mock(SmtpBudgetEmailSender.class);
        new BudgetNotificationProcessor(notifications, protection, sender, properties).processPending();
        verifyNoInteractions(sender);
        assertThat(notification().get("status")).isEqualTo("CANCELLED");
        assertThat(notification().get("attempt_count")).isEqualTo(0);
        assertThat(notification().get("protected_content")).isNull();
        assertThat(budgets.findById(budget.getBudgetId()).orElseThrow().getStatus()).isEqualTo(BudgetStatus.SENT);
    }

    @Test
    void shouldCheckExpiryAgainWhenAuthorizationExpiresAfterClaim() {
        sent();
        var claimed = notifications.claimAvailable(1, 5, Duration.ofMinutes(2)).getFirst();
        expireAuthorization();
        assertThat(notifications.prepareDelivery(claimed)).isFalse();
        assertThat(notification().get("status")).isEqualTo("CANCELLED");
        assertThat(notification().get("protected_content")).isNull();
        assertThat(notification().get("lease_token")).isNull();
    }

    @Test
    void shouldRecoverExpiredLeaseAndRejectStaleConfirmations() {
        sent();
        var abandoned = notifications.claimAvailable(1, 5, Duration.ofMinutes(2)).getFirst();
        assertThat(notifications.claimAvailable(1, 5, Duration.ofMinutes(2))).isEmpty();
        jdbc.execute("UPDATE budget_email_notification SET lease_until = statement_timestamp() - INTERVAL '1 second'");
        var recovered = notifications.claimAvailable(1, 5, Duration.ofMinutes(2)).getFirst();
        assertThat(recovered.leaseToken()).isNotEqualTo(abandoned.leaseToken());
        assertThat(recovered.attemptCount()).isEqualTo(2);
        assertThat(notifications.markSent(abandoned)).isFalse();
        assertThat(notifications.recordFailure(abandoned, "OLD_WORKER", false, 5, Duration.ofSeconds(1))).isFalse();
        assertThat(notifications.prepareDelivery(abandoned)).isFalse();
        assertThat(notifications.markSent(recovered)).isTrue();
    }

    @Test
    void shouldFinishExhaustedLeaseWithoutInfiniteRecovery() {
        sent();
        notifications.claimAvailable(1, 1, Duration.ofMinutes(2));
        jdbc.execute("UPDATE budget_email_notification SET lease_until = statement_timestamp() - INTERVAL '1 second'");
        assertThat(notifications.claimAvailable(1, 1, Duration.ofMinutes(2))).isEmpty();
        assertThat(notification().get("status")).isEqualTo("FAILED");
        assertThat(notification().get("protected_content")).isNull();
    }

    @Test
    void shouldSkipLockedRowsForOtherProcessors() throws Exception {
        sent(); sent();
        UUID locked = (UUID) jdbc.queryForList("SELECT notification_id FROM budget_email_notification ORDER BY created_at").getFirst().get("notification_id");
        var lockedLatch = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var transaction = worker.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT notification_id FROM budget_email_notification WHERE notification_id = ? FOR UPDATE", UUID.class, locked);
                lockedLatch.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test timeout"); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
            }));
            try {
                assertThat(lockedLatch.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(notifications.claimAvailable(10, 5, Duration.ofMinutes(2)))
                        .singleElement().satisfies(claim -> assertThat(claim.notificationId()).isNotEqualTo(locked));
            } finally { release.countDown(); }
            transaction.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldCancelClaimedOldNotificationBeforeSendingAndDocumentInFlightSmtpWindow() throws Exception {
        Budget budget = sent();
        var claimed = notifications.claimAvailable(1, 5, Duration.ofMinutes(2)).getFirst();
        allowResend();
        budgets.resendForApproval(budget.getBudgetId(), "customer@example.test");
        assertThat(notifications.prepareDelivery(claimed)).isFalse();
        assertThat(notifications.markSent(claimed)).isFalse();

        String activeHash = jdbc.queryForObject("SELECT token_hash FROM budget_approval_authorization WHERE revoked_at IS NULL", String.class);
        try (var server = new SimulatedSmtpServer(); var worker = Executors.newSingleThreadExecutor()) {
            server.receivedData = new CountDownLatch(1);
            server.releaseAcceptance = new CountDownLatch(1);
            var delivery = worker.submit(() -> processor(server).processPending());
            try {
                assertThat(server.receivedData.await(10, TimeUnit.SECONDS)).isTrue();
                allowResend();
                budgets.resendForApproval(budget.getBudgetId(), "customer@example.test");
                assertThat(notifications.isAuthorizationUsable(activeHash)).isFalse();
            } finally { server.releaseAcceptance.countDown(); }
            delivery.get(10, TimeUnit.SECONDS);
            assertThat(server.messages()).hasSize(1); // SMTP já em andamento pode aceitar o link revogado.
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_email_notification WHERE status = 'CANCELLED'", Integer.class)).isEqualTo(2);
            assertThat(notifications.findLatest(budget.getBudgetId()).orElseThrow().status().name()).isEqualTo("PENDING");
        }
    }

    private BudgetNotificationProcessor processor(SimulatedSmtpServer server) {
        var mail = new JavaMailSenderImpl();
        mail.setHost("127.0.0.1"); mail.setPort(server.port()); mail.setDefaultEncoding("UTF-8");
        mail.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "3000");
        mail.getJavaMailProperties().setProperty("mail.smtp.timeout", "10000");
        mail.getJavaMailProperties().setProperty("mail.smtp.writetimeout", "3000");
        var sender = new SmtpBudgetEmailSender(mail, "billing@example.test") {
            @Override public void send(BudgetApprovalEmail email) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                super.send(email);
            }
        };
        return new BudgetNotificationProcessor(notifications, protection, sender, properties);
    }

    @Test
    void shouldRecoverAfterSmtpAcceptanceWithoutLocalConfirmationAndMayDeliverDuplicate() throws Exception {
        sent();
        var abandoned = notifications.claimAvailable(1, 5, Duration.ofMinutes(2)).getFirst();
        assertThat(notifications.prepareDelivery(abandoned)).isTrue();
        try (var server = new SimulatedSmtpServer()) {
            var mail = new JavaMailSenderImpl();
            mail.setHost("127.0.0.1"); mail.setPort(server.port()); mail.setDefaultEncoding("UTF-8");
            mail.getJavaMailProperties().setProperty("mail.smtp.timeout", "3000");
            new SmtpBudgetEmailSender(mail, "billing@example.test").send(
                    protection.unprotect(abandoned.authorizationId(), abandoned.protectedContent()));
            // Simular queda após aceite SMTP, antes da confirmação no PostgreSQL.
            jdbc.execute("UPDATE budget_email_notification SET lease_until = statement_timestamp() - INTERVAL '1 second'");
            processor(server).processPending();
            assertThat(server.messages()).hasSize(2);
            assertThat(server.messages().getFirst()).isEqualTo(server.messages().getLast());
            assertThat(notification().get("status")).isEqualTo("SENT");
            assertThat(notification().get("attempt_count")).isEqualTo(2);
            assertThat(notifications.markSent(abandoned)).isFalse();
        }
    }

    @Test
    void shouldRunTwoProcessorsWithoutSharingValidLeases() throws Exception {
        sent(); sent();
        try (var server = new SimulatedSmtpServer(); var workers = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            var first = workers.submit(() -> { barrier.await(10, TimeUnit.SECONDS); processor(server).processPending(); return null; });
            var second = workers.submit(() -> { barrier.await(10, TimeUnit.SECONDS); processor(server).processPending(); return null; });
            first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
            assertThat(server.messages()).hasSize(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_email_notification WHERE status = 'SENT' AND attempt_count = 1", Integer.class)).isEqualTo(2);
        }
    }

    private RuntimeException resendAfterBarrier(Budget budget, CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try { budgets.resendForApproval(budget.getBudgetId(), "customer@example.test"); return null; }
        catch (RuntimeException exception) { return exception; }
    }

    private Budget sent() { return budgets.sendForApproval(draft(UUID.randomUUID()).getBudgetId(), "customer@example.test"); }
    private Budget draft(UUID serviceOrderId) {
        return budgets.createDraft(serviceOrderId, new BigDecimal("123.45"), new BudgetComposition(List.of(
                new BudgetComposition.ServiceItem(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, "COMPLETED", List.of()))));
    }
    private Map<String, Object> notification() { return jdbc.queryForMap("SELECT * FROM budget_email_notification ORDER BY created_at DESC, notification_id DESC LIMIT 1"); }
    private String hash() { return jdbc.queryForObject("SELECT token_hash FROM budget_approval_authorization", String.class); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private long rowVersion(Budget budget) { return jdbc.queryForObject("SELECT row_version FROM budget WHERE budget_id = ?", Long.class, budget.getBudgetId()); }
    private void allowResend() { jdbc.execute("UPDATE budget_email_notification SET created_at = statement_timestamp() - INTERVAL '31 seconds'"); }
    private void expireAuthorization() { jdbc.execute("UPDATE budget_approval_authorization SET created_at = statement_timestamp() - INTERVAL '49 hours', expires_at = statement_timestamp() - INTERVAL '1 hour'"); }
    private void makeDue() { jdbc.execute("UPDATE budget_email_notification SET next_attempt_at = statement_timestamp() - INTERVAL '1 second'"); }

    @Test
    void shouldConfirmWholeBatchWithoutResendingWhenLoggingFails() throws Exception {
        sent(); sent();
        try (var server = new SimulatedSmtpServer(); var logging = new FailingLogAppender(BudgetNotificationProcessor.class)) {
            var worker = processor(server);
            worker.processPending();
            worker.processPending();
            assertThat(server.messages()).hasSize(2);
            assertThat(logging.eventCodes()).containsExactly("BUDGET_NOTIFICATION_SENT", "BUDGET_NOTIFICATION_SENT");
            assertThat(jdbc.queryForList("SELECT status FROM budget_email_notification", String.class)).containsOnly("SENT");
            assertThat(jdbc.queryForList("SELECT attempt_count FROM budget_email_notification", Integer.class)).containsOnly(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_email_notification WHERE sent_at IS NOT NULL AND protected_content IS NULL AND lease_token IS NULL AND lease_until IS NULL", Integer.class))
                    .isEqualTo(2);
        }
    }

    @Test
    void shouldKeepPersistedRetryScheduleAndExhaustionWhenLoggingFails() throws Exception {
        sent();
        try (var server = new SimulatedSmtpServer(); var logging = new FailingLogAppender(BudgetNotificationProcessor.class)) {
            server.reject = true;
            var worker = processor(server);
            worker.processPending();
            assertThat(notification().get("status")).isEqualTo("PENDING");
            assertThat(notification().get("lease_token")).isNull();
            assertThat(jdbc.queryForObject("SELECT next_attempt_at > last_attempt_at FROM budget_email_notification", Boolean.class)).isTrue();
            worker.processPending();
            assertThat(notification().get("attempt_count")).isEqualTo(1);
            for (int attempt = 2; attempt <= properties.maxAttempts(); attempt++) {
                makeDue();
                worker.processPending();
            }
            worker.processPending();
            assertThat(notification().get("status")).isEqualTo("FAILED");
            assertThat(notification().get("attempt_count")).isEqualTo(properties.maxAttempts());
            assertThat(notification().get("last_error")).isEqualTo("SMTP_SEND_FAILED");
            assertThat(notification().get("protected_content")).isNull();
            assertThat(notification().get("sent_at")).isNull();
            assertThat(logging.eventCodes()).hasSize(properties.maxAttempts()).containsOnly("BUDGET_NOTIFICATION_SEND_FAILED");
        }
    }

}
