package br.com.fiap.numberone.billing.budget.infrastructure.outbox;

import br.com.fiap.numberone.billing.infrastructure.observability.support.FailingLogAppender;
import br.com.fiap.numberone.billing.budget.application.exceptions.IntegrationEventPublicationException;
import br.com.fiap.numberone.billing.budget.application.exceptions.IntegrationEventPublicationException.Failure;
import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.application.gateways.IntegrationEventPublicationGateway;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.valueobjects.BudgetComposition;
import br.com.fiap.numberone.billing.budget.infrastructure.config.OutboxPublicationConfig;
import br.com.fiap.numberone.billing.budget.infrastructure.config.OutboxPublisherProperties;
import br.com.fiap.numberone.billing.budget.infrastructure.config.SqsPublicationProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties = {
        "billing.notifications.enabled=false",
        "billing.notifications.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "billing.notifications.approval-base-url=https://billing.test/decision",
        "billing.integrations.os-base-url=http://os.test",
        "billing.integrations.inventory-base-url=http://inventory.test",
        "billing.outbox.enabled=false"
})
class OutboxPublicationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final GenericContainer<?> LOCALSTACK = new GenericContainer<>("localstack/localstack:4.14.0")
            .withEnv("SERVICES", "sqs").withEnv("AWS_DEFAULT_REGION", "us-east-1")
            .withEnv("AWS_ACCESS_KEY_ID", "test").withEnv("AWS_SECRET_ACCESS_KEY", "test")
            .withEnv("SQS_ENDPOINT_STRATEGY", "dynamic").withExposedPorts(4566)
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("docker/localstack/10-billing-sqs.sh").toAbsolutePath().toString(), 0755),
                    "/etc/localstack/init/ready.d/10-billing-sqs.sh")
            .waitingFor(Wait.forLogMessage(".*BILLING_SQS_READY.*", 1)).withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static SqsClient sqs;
    private static URI queueUrl;

    @BeforeAll
    static void sqsClient() {
        URI endpoint = URI.create("http://" + LOCALSTACK.getHost() + ":" + LOCALSTACK.getMappedPort(4566));
        var properties = new SqsPublicationProperties("us-east-1", endpoint.resolve("/unused"), endpoint,
                Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(5));
        sqs = new OutboxPublicationConfig().sqsClient(properties,
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
        queueUrl = URI.create(sqs.getQueueUrl(request -> request.queueName("numberone-billing-events")).queueUrl());
    }

    @AfterAll
    static void closeClient() {
        if (sqs != null) sqs.close();
    }

    @Autowired private IntegrationEventOutboxRepository repository;
    @Autowired private BudgetGateway budgets;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JsonMapper jsonMapper;

    private final OutboxPublisherProperties properties = new OutboxPublisherProperties(10, Duration.ofSeconds(60),
            Duration.ofSeconds(1), 3, Duration.ofSeconds(5), Duration.ofSeconds(10));

    @BeforeEach
    void isolatePublicationFixtures() {
        // Somente a base descartável deste teste; nenhuma conexão com banco externo.
        jdbc.update("DELETE FROM integration_event_outbox");
        while (true) {
            var messages = sqs.receiveMessage(request -> request.queueUrl(queueUrl.toString()).maxNumberOfMessages(10)).messages();
            if (messages.isEmpty()) break;
            for (var message : messages) sqs.deleteMessage(request -> request.queueUrl(queueUrl.toString()).receiptHandle(message.receiptHandle()));
        }
    }

    @Test
    void shouldPublishAllBudgetContractsFromPersistedJsonWithoutAnOpenTransaction() throws Exception {
        Budget approved = draft();
        budgets.sendForApproval(approved.getBudgetId(), "customer@example.test");
        budgets.approve(approved.getBudgetId());
        Budget rejected = draft();
        budgets.sendForApproval(rejected.getBudgetId(), "customer@example.test");
        budgets.reject(rejected.getBudgetId());
        var expected = new HashMap<UUID, String>();
        for (var row : jdbc.queryForList("SELECT event_id, envelope::text AS envelope FROM integration_event_outbox")) {
            expected.put((UUID) row.get("event_id"), row.get("envelope").toString());
        }
        var adapter = adapter();
        new OutboxPublisher(repository, body -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            adapter.publish(body);
        }, properties).publishPendingEvents();

        var received = receiveDistinct(expected.size());
        assertThat(received).isEqualTo(expected);
        assertThat(received.values()).anySatisfy(body -> {
            var event = jsonMapper.readTree(body);
            assertThat(event.get("eventType").asString()).isEqualTo("OrcamentoAprovado");
            assertThat(event.get("payload").get("approvedAmount").asString()).isEqualTo("550.24");
        });
        for (var body : received.values()) {
            assertThat(jsonMapper.readTree(body).get("payload").get("budgetVersion").asInt()).isEqualTo(1);
        }
        assertThat(jdbc.queryForList("SELECT event_type FROM integration_event_outbox", String.class))
                .contains("OrcamentoEnviado", "OrcamentoAprovado", "OrcamentoRejeitado");
        assertThat(jdbc.queryForList("SELECT status FROM integration_event_outbox", String.class)).containsOnly("PUBLISHED");
        assertThat(jdbc.queryForList("SELECT attempt_count FROM integration_event_outbox", Integer.class)).containsOnly(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_event_outbox WHERE published_at IS NOT NULL AND lease_token IS NULL AND lease_until IS NULL", Integer.class))
                .isEqualTo(expected.size());
        var redrive = sqs.getQueueAttributes(request -> request.queueUrl(queueUrl.toString())
                .attributeNames(software.amazon.awssdk.services.sqs.model.QueueAttributeName.REDRIVE_POLICY)).attributesAsStrings().get("RedrivePolicy");
        assertThat(redrive).contains("numberone-billing-events-dlq");
        var rerun = LOCALSTACK.execInContainer("sh", "/etc/localstack/init/ready.d/10-billing-sqs.sh");
        assertThat(rerun.getExitCode()).isZero();
    }

    @Test
    void shouldLimitBatchAndSkipRowsLockedByAnotherInstance() throws Exception {
        pending(); pending(); pending();
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                var events = repository.claimAvailable(1, 3, Duration.ofMinutes(1));
                claimed.countDown();
                await(release);
                return events;
            }));
            try {
                await(claimed);
                var second = workers.submit(() -> repository.claimAvailable(2, 3, Duration.ofMinutes(1))).get(5, TimeUnit.SECONDS);
                assertThat(second).hasSize(2);
                release.countDown();
                var firstEvents = first.get(5, TimeUnit.SECONDS);
                assertThat(firstEvents).hasSize(1);
                assertThat(second).extracting(ClaimedIntegrationEvent::eventId).doesNotContain(firstEvents.getFirst().eventId());
                assertThat(second).extracting(ClaimedIntegrationEvent::leaseToken).doesNotHaveDuplicates();
                assertThat(repository.claimAvailable(10, 3, Duration.ofMinutes(1))).isEmpty();
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void shouldNotSendSameEventWhileOtherPublisherOwnsItAndShouldReleaseDatabaseLocksBeforeIo() throws Exception {
        UUID eventId = pending();
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger sends = new AtomicInteger();
        IntegrationEventPublicationGateway gateway = body -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            sends.incrementAndGet();
            if (UUID.fromString(jsonMapper.readTree(body).get("eventId").asString()).equals(eventId)) {
                sending.countDown();
                await(release);
            }
        };
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> new OutboxPublisher(repository, gateway, properties).publishPendingEvents());
            try {
                await(sending);
                new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                        jdbc.queryForObject("SELECT event_id FROM integration_event_outbox WHERE event_id = ? FOR UPDATE NOWAIT", UUID.class, eventId));
                workers.submit(() -> new OutboxPublisher(repository, gateway, properties).publishPendingEvents()).get(5, TimeUnit.SECONDS);
                assertThat(sends).hasValue(1);
                UUID other = pending();
                workers.submit(() -> new OutboxPublisher(repository, gateway, properties).publishPendingEvents()).get(5, TimeUnit.SECONDS);
                assertThat(sends).hasValue(2);
                assertThat(state(other).get("status")).isEqualTo("PUBLISHED");
                assertThat(state(eventId).get("status")).isEqualTo("PENDING");
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
        }
        assertThat(state(eventId).get("status")).isEqualTo("PUBLISHED");
    }

    @Test
    void shouldReclaimExpiredLeaseAndRejectConfirmationOrRescheduleByOldToken() {
        UUID eventId = pending();
        var old = repository.claimAvailable(1, 3, Duration.ofMinutes(1)).getFirst();
        expire(eventId);
        assertThat(repository.markPublished(old)).isFalse();
        var current = repository.claimAvailable(1, 3, Duration.ofMinutes(1)).getFirst();
        assertThat(current.leaseToken()).isNotEqualTo(old.leaseToken());
        assertThat(current.attemptCount()).isEqualTo(2);
        assertThat(repository.markPublished(old)).isFalse();
        assertThat(repository.recordFailure(old, "OLD_FAILURE", false, 3, Duration.ofSeconds(1))).isFalse();
        assertThat(repository.hasValidLease(current)).isTrue();
        assertThat(repository.markPublished(current)).isTrue();
        assertThat(state(eventId).get("status")).isEqualTo("PUBLISHED");
    }

    @Test
    void shouldStopRecoveringExpiredLeasesWhenAttemptsAreExhausted() {
        UUID eventId = pending();
        repository.claimAvailable(1, 1, Duration.ofMinutes(1));
        expire(eventId);
        assertThat(repository.claimAvailable(1, 1, Duration.ofMinutes(1))).isEmpty();
        var state = state(eventId);
        assertThat(state.get("status")).isEqualTo("NEEDS_REVIEW");
        assertThat(state.get("attempt_count")).isEqualTo(1);
        assertThat(state.get("last_error")).isEqualTo("ATTEMPTS_EXHAUSTED_AFTER_LEASE_EXPIRY");
        assertThat(state.get("lease_token")).isNull();
    }

    @Test
    void shouldBackOffAndPreserveFailureUntilReviewWithoutPublishing() {
        UUID eventId = pending();
        String envelope = state(eventId).get("envelope").toString();
        var publisher = new OutboxPublisher(repository, body -> {
            throw new IntegrationEventPublicationException(Failure.UNAVAILABLE, 503);
        }, properties);
        publisher.publishPendingEvents();
        assertPendingFailure(eventId, 1, 5);
        publisher.publishPendingEvents();
        assertThat(state(eventId).get("attempt_count")).isEqualTo(1);
        makeAvailable(eventId);
        publisher.publishPendingEvents();
        assertPendingFailure(eventId, 2, 10);
        makeAvailable(eventId);
        publisher.publishPendingEvents();
        var state = state(eventId);
        assertThat(state.get("status")).isEqualTo("NEEDS_REVIEW");
        assertThat(state.get("attempt_count")).isEqualTo(3);
        assertThat(state.get("last_error")).isEqualTo("SQS_UNAVAILABLE HTTP_503");
        assertThat(state.get("published_at")).isNull();
        assertThat(state.get("envelope")).isEqualTo(envelope);
    }

    @Test
    void shouldReviewRejectedRequestsAndSanitizeUnknownFailures() {
        UUID rejected = pending();
        new OutboxPublisher(repository, body -> { throw new IntegrationEventPublicationException(Failure.REJECTED, 403); }, properties)
                .publishPendingEvents();
        assertThat(state(rejected).get("status")).isEqualTo("NEEDS_REVIEW");
        UUID unknown = pending();
        new OutboxPublisher(repository, body -> { throw new IllegalStateException("credential-secret-do-not-record"); }, properties)
                .publishPendingEvents();
        assertThat(state(unknown).get("last_error")).isEqualTo("PUBLICATION_FAILED");
        assertThat(state(unknown).get("status")).isEqualTo("PENDING");
    }

    @Test
    void shouldResumePersistedRetryWithANewPublisherInstance() {
        UUID eventId = pending();
        new OutboxPublisher(repository, body -> { throw new IntegrationEventPublicationException(Failure.UNAVAILABLE, 503); }, properties)
                .publishPendingEvents();
        makeAvailable(eventId);
        new OutboxPublisher(repository, adapter(), properties).publishPendingEvents();
        assertThat(state(eventId).get("status")).isEqualTo("PUBLISHED");
        assertThat(state(eventId).get("attempt_count")).isEqualTo(2);
        assertThat(receiveDistinct(1)).containsKey(eventId);
    }

    @Test
    void shouldAllowDuplicateDeliveryAfterExternalSuccessAndFailedLocalConfirmation() {
        UUID eventId = pending();
        String original = state(eventId).get("envelope").toString();
        String constraint = "test_publication_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("ALTER TABLE integration_event_outbox ADD CONSTRAINT " + constraint + " CHECK (status <> 'PUBLISHED')");
        try {
            new OutboxPublisher(repository, adapter(), properties).publishPendingEvents();
            assertThat(receiveDistinct(1)).containsEntry(eventId, original);
            assertThat(state(eventId).get("status")).isEqualTo("PENDING");
            assertThat(state(eventId).get("published_at")).isNull();
            assertThat(state(eventId).get("lease_token")).isNotNull();
        } finally {
            jdbc.execute("ALTER TABLE integration_event_outbox DROP CONSTRAINT " + constraint);
        }
        expire(eventId);
        new OutboxPublisher(repository, adapter(), properties).publishPendingEvents();
        assertThat(receiveDistinct(1)).containsEntry(eventId, original);
        assertThat(state(eventId).get("status")).isEqualTo("PUBLISHED");
        assertThat(state(eventId).get("attempt_count")).isEqualTo(2);
    }

    private void assertPendingFailure(UUID eventId, int attempts, int delaySeconds) {
        var state = state(eventId);
        assertThat(state.get("status")).isEqualTo("PENDING");
        assertThat(state.get("attempt_count")).isEqualTo(attempts);
        assertThat(state.get("published_at")).isNull();
        assertThat(state.get("lease_token")).isNull();
        double delay = jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM next_attempt_at - statement_timestamp()) FROM integration_event_outbox WHERE event_id = ?", Double.class, eventId);
        assertThat(delay).isBetween(delaySeconds - 2.0, delaySeconds + 0.1);
    }

    private Budget draft() {
        return budgets.createDraft(UUID.randomUUID(), new BigDecimal("550.24"), new BudgetComposition(List.of(), 1));
    }

    private UUID pending() {
        Budget draft = draft();
        budgets.sendForApproval(draft.getBudgetId(), "customer@example.test");
        return jdbc.queryForObject("SELECT event_id FROM integration_event_outbox WHERE budget_id = ?", UUID.class, draft.getBudgetId());
    }

    private Map<String, Object> state(UUID id) {
        return jdbc.queryForMap("""
                SELECT event_id, event_type, schema_version, service_order_id, budget_id, budget_version,
                    envelope::text AS envelope, status, created_at, published_at, attempt_count, next_attempt_at,
                    last_error, lease_token, lease_until FROM integration_event_outbox WHERE event_id = ?
                """, id);
    }

    private void expire(UUID eventId) {
        jdbc.update("UPDATE integration_event_outbox SET lease_until = statement_timestamp() - INTERVAL '1 second' WHERE event_id = ?", eventId);
    }

    private void makeAvailable(UUID eventId) {
        jdbc.update("UPDATE integration_event_outbox SET next_attempt_at = statement_timestamp() - INTERVAL '1 second' WHERE event_id = ?", eventId);
    }

    private SqsIntegrationEventPublicationGateway adapter() {
        return new SqsIntegrationEventPublicationGateway(sqs, queueUrl);
    }

    private Map<UUID, String> receiveDistinct(int count) {
        Map<UUID, String> messages = new HashMap<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (messages.size() < count && System.nanoTime() < deadline) {
            for (var message : sqs.receiveMessage(request -> request.queueUrl(queueUrl.toString()).maxNumberOfMessages(10).waitTimeSeconds(1)).messages()) {
                UUID id = UUID.fromString(jsonMapper.readTree(message.body()).get("eventId").asString());
                messages.put(id, message.body());
                sqs.deleteMessage(request -> request.queueUrl(queueUrl.toString()).receiptHandle(message.receiptHandle()));
            }
        }
        assertThat(messages).hasSize(count);
        return messages;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void shouldConfirmWholeBatchWithoutRepublishingWhenLoggingFails() {
        UUID first = pending();
        UUID next = pending();
        AtomicInteger publications = new AtomicInteger();
        var adapter = adapter();
        var publisher = new OutboxPublisher(repository, body -> { publications.incrementAndGet(); adapter.publish(body); }, properties);
        try (var logging = new FailingLogAppender(OutboxPublisher.class)) {
            publisher.publishPendingEvents();
            publisher.publishPendingEvents();
            assertThat(publications).hasValue(2);
            assertThat(logging.eventCodes()).containsExactly("BUDGET_EVENT_PUBLISHED", "BUDGET_EVENT_PUBLISHED");
        }
        assertThat(receiveDistinct(2)).containsOnlyKeys(first, next);
        assertThat(jdbc.queryForList("SELECT status FROM integration_event_outbox", String.class)).containsOnly("PUBLISHED");
        assertThat(jdbc.queryForList("SELECT attempt_count FROM integration_event_outbox", Integer.class)).containsOnly(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_event_outbox WHERE published_at IS NOT NULL AND lease_token IS NULL AND lease_until IS NULL", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void shouldKeepPersistedRetryScheduleAndExhaustionWhenLoggingFails() {
        UUID eventId = pending();
        AtomicInteger publications = new AtomicInteger();
        var failure = new IntegrationEventPublicationException(IntegrationEventPublicationException.Failure.TIMEOUT, 504,
                new IllegalStateException("Falha externa"));
        var publisher = new OutboxPublisher(repository, body -> { publications.incrementAndGet(); throw failure; }, properties);
        try (var logging = new FailingLogAppender(OutboxPublisher.class)) {
            publisher.publishPendingEvents();
            assertPendingFailure(eventId, 1, 5);
            publisher.publishPendingEvents();
            assertThat(publications).hasValue(1);
            for (int attempt = 2; attempt <= properties.maxAttempts(); attempt++) {
                makeAvailable(eventId);
                publisher.publishPendingEvents();
            }
            publisher.publishPendingEvents();
            assertThat(publications).hasValue(properties.maxAttempts());
            assertThat(state(eventId).get("status")).isEqualTo("NEEDS_REVIEW");
            assertThat(state(eventId).get("attempt_count")).isEqualTo(properties.maxAttempts());
            assertThat(state(eventId).get("published_at")).isNull();
            assertThat(state(eventId).get("lease_token")).isNull();
            assertThat(state(eventId).get("last_error")).isEqualTo(failure.getMessage());
            assertThat(logging.eventCodes()).hasSize(properties.maxAttempts()).containsOnly("BUDGET_EVENT_PUBLICATION_FAILED");
        }
    }

}
