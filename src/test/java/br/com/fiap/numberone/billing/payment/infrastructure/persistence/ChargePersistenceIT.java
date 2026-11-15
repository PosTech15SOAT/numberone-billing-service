package br.com.fiap.numberone.billing.payment.infrastructure.persistence;

import br.com.fiap.numberone.billing.payment.application.exceptions.ChargeNotFoundException;
import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.domain.valueobjects.BudgetComposition;
import br.com.fiap.numberone.billing.payment.application.exceptions.PaymentPersistenceConflictException;
import br.com.fiap.numberone.billing.payment.application.gateways.ChargeGateway;
import br.com.fiap.numberone.billing.payment.domain.entities.Charge;
import br.com.fiap.numberone.billing.payment.domain.entities.PaymentAttempt;
import br.com.fiap.numberone.billing.payment.domain.enums.ChargeStatus;
import br.com.fiap.numberone.billing.payment.domain.enums.PaymentAttemptStatus;
import br.com.fiap.numberone.billing.payment.domain.enums.PaymentMethod;
import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentDataException;
import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentStateException;
import br.com.fiap.numberone.billing.payment.domain.exceptions.PaymentIdempotencyConflictException;
import br.com.fiap.numberone.billing.payment.domain.references.ServiceOrderPaymentContext;
import br.com.fiap.numberone.billing.payment.infrastructure.persistence.entities.ChargeEntity;
import br.com.fiap.numberone.billing.payment.infrastructure.persistence.entities.PaymentAttemptEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(properties = {
        "billing.notifications.enabled=false",
        "billing.integrations.os-base-url=http://os.test",
        "billing.integrations.inventory-base-url=http://inventory.test"
})
class ChargePersistenceIT {
    private Charge confirmPayment(UUID chargeId, UUID attemptId, String paymentId) {
        jdbcTemplate.update("UPDATE payment_attempt SET provider_order_id=COALESCE(provider_order_id, ?) WHERE attempt_id=?", "ORD" + attemptId, attemptId);
        Charge charge = gateway.findById(chargeId).orElseThrow(ChargeNotFoundException::new);
        return gateway.confirmPayment(br.com.fiap.numberone.billing.payment.support.PaymentConfirmationFixtures.approved(charge, attemptId, paymentId));
    }

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final BigDecimal AMOUNT = new BigDecimal("530.12");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private ChargeGateway gateway;
    @Autowired private BudgetGateway budgets;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;
    @Autowired private DataSource dataSource;

    @Test
    void shouldRoundTripExactCommercialDataAndAttemptHistory() {
        Charge created = createCharge();
        PaymentAttempt failed = gateway.startAttempt(created.getChargeId(), UUID.randomUUID());
        gateway.recordPendingAttempt(created.getChargeId(), failed.getAttemptId(), "failed-" + UUID.randomUUID());
        gateway.confirmAttemptFailure(created.getChargeId(), failed.getAttemptId());
        PaymentAttempt paid = gateway.startAttempt(created.getChargeId(), UUID.randomUUID());
        String providerId = "paid-" + UUID.randomUUID();
        confirmPayment(created.getChargeId(), paid.getAttemptId(), providerId);

        Charge restored = gateway.findById(created.getChargeId()).orElseThrow();
        assertThat(restored.getAmount()).isEqualTo(AMOUNT);
        assertThat(restored.getBudgetId()).isEqualTo(created.getBudgetId());
        assertThat(restored.getServiceOrderId()).isEqualTo(created.getServiceOrderId());
        assertThat(restored.getCurrency()).isEqualTo(BRL);
        assertThat(restored.getCreatedAt()).isEqualTo(created.getCreatedAt());
        assertThat(restored.getStatus()).isEqualTo(ChargeStatus.PAID);
        assertThat(restored.getAttempts()).extracting(PaymentAttempt::getStatus)
                .containsExactly(PaymentAttemptStatus.FAILED, PaymentAttemptStatus.PAID);
        assertThat(restored.getAttempts().getLast().getProviderPaymentId()).isEqualTo(providerId);
        assertThat(restored.getAttempts().getLast().getUpdatedAt()).isEqualTo(restored.getUpdatedAt());
        assertThat(gateway.findByServiceOrderId(created.getServiceOrderId()).orElseThrow().getChargeId())
                .isEqualTo(created.getChargeId());
        assertThat(version(created.getChargeId())).isEqualTo(5);
    }

    @ParameterizedTest
    @CsvSource({"DRAFT,10", "SENT,10", "REJECTED,10", "APPROVED,0", "APPROVED,-10", "APPROVED,NULL"})
    void shouldRequirePositiveApprovedBudgetFromSameOrder(String status, String amount) {
        UUID orderId = UUID.randomUUID();
        UUID budgetId = insertBudget(orderId, status, "NULL".equals(amount) ? null : new BigDecimal(amount));
        assertThatThrownBy(() -> gateway.createCharge(completed(orderId), budgetId, BRL))
                .isInstanceOf(InvalidPaymentDataException.class);
        assertThat(chargeCount(orderId)).isZero();
    }

    @Test
    void shouldRejectMissingBudgetAnotherOrderAndUncompletedOrder() {
        UUID orderId = UUID.randomUUID();
        UUID budgetId = insertBudget(orderId, "APPROVED", AMOUNT);
        assertThatThrownBy(() -> gateway.createCharge(completed(orderId), UUID.randomUUID(), BRL))
                .isInstanceOf(InvalidPaymentDataException.class);
        assertThatThrownBy(() -> gateway.createCharge(completed(UUID.randomUUID()), budgetId, BRL))
                .isInstanceOf(InvalidPaymentDataException.class);
        assertThatThrownBy(() -> gateway.createCharge(new ServiceOrderPaymentContext(orderId, "IN_PROGRESS"), budgetId, BRL))
                .isInstanceOf(InvalidPaymentDataException.class);
        assertThat(chargeCount(orderId)).isZero();
    }

    @Test
    void shouldReplayChargeCreationAndRejectDifferentCommercialReference() {
        Charge original = createCharge();
        Charge replay = gateway.createCharge(completed(original.getServiceOrderId()), original.getBudgetId(), BRL);
        assertThat(replay.getChargeId()).isEqualTo(original.getChargeId());
        assertThat(replay.getCreatedAt()).isEqualTo(original.getCreatedAt());
        assertThatThrownBy(() -> gateway.createCharge(completed(original.getServiceOrderId()), original.getBudgetId(),
                Currency.getInstance("USD"))).isInstanceOf(PaymentIdempotencyConflictException.class);
        UUID anotherBudget = insertBudget(original.getServiceOrderId(), "APPROVED", AMOUNT);
        assertThatThrownBy(() -> gateway.createCharge(completed(original.getServiceOrderId()), anotherBudget, BRL))
                .isInstanceOf(PaymentIdempotencyConflictException.class);
        assertThat(chargeCount(original.getServiceOrderId())).isEqualTo(1);
        assertThat(version(original.getChargeId())).isZero();
    }

    @Test
    void shouldDeduplicateConcurrentChargeCreation() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID budgetId = insertBudget(orderId, "APPROVED", AMOUNT);
        List<Outcome<Charge>> outcomes = concurrently(
                () -> gateway.createCharge(completed(orderId), budgetId, BRL),
                () -> gateway.createCharge(completed(orderId), budgetId, BRL));
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.failure()).isNull());
        assertThat(outcomes.getFirst().value().getChargeId()).isEqualTo(outcomes.getLast().value().getChargeId());
        assertThat(chargeCount(orderId)).isEqualTo(1);
    }

    @Test
    void shouldSerializeConcurrentAttemptsWithDifferentKeys() throws Exception {
        Charge charge = createCharge();
        List<Outcome<PaymentAttempt>> outcomes = concurrently(
                () -> gateway.startAttempt(charge.getChargeId(), UUID.randomUUID()),
                () -> gateway.startAttempt(charge.getChargeId(), UUID.randomUUID()));
        assertThat(outcomes.stream().filter(outcome -> outcome.failure() == null)).hasSize(1);
        assertThat(outcomes.stream().map(Outcome::failure).filter(failure -> failure != null))
                .singleElement().isInstanceOf(InvalidPaymentStateException.class);
        assertThat(gateway.findById(charge.getChargeId()).orElseThrow().getAttempts()).hasSize(1);
        assertThat(version(charge.getChargeId())).isEqualTo(1);
    }

    @Test
    void shouldReplayConcurrentAttemptsWithTheSameKey() throws Exception {
        Charge charge = createCharge();
        UUID key = UUID.randomUUID();
        List<Outcome<PaymentAttempt>> outcomes = concurrently(
                () -> gateway.startAttempt(charge.getChargeId(), key),
                () -> gateway.startAttempt(charge.getChargeId(), key));
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.failure()).isNull());
        assertThat(outcomes.getFirst().value().getAttemptId()).isEqualTo(outcomes.getLast().value().getAttemptId());
        assertThat(version(charge.getChargeId())).isEqualTo(1);
        gateway.recordPaymentMethod(charge.getChargeId(), outcomes.getFirst().value().getAttemptId(), PaymentMethod.PIX);
        assertThat(gateway.startAttempt(charge.getChargeId(), key).getPaymentMethod()).isEqualTo(PaymentMethod.PIX);
    }

    @Test
    void shouldEnforceGlobalKeyUniquenessAndRollbackLosingAggregate() throws Exception {
        Charge first = createCharge();
        Charge second = createCharge();
        UUID key = UUID.randomUUID();
        List<Outcome<PaymentAttempt>> outcomes = concurrently(
                () -> gateway.startAttempt(first.getChargeId(), key),
                () -> gateway.startAttempt(second.getChargeId(), key));
        assertThat(outcomes.stream().filter(outcome -> outcome.failure() == null)).hasSize(1);
        assertThat(outcomes.stream().map(Outcome::failure).filter(failure -> failure != null)).singleElement()
                .isInstanceOf(PaymentPersistenceConflictException.class)
                .satisfies(failure -> assertThat(failure.getCause()).isInstanceOf(DataIntegrityViolationException.class));
        UUID losingId = outcomes.getFirst().failure() != null ? first.getChargeId() : second.getChargeId();
        Charge losing = gateway.findById(losingId).orElseThrow();
        assertThat(losing.getAttempts()).isEmpty();
        assertThat(losing.getUpdatedAt()).isEqualTo(losing.getCreatedAt());
        assertThat(version(losingId)).isZero();
        assertThat(gateway.startAttempt(losingId, UUID.randomUUID())).isNotNull();
    }

    @Test
    void shouldRecoverUnknownResultWithoutCreatingAnotherOperation() {
        Charge charge = createCharge();
        UUID key = UUID.randomUUID();
        PaymentAttempt attempt = gateway.startAttempt(charge.getChargeId(), key);
        gateway.markAttemptUnknown(charge.getChargeId(), attempt.getAttemptId());

        assertThat(gateway.startAttempt(charge.getChargeId(), key).getStatus())
                .isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThatThrownBy(() -> gateway.startAttempt(charge.getChargeId(), UUID.randomUUID()))
                .isInstanceOf(InvalidPaymentStateException.class);
        String providerId = "resolved-" + UUID.randomUUID();
        gateway.recordPendingAttempt(charge.getChargeId(), attempt.getAttemptId(), providerId);
        Charge paid = confirmPayment(charge.getChargeId(), attempt.getAttemptId(), providerId);
        assertThat(paid.getStatus()).isEqualTo(ChargeStatus.PAID);
        assertThat(paid.getAttempts()).hasSize(1);
        long version = version(charge.getChargeId());
        confirmPayment(charge.getChargeId(), attempt.getAttemptId(), providerId);
        assertThat(version(charge.getChargeId())).isEqualTo(version);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentAttemptStatus.class, names = {"FAILED", "EXPIRED"})
    void shouldAllowNewAttemptAfterConfirmedFailureOrExpiration(PaymentAttemptStatus terminal) {
        Charge charge = createCharge();
        PaymentAttempt attempt = gateway.startAttempt(charge.getChargeId(), UUID.randomUUID());
        gateway.markAttemptUnknown(charge.getChargeId(), attempt.getAttemptId());
        if (terminal == PaymentAttemptStatus.FAILED) {
            gateway.confirmAttemptFailure(charge.getChargeId(), attempt.getAttemptId());
        } else {
            gateway.confirmAttemptExpiration(charge.getChargeId(), attempt.getAttemptId());
        }
        PaymentAttempt next = gateway.startAttempt(charge.getChargeId(), UUID.randomUUID());
        confirmPayment(charge.getChargeId(), next.getAttemptId(), "confirmed-" + UUID.randomUUID());
        assertThatThrownBy(() -> confirmPayment(charge.getChargeId(), attempt.getAttemptId(), "late-payment"))
                .isInstanceOf(InvalidPaymentStateException.class);
        assertThat(gateway.findById(charge.getChargeId()).orElseThrow().getAttempts()).hasSize(2);
    }

    @Test
    void shouldDeduplicateConcurrentSettlement() throws Exception {
        Charge charge = createCharge();
        PaymentAttempt attempt = gateway.startAttempt(charge.getChargeId(), UUID.randomUUID());
        String providerId = "settled-" + UUID.randomUUID();
        List<Outcome<Charge>> outcomes = concurrently(
                () -> confirmPayment(charge.getChargeId(), attempt.getAttemptId(), providerId),
                () -> confirmPayment(charge.getChargeId(), attempt.getAttemptId(), providerId));
        assertThat(outcomes).allSatisfy(outcome -> {
            assertThat(outcome.failure()).isNull();
            assertThat(outcome.value().getStatus()).isEqualTo(ChargeStatus.PAID);
        });
        assertThat(version(charge.getChargeId())).isEqualTo(2);
    }

    @Test
    void shouldNotSettleDifferentChargesUsingTheSameProviderOperation() throws Exception {
        Charge first = createCharge();
        Charge second = createCharge();
        PaymentAttempt firstAttempt = gateway.startAttempt(first.getChargeId(), UUID.randomUUID());
        PaymentAttempt secondAttempt = gateway.startAttempt(second.getChargeId(), UUID.randomUUID());
        String providerId = "shared-" + UUID.randomUUID();
        List<Outcome<Charge>> outcomes = concurrently(
                () -> confirmPayment(first.getChargeId(), firstAttempt.getAttemptId(), providerId),
                () -> confirmPayment(second.getChargeId(), secondAttempt.getAttemptId(), providerId));
        assertThat(outcomes.stream().filter(outcome -> outcome.failure() == null)).hasSize(1);
        assertThat(outcomes.stream().map(Outcome::failure).filter(failure -> failure != null)).singleElement()
                .isInstanceOf(PaymentPersistenceConflictException.class);
        UUID losingId = outcomes.getFirst().failure() != null ? first.getChargeId() : second.getChargeId();
        Charge losing = gateway.findById(losingId).orElseThrow();
        assertThat(losing.getStatus()).isEqualTo(ChargeStatus.OPEN);
        assertThat(losing.getAttempts().getFirst().getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(losing.getAttempts().getFirst().getProviderPaymentId()).isNull();
        assertThat(version(losingId)).isEqualTo(1);
    }

    @Test
    void shouldHoldDatabaseLockUntilCommitAndAllowOtherChargesToProgress() throws Exception {
        Charge charge = createCharge();
        Charge independent = createCharge();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        UUID key = UUID.randomUUID();
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<PaymentAttempt> first = workers.submit(() -> transaction().execute(status -> {
                PaymentAttempt attempt = gateway.startAttempt(charge.getChargeId(), key);
                inserted.countDown();
                await(release);
                return attempt;
            }));
            try {
                await(inserted);
                Future<PaymentAttempt> second = workers.submit(() -> gateway.startAttempt(charge.getChargeId(), key));
                awaitDatabaseLock();
                assertThat(second.isDone()).isFalse();
                assertThat(gateway.startAttempt(independent.getChargeId(), UUID.randomUUID())).isNotNull();
                release.countDown();
                assertThat(second.get(15, TimeUnit.SECONDS).getAttemptId()).isEqualTo(first.get(15, TimeUnit.SECONDS).getAttemptId());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void shouldRefreshPreviouslyManagedChargeAndAttemptAfterAcquiringLock() throws Exception {
        Charge charge = createCharge();
        PaymentAttempt attempt = gateway.startAttempt(charge.getChargeId(), UUID.randomUUID());
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            transaction().executeWithoutResult(status -> {
                ChargeEntity staleCharge = entityManager.find(ChargeEntity.class, charge.getChargeId());
                PaymentAttemptEntity staleAttempt = entityManager.find(PaymentAttemptEntity.class, attempt.getAttemptId());
                try {
                    worker.submit(() -> gateway.confirmAttemptFailure(charge.getChargeId(), attempt.getAttemptId())).get(15, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
                assertThat(staleAttempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
                assertThat(staleCharge.getRowVersion()).isEqualTo(1);
                PaymentAttempt next = gateway.startAttempt(charge.getChargeId(), UUID.randomUUID());
                assertThat(next.getAttemptId()).isNotEqualTo(attempt.getAttemptId());
                assertThat(staleAttempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
            });
        }
        assertThat(gateway.findById(charge.getChargeId()).orElseThrow().getAttempts()).hasSize(2);
        assertThat(version(charge.getChargeId())).isEqualTo(3);
    }

    @Test
    void shouldRejectStaleJpaWriteUsingTechnicalVersion() throws Exception {
        Charge charge = createCharge();
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
                ChargeEntity stale = entityManager.find(ChargeEntity.class, charge.getChargeId());
                try {
                    worker.submit(() -> gateway.startAttempt(charge.getChargeId(), UUID.randomUUID()))
                            .get(15, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
                stale.updateState(ChargeStatus.OPEN, Instant.now().plusSeconds(1));
                entityManager.flush();
            })).isInstanceOf(OptimisticLockException.class);
        }
        assertThat(version(charge.getChargeId())).isEqualTo(1);
    }

    @Test
    void shouldRollbackEntireLocalSettlementWhenCallingTransactionFails() {
        Charge charge = createCharge();
        PaymentAttempt attempt = gateway.startAttempt(charge.getChargeId(), UUID.randomUUID());
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
            confirmPayment(charge.getChargeId(), attempt.getAttemptId(), "rolled-back-" + UUID.randomUUID());
            throw new IllegalStateException("Falha controlada após flush");
        })).isInstanceOf(IllegalStateException.class);
        Charge restored = gateway.findById(charge.getChargeId()).orElseThrow();
        assertThat(restored.getStatus()).isEqualTo(ChargeStatus.OPEN);
        assertThat(restored.getAttempts().getFirst().getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(restored.getAttempts().getFirst().getProviderPaymentId()).isNull();
        assertThat(version(charge.getChargeId())).isEqualTo(1);
    }

    @Test
    void shouldRollbackChargeCreationAndKeepBudgetUnchanged() {
        UUID orderId = UUID.randomUUID();
        UUID budgetId = insertBudget(orderId, "APPROVED", AMOUNT);
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
            Charge charge = gateway.createCharge(completed(orderId), budgetId, BRL);
            gateway.startAttempt(charge.getChargeId(), UUID.randomUUID());
            throw new IllegalStateException("Falha controlada após inserções");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(chargeCount(orderId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT approved_amount FROM budget WHERE budget_id = ?", BigDecimal.class, budgetId))
                .isEqualTo(AMOUNT);
        assertThat(gateway.createCharge(completed(orderId), budgetId, BRL)).isNotNull();
    }

    @Test
    void shouldReturnEmptyQueriesAndRejectWritesToMissingCharges() {
        UUID missing = UUID.randomUUID();
        assertThat(gateway.findById(missing)).isEmpty();
        assertThat(gateway.findByServiceOrderId(missing)).isEmpty();
        assertThatThrownBy(() -> gateway.startAttempt(missing, UUID.randomUUID()))
                .isInstanceOf(ChargeNotFoundException.class);
        assertThatThrownBy(() -> confirmPayment(missing, UUID.randomUUID(), "missing"))
                .isInstanceOf(ChargeNotFoundException.class);
    }

    @ParameterizedTest
    @CsvSource({"0,ck_charge_amount", "-1,ck_charge_amount", "NaN,ck_charge_amount", "Infinity,ck_charge_amount"})
    void shouldEnforcePositiveFiniteAmountsInPostgres(String amount, String constraint) {
        Charge charge = createCharge();
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE charge SET amount = ?::numeric WHERE charge_id = ?", amount, charge.getChargeId()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(constraint);
        assertThat(gateway.findById(charge.getChargeId()).orElseThrow().getAmount()).isEqualTo(AMOUNT);
    }

    @ParameterizedTest
    @CsvSource({"currency,brl,ck_charge_currency", "status,CANCELLED,ck_charge_status"})
    void shouldEnforceChargeCurrencyAndState(String column, String value, String constraint) {
        Charge charge = createCharge();
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE charge SET " + column + " = ? WHERE charge_id = ?", value, charge.getChargeId()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(constraint);
    }

    @Test
    void shouldEnforceChargeUniquenessAndLocalBudgetForeignKey() {
        Charge charge = createCharge();
        assertThatThrownBy(() -> insertChargeRow(charge.getServiceOrderId(), charge.getBudgetId()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_charge_service_order");
        assertThatThrownBy(() -> insertChargeRow(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_charge_budget");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM budget WHERE budget_id = ?", charge.getBudgetId()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_charge_budget");
    }

    @ParameterizedTest
    @EnumSource(value = PaymentAttemptStatus.class, names = {"PENDING", "UNKNOWN", "PAID"})
    void shouldRejectSecondBlockingAttemptEvenOutsideTheGateway(PaymentAttemptStatus status) {
        Charge charge = createCharge();
        String providerId = status == PaymentAttemptStatus.PAID ? "paid-" + UUID.randomUUID() : null;
        insertAttemptRow(charge.getChargeId(), UUID.randomUUID(), status.name(), providerId);
        assertThatThrownBy(() -> insertAttemptRow(charge.getChargeId(), UUID.randomUUID(), "PAID", "duplicate-" + UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_payment_attempt_blocking_charge");
    }

    @Test
    void shouldEnforceAttemptForeignKeyIdempotencyAndProviderUniqueness() {
        Charge charge = createCharge();
        UUID key = UUID.randomUUID();
        String providerId = "unique-" + UUID.randomUUID();
        insertAttemptRow(charge.getChargeId(), key, "FAILED", providerId);
        assertThatThrownBy(() -> insertAttemptRow(charge.getChargeId(), key, "FAILED", null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_payment_attempt_idempotency");
        assertThatThrownBy(() -> insertAttemptRow(charge.getChargeId(), UUID.randomUUID(), "FAILED", providerId))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_payment_attempt_provider_payment");
        assertThatThrownBy(() -> insertAttemptRow(UUID.randomUUID(), UUID.randomUUID(), "PENDING", null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_payment_attempt_charge");
    }

    @ParameterizedTest
    @CsvSource(value = {"PAID|NULL|ck_payment_attempt_paid", "CANCELLED|NULL|ck_payment_attempt_status",
            "PENDING|' '|ck_payment_attempt_provider_payment"}, delimiter = '|', nullValues = "NULL")
    void shouldRejectInvalidAttemptStateOrProviderReference(String status, String providerId, String constraint) {
        Charge charge = createCharge();
        assertThatThrownBy(() -> insertAttemptRow(charge.getChargeId(), UUID.randomUUID(), status, providerId))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(constraint);
    }

    @Test
    void shouldApplyV9AfterExistingV8WithoutChangingBudgetHistory() {
        String schema = "payment_migration_" + UUID.randomUUID().toString().replace("-", "");
        Flyway v8 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("8").load();
        v8.migrate();
        UUID budgetId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + schema + ".budget (budget_id, service_order_id, budget_version, quoted_amount, status, created_at) "
                + "VALUES (?, ?, 1, 123.4567, 'DRAFT', CURRENT_TIMESTAMP)", budgetId, UUID.randomUUID());
        List<java.util.Map<String, Object>> before = jdbcTemplate.queryForList("SELECT * FROM " + schema + ".budget");
        Flyway v9 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("9").load();
        assertThat(v9.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(v9.validateWithResult().validationSuccessful).isTrue();
        assertThat(v9.info().pending()).isEmpty();
        assertThat(jdbcTemplate.queryForList("SELECT * FROM " + schema + ".budget")).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + schema + ".charge", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + schema + ".payment_attempt", Integer.class)).isZero();
    }

    @Test
    void shouldRoundTripCheckoutOrderAndChosenMethodAsSeparateFinancialIdentities() {
        Charge charge = createCharge();
        UUID key = UUID.randomUUID();
        PaymentAttempt attempt = gateway.startAttempt(charge.getChargeId(), key);
        assertThat(attempt.getPaymentMethod()).isNull();
        String orderId = "order-" + UUID.randomUUID();
        String url = checkoutUrl(orderId);
        gateway.associateProviderOrder(charge.getChargeId(), attempt.getAttemptId(), orderId, url);
        gateway.recordPaymentMethod(charge.getChargeId(), attempt.getAttemptId(), PaymentMethod.CARD);

        Charge restored = gateway.findById(charge.getChargeId()).orElseThrow();
        PaymentAttempt persisted = restored.getAttempts().getFirst();
        assertThat(persisted.getProviderOrderId()).isEqualTo(orderId);
        assertThat(persisted.getCheckoutUrl()).isEqualTo(url);
        assertThat(persisted.getProviderPaymentId()).isNull();
        assertThat(persisted.getPaymentMethod()).isEqualTo(PaymentMethod.CARD);
        assertThat(persisted.getIdempotencyKey()).isEqualTo(key);
        assertThat(persisted.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(restored.getStatus()).isEqualTo(ChargeStatus.OPEN);
        assertThat(version(charge.getChargeId())).isEqualTo(3);

        gateway.associateProviderOrder(charge.getChargeId(), attempt.getAttemptId(), orderId, url);
        gateway.recordPaymentMethod(charge.getChargeId(), attempt.getAttemptId(), PaymentMethod.CARD);
        assertThat(gateway.startAttempt(charge.getChargeId(), key).getAttemptId()).isEqualTo(attempt.getAttemptId());
        assertThat(version(charge.getChargeId())).isEqualTo(3);
        assertThatThrownBy(() -> gateway.recordPaymentMethod(charge.getChargeId(), attempt.getAttemptId(), PaymentMethod.PIX))
                .isInstanceOf(InvalidPaymentStateException.class);
        assertThat(version(charge.getChargeId())).isEqualTo(3);
    }

    @Test
    void shouldDeduplicateConcurrentAssociationOfTheSameOrder() throws Exception {
        Charge charge = createCharge();
        PaymentAttempt attempt = gateway.startAttempt(charge.getChargeId(), UUID.randomUUID());
        String orderId = "order-" + UUID.randomUUID();
        List<Outcome<Charge>> outcomes = concurrently(
                () -> gateway.associateProviderOrder(charge.getChargeId(), attempt.getAttemptId(), orderId, checkoutUrl(orderId)),
                () -> gateway.associateProviderOrder(charge.getChargeId(), attempt.getAttemptId(), orderId, checkoutUrl(orderId)));
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.failure()).isNull());
        assertThat(version(charge.getChargeId())).isEqualTo(2);
        assertThat(gateway.findById(charge.getChargeId()).orElseThrow().getAttempts()).hasSize(1);
    }

    @Test
    void shouldRejectDivergentConcurrentOrderAssociationsWithoutMixingUrlAndIdentity() throws Exception {
        Charge charge = createCharge();
        PaymentAttempt attempt = gateway.startAttempt(charge.getChargeId(), UUID.randomUUID());
        String firstOrder = "order-" + UUID.randomUUID();
        String secondOrder = "order-" + UUID.randomUUID();
        List<Outcome<Charge>> outcomes = concurrently(
                () -> gateway.associateProviderOrder(charge.getChargeId(), attempt.getAttemptId(), firstOrder, checkoutUrl(firstOrder)),
                () -> gateway.associateProviderOrder(charge.getChargeId(), attempt.getAttemptId(), secondOrder, checkoutUrl(secondOrder)));
        assertThat(outcomes.stream().filter(outcome -> outcome.failure() == null)).hasSize(1);
        assertThat(outcomes.stream().map(Outcome::failure).filter(failure -> failure != null)).singleElement()
                .isInstanceOf(InvalidPaymentStateException.class);
        PaymentAttempt persisted = gateway.findById(charge.getChargeId()).orElseThrow().getAttempts().getFirst();
        assertThat(persisted.getProviderOrderId()).isIn(firstOrder, secondOrder);
        assertThat(persisted.getCheckoutUrl()).isEqualTo(checkoutUrl(persisted.getProviderOrderId()));
        assertThat(version(charge.getChargeId())).isEqualTo(2);
    }

    @Test
    void shouldEnforceGlobalOrderUniquenessAndRollbackTheLosingCharge() throws Exception {
        Charge first = createCharge();
        Charge second = createCharge();
        PaymentAttempt firstAttempt = gateway.startAttempt(first.getChargeId(), UUID.randomUUID());
        PaymentAttempt secondAttempt = gateway.startAttempt(second.getChargeId(), UUID.randomUUID());
        String orderId = "shared-order-" + UUID.randomUUID();
        List<Outcome<Charge>> outcomes = concurrently(
                () -> gateway.associateProviderOrder(first.getChargeId(), firstAttempt.getAttemptId(), orderId, checkoutUrl(orderId)),
                () -> gateway.associateProviderOrder(second.getChargeId(), secondAttempt.getAttemptId(), orderId, checkoutUrl(orderId)));
        assertThat(outcomes.stream().filter(outcome -> outcome.failure() == null)).hasSize(1);
        assertThat(outcomes.stream().map(Outcome::failure).filter(failure -> failure != null)).singleElement()
                .isInstanceOf(PaymentPersistenceConflictException.class)
                .satisfies(failure -> assertThat(failure.getCause()).isInstanceOf(DataIntegrityViolationException.class));
        UUID losingId = outcomes.getFirst().failure() != null ? first.getChargeId() : second.getChargeId();
        PaymentAttempt losing = gateway.findById(losingId).orElseThrow().getAttempts().getFirst();
        assertThat(losing.getProviderOrderId()).isNull();
        assertThat(losing.getCheckoutUrl()).isNull();
        assertThat(losing.getUpdatedAt()).isEqualTo(losing.getCreatedAt());
        assertThat(version(losingId)).isEqualTo(1);
    }

    @Test
    void shouldRejectDivergentUrlAndWrongChargeWithoutModifyingStoredOrder() {
        Charge charge = createCharge();
        PaymentAttempt attempt = gateway.startAttempt(charge.getChargeId(), UUID.randomUUID());
        String orderId = "order-" + UUID.randomUUID();
        gateway.associateProviderOrder(charge.getChargeId(), attempt.getAttemptId(), orderId, checkoutUrl(orderId));
        assertThatThrownBy(() -> gateway.associateProviderOrder(charge.getChargeId(), attempt.getAttemptId(), orderId, checkoutUrl(orderId) + "&different=true"))
                .isInstanceOf(InvalidPaymentStateException.class);
        Charge other = createCharge();
        assertThatThrownBy(() -> gateway.associateProviderOrder(other.getChargeId(), attempt.getAttemptId(), orderId, checkoutUrl(orderId)))
                .isInstanceOf(InvalidPaymentDataException.class);
        assertThat(gateway.findById(charge.getChargeId()).orElseThrow().getAttempts().getFirst().getCheckoutUrl())
                .isEqualTo(checkoutUrl(orderId));
        assertThat(version(charge.getChargeId())).isEqualTo(2);
        assertThat(version(other.getChargeId())).isZero();
    }

    @Test
    void shouldRecoverUnknownOrderCreationAndRollbackCallerFailure() {
        Charge charge = createCharge();
        UUID key = UUID.randomUUID();
        PaymentAttempt attempt = gateway.startAttempt(charge.getChargeId(), key);
        gateway.markAttemptUnknown(charge.getChargeId(), attempt.getAttemptId());
        String orderId = "recovered-" + UUID.randomUUID();
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
            gateway.associateProviderOrder(charge.getChargeId(), attempt.getAttemptId(), orderId, checkoutUrl(orderId));
            throw new IllegalStateException("Falha controlada depois do flush");
        })).isInstanceOf(IllegalStateException.class);
        PaymentAttempt unknown = gateway.startAttempt(charge.getChargeId(), key);
        assertThat(unknown.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(unknown.getProviderOrderId()).isNull();
        assertThat(version(charge.getChargeId())).isEqualTo(2);

        gateway.associateProviderOrder(charge.getChargeId(), attempt.getAttemptId(), orderId, checkoutUrl(orderId));
        PaymentAttempt recovered = gateway.startAttempt(charge.getChargeId(), key);
        assertThat(recovered.getAttemptId()).isEqualTo(attempt.getAttemptId());
        assertThat(recovered.getProviderOrderId()).isEqualTo(orderId);
        assertThat(recovered.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(recovered.getProviderPaymentId()).isNull();
        assertThatThrownBy(() -> gateway.startAttempt(charge.getChargeId(), UUID.randomUUID()))
                .isInstanceOf(InvalidPaymentStateException.class);
        assertThat(version(charge.getChargeId())).isEqualTo(3);
    }

    @ParameterizedTest
    @CsvSource(value = {"' '|NULL|ck_payment_attempt_provider_order", "' order'|NULL|ck_payment_attempt_provider_order",
            "NULL|https://checkout.test/pay|ck_payment_attempt_checkout_url", "order|http://checkout.test/pay|ck_payment_attempt_checkout_url",
            "order|https://user@checkout.test/pay|ck_payment_attempt_checkout_url", "order|https://checkout.test/pay#fragment|ck_payment_attempt_checkout_url"},
            delimiter = '|', nullValues = "NULL")
    void shouldEnforceOrderAndCheckoutUrlConstraints(String orderId, String url, String constraint) {
        Charge charge = createCharge();
        PaymentAttempt attempt = gateway.startAttempt(charge.getChargeId(), UUID.randomUUID());
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE payment_attempt SET provider_order_id = ?, checkout_url = ? WHERE attempt_id = ?",
                orderId, url, attempt.getAttemptId())).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(constraint);
        PaymentAttempt persisted = gateway.findById(charge.getChargeId()).orElseThrow().getAttempts().getFirst();
        assertThat(persisted.getProviderOrderId()).isNull();
        assertThat(persisted.getCheckoutUrl()).isNull();
    }

    @Test
    void shouldKeepPaidConstraintIndependentFromOrderCreationAndValidateMethodWhenPresent() {
        Charge charge = createCharge();
        PaymentAttempt attempt = gateway.startAttempt(charge.getChargeId(), UUID.randomUUID());
        String orderId = "order-" + UUID.randomUUID();
        gateway.associateProviderOrder(charge.getChargeId(), attempt.getAttemptId(), orderId, checkoutUrl(orderId));
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE payment_attempt SET status = 'PAID' WHERE attempt_id = ?", attempt.getAttemptId()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_payment_attempt_paid");
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE payment_attempt SET payment_method = 'CASH' WHERE attempt_id = ?", attempt.getAttemptId()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_payment_attempt_method");
        assertThat(gateway.findById(charge.getChargeId()).orElseThrow().getStatus()).isEqualTo(ChargeStatus.OPEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.001", "1.2301", "530.1234"})
    void shouldRejectIncompatibleApprovedAmountBeforeCreatingChargeWithoutChangingBudget(String decimal) {
        UUID orderId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal(decimal);
        UUID budgetId = insertBudget(orderId, "APPROVED", amount);
        assertThatThrownBy(() -> gateway.createCharge(completed(orderId), budgetId, BRL))
                .isInstanceOf(InvalidPaymentDataException.class);
        assertThat(chargeCount(orderId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT approved_amount FROM budget WHERE budget_id = ?", BigDecimal.class, budgetId))
                .isEqualTo(amount);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0", "10.00", "10.000", "1.23000", "0.0100"})
    void shouldCreateChargeFromApprovedBudgetWithExactCentPrecisionWithoutRewritingBudget(String value) {
        UUID orderId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal(value);
        var draft = budgets.createDraft(orderId, amount, new BudgetComposition(List.of(), 1));
        var approved = budgets.registerApproval(draft.getBudgetId(), "validated-employee");
        var before = jdbcTemplate.queryForMap("SELECT * FROM budget WHERE budget_id=?", approved.getBudgetId());

        Charge charge = gateway.createCharge(completed(orderId), approved.getBudgetId(), BRL);
        assertThat(charge.getAmount()).isEqualTo(amount).isEqualTo(approved.getApprovedAmount());
        assertThat(gateway.findById(charge.getChargeId()).orElseThrow().getAmount()).isEqualTo(amount);
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM budget WHERE budget_id=?", approved.getBudgetId())).isEqualTo(before);
    }

    @Test
    void shouldRejectNonBrlForANewChargeAndPreserveHistoricalMoneyOnReplay() {
        UUID orderId = UUID.randomUUID();
        UUID budgetId = insertBudget(orderId, "APPROVED", AMOUNT);
        assertThatThrownBy(() -> gateway.createCharge(completed(orderId), budgetId, Currency.getInstance("USD")))
                .isInstanceOf(InvalidPaymentDataException.class);
        assertThat(chargeCount(orderId)).isZero();

        Charge existing = gateway.createCharge(completed(orderId), budgetId, BRL);
        PaymentAttempt failed = gateway.startAttempt(existing.getChargeId(), UUID.randomUUID());
        gateway.confirmAttemptFailure(existing.getChargeId(), failed.getAttemptId());
        jdbcTemplate.update("UPDATE charge SET amount = 530.123400, currency = 'USD' WHERE charge_id = ?", existing.getChargeId());
        jdbcTemplate.update("UPDATE budget SET approved_amount = 530.123400 WHERE budget_id = ?", budgetId);
        Charge replay = gateway.createCharge(completed(orderId), budgetId, Currency.getInstance("USD"));
        assertThat(replay.getChargeId()).isEqualTo(existing.getChargeId());
        assertThat(replay.getAmount()).isEqualTo(new BigDecimal("530.123400"));
        assertThat(gateway.startAttempt(existing.getChargeId(), failed.getIdempotencyKey()).getAttemptId()).isEqualTo(failed.getAttemptId());
        assertThatThrownBy(() -> gateway.startAttempt(existing.getChargeId(), UUID.randomUUID())).isInstanceOf(InvalidPaymentDataException.class);
        assertThat(version(existing.getChargeId())).isEqualTo(2);
    }

    @Test
    void shouldMigrateV9AttemptsWithoutRewritingLegacyMethodsMoneyOrFinancialConstraints() {
        String schema = "checkout_migration_" + UUID.randomUUID().toString().replace("-", "");
        Flyway v9 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("9").load();
        v9.migrate();
        UUID budgetId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID chargeId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + schema + ".budget (budget_id, service_order_id, budget_version, quoted_amount, approved_amount, status, created_at) "
                + "VALUES (?, ?, 1, 530.123400, 530.123400, 'APPROVED', CURRENT_TIMESTAMP)", budgetId, orderId);
        jdbcTemplate.update("INSERT INTO " + schema + ".charge (charge_id, service_order_id, budget_id, amount, currency, status, row_version, created_at, updated_at) "
                + "VALUES (?, ?, ?, 530.123400, 'USD', 'OPEN', 7, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", chargeId, orderId, budgetId);
        for (PaymentMethod method : PaymentMethod.values()) {
            jdbcTemplate.update("INSERT INTO " + schema + ".payment_attempt (attempt_id, charge_id, payment_method, status, idempotency_key, provider_payment_id, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", UUID.randomUUID(), chargeId, method.name(),
                    method == PaymentMethod.PIX ? "FAILED" : "PENDING", UUID.randomUUID(), "legacy-" + method.name());
        }
        String legacyAttemptColumns = "attempt_id, charge_id, payment_method, status, idempotency_key, provider_payment_id, created_at, updated_at";
        List<java.util.Map<String, Object>> beforeAttempts = jdbcTemplate.queryForList("SELECT " + legacyAttemptColumns + " FROM " + schema + ".payment_attempt ORDER BY attempt_id");
        List<java.util.Map<String, Object>> beforeCharges = jdbcTemplate.queryForList("SELECT * FROM " + schema + ".charge");
        List<java.util.Map<String, Object>> beforeBudgets = jdbcTemplate.queryForList("SELECT * FROM " + schema + ".budget");
        Flyway v10 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("10").load();
        assertThat(v10.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(v10.validateWithResult().validationSuccessful).isTrue();
        assertThat(v10.info().pending()).isEmpty();
        assertThat(jdbcTemplate.queryForList("SELECT " + legacyAttemptColumns + " FROM " + schema + ".payment_attempt ORDER BY attempt_id")).isEqualTo(beforeAttempts);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM " + schema + ".charge")).isEqualTo(beforeCharges);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM " + schema + ".budget")).isEqualTo(beforeBudgets);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + schema + ".payment_attempt WHERE provider_order_id IS NULL AND checkout_url IS NULL", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = ? AND tablename = 'payment_attempt'", String.class, schema))
                .contains("uk_payment_attempt_provider_order", "uk_payment_attempt_provider_payment", "uk_payment_attempt_idempotency", "uk_payment_attempt_blocking_charge");
        assertThat(jdbcTemplate.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema = ? AND table_name = 'payment_attempt' AND column_name = 'payment_method'", String.class, schema))
                .isEqualTo("YES");
    }

    private static String checkoutUrl(String orderId) {
        return "https://checkout.example.test/pay?order_id=" + orderId;
    }

    private Charge createCharge() {
        UUID orderId = UUID.randomUUID();
        return gateway.createCharge(completed(orderId), insertBudget(orderId, "APPROVED", AMOUNT), BRL);
    }

    private UUID insertBudget(UUID orderId, String status, BigDecimal amount) {
        UUID budgetId = UUID.randomUUID();
        int version = jdbcTemplate.queryForObject("SELECT coalesce(max(budget_version), 0) + 1 FROM budget WHERE service_order_id = ?", Integer.class, orderId);
        jdbcTemplate.update("""
                INSERT INTO budget (budget_id, service_order_id, budget_version, quoted_amount, approved_amount, status, created_at)
                VALUES (?, ?, ?, 530.12, ?, ?, CURRENT_TIMESTAMP)
                """, budgetId, orderId, version, amount, status);
        return budgetId;
    }

    private void insertChargeRow(UUID orderId, UUID budgetId) {
        jdbcTemplate.update("""
                INSERT INTO charge (charge_id, service_order_id, budget_id, amount, currency, status, created_at, updated_at)
                VALUES (?, ?, ?, 10, 'BRL', 'OPEN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), orderId, budgetId);
    }

    private void insertAttemptRow(UUID chargeId, UUID key, String status, String providerId) {
        jdbcTemplate.update("""
                INSERT INTO payment_attempt (attempt_id, charge_id, payment_method, status, idempotency_key, provider_payment_id, created_at, updated_at)
                VALUES (?, ?, 'PIX', ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), chargeId, status, key, providerId);
    }

    private int chargeCount(UUID orderId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM charge WHERE service_order_id = ?", Integer.class, orderId);
    }

    private long version(UUID chargeId) {
        return jdbcTemplate.queryForObject("SELECT row_version FROM charge WHERE charge_id = ?", Long.class, chargeId);
    }

    private TransactionTemplate transaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }

    private static ServiceOrderPaymentContext completed(UUID orderId) {
        return new ServiceOrderPaymentContext(orderId, "COMPLETED");
    }

    private static <T> List<Outcome<T>> concurrently(Callable<T> first, Callable<T> second) throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<Outcome<T>> firstResult = workers.submit(() -> execute(start, first));
            Future<Outcome<T>> secondResult = workers.submit(() -> execute(start, second));
            return List.of(firstResult.get(20, TimeUnit.SECONDS), secondResult.get(20, TimeUnit.SECONDS));
        }
    }

    private static <T> Outcome<T> execute(CyclicBarrier start, Callable<T> operation) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try {
            return new Outcome<>(operation.call(), null);
        } catch (RuntimeException exception) {
            return new Outcome<>(null, exception);
        }
    }

    private void awaitDatabaseLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE pid <> pg_backend_pid() AND wait_event_type = 'Lock' AND query LIKE '%charge%'
                    """, Integer.class);
            if (waiting != null && waiting > 0) { return; }
            Thread.sleep(20);
        }
        throw new AssertionError("Segunda transação não aguardou lock PostgreSQL da cobrança");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new AssertionError("Tempo limite na sincronização transacional");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private record Outcome<T>(T value, RuntimeException failure) {
    }
}
