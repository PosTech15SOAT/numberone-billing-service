package br.com.fiap.numberone.billing.payment.infrastructure.persistence.gateways;

import br.com.fiap.numberone.billing.payment.application.exceptions.ChargeNotFoundException;
import br.com.fiap.numberone.billing.payment.application.exceptions.ApprovedBudgetUnavailableException;
import br.com.fiap.numberone.billing.payment.application.exceptions.PaymentPersistenceConflictException;
import br.com.fiap.numberone.billing.payment.application.gateways.ChargeGateway;
import br.com.fiap.numberone.billing.payment.application.results.PreparedCheckout;
import br.com.fiap.numberone.billing.payment.application.results.CheckoutResult;
import br.com.fiap.numberone.billing.payment.application.results.OrderObservation;
import br.com.fiap.numberone.billing.payment.application.services.OrderPaymentEvidence;
import br.com.fiap.numberone.billing.budget.infrastructure.outbox.IntegrationEventOutboxRepository;
import br.com.fiap.numberone.billing.budget.infrastructure.persistence.repositories.BudgetRevisionCounterRepository;
import br.com.fiap.numberone.billing.infrastructure.outbox.IntegrationEventOutboxEntry;
import br.com.fiap.numberone.billing.payment.infrastructure.outbox.PaymentIntegrationEventFactory;
import br.com.fiap.numberone.billing.payment.domain.enums.ChargeStatus;
import br.com.fiap.numberone.billing.payment.domain.entities.Charge;
import br.com.fiap.numberone.billing.payment.domain.entities.PaymentAttempt;
import br.com.fiap.numberone.billing.payment.domain.enums.PaymentMethod;
import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentDataException;
import br.com.fiap.numberone.billing.payment.domain.exceptions.PaymentIdempotencyConflictException;
import br.com.fiap.numberone.billing.payment.domain.references.ServiceOrderPaymentContext;
import br.com.fiap.numberone.billing.payment.infrastructure.persistence.entities.ChargeEntity;
import br.com.fiap.numberone.billing.payment.infrastructure.persistence.entities.PaymentAttemptEntity;
import br.com.fiap.numberone.billing.payment.infrastructure.persistence.mappers.ChargePersistenceMapper;
import br.com.fiap.numberone.billing.payment.infrastructure.persistence.repositories.ChargeRepository;
import br.com.fiap.numberone.billing.payment.infrastructure.persistence.repositories.PaymentAttemptRepository;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import br.com.fiap.numberone.billing.infrastructure.observability.ObservationLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

@Component
@Transactional
public class ChargeGatewayImpl implements ChargeGateway {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChargeGatewayImpl.class);
    private final ChargeRepository chargeRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final ChargePersistenceMapper mapper;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;
    private final TransactionTemplate checkoutPreparation;
    private final IntegrationEventOutboxRepository outbox;
    private final PaymentIntegrationEventFactory paymentEvents;
    private final BudgetRevisionCounterRepository contexts;

    public ChargeGatewayImpl(ChargeRepository chargeRepository, PaymentAttemptRepository attemptRepository,
                             ChargePersistenceMapper mapper, JdbcTemplate jdbcTemplate, EntityManager entityManager,
                             PlatformTransactionManager transactionManager, IntegrationEventOutboxRepository outbox,
                             PaymentIntegrationEventFactory paymentEvents, BudgetRevisionCounterRepository contexts) {
        this.chargeRepository = chargeRepository;
        this.attemptRepository = attemptRepository;
        this.mapper = mapper;
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
        this.checkoutPreparation = new TransactionTemplate(transactionManager);
        this.outbox = outbox;
        this.paymentEvents = paymentEvents;
        this.contexts = contexts;
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public Optional<CheckoutResult> inspectCheckout(UUID serviceOrderId, UUID idempotencyKey) {
        var result = checkoutPreparation.execute(transaction -> {
            if (serviceOrderId == null || idempotencyKey == null) throw new InvalidPaymentDataException("Identidades do checkout são obrigatórias");
            requireIdempotencyOwner(serviceOrderId, idempotencyKey);
            ApprovedBudget approved = approvedBudget(serviceOrderId);
            return findByServiceOrderId(serviceOrderId, false).flatMap(charge -> {
                requireEquivalentCharge(charge, approved.budgetId(), approved.amount(), Currency.getInstance("BRL"));
                return charge.getAttempts().stream().filter(attempt -> attempt.getIdempotencyKey().equals(idempotencyKey))
                        .findFirst().map(attempt -> CheckoutResult.from(charge, attempt.getAttemptId()));
            });
        });
        if (result != null) result.ifPresent(replay -> ObservationLog.safely(() -> LOGGER.atInfo()
                .addKeyValue("event.code", "CHECKOUT_REUSED").addKeyValue("service_order.id", serviceOrderId)
                .addKeyValue("charge.id", replay.chargeId()).addKeyValue("payment_attempt.id", replay.attemptId())
                .addKeyValue("provider_order.id", replay.providerOrderId()).addKeyValue("payment_attempt.status", replay.status())
                .log("Resultado local reutilizado sem chamada externa")));
        return result;
    }

    private void requireIdempotencyOwner(UUID serviceOrderId, UUID idempotencyKey) {
        List<UUID> previousOrders = jdbcTemplate.query("""
                SELECT c.service_order_id FROM payment_attempt a JOIN charge c ON c.charge_id = a.charge_id
                WHERE a.idempotency_key = ?
                """, (result, row) -> result.getObject("service_order_id", UUID.class), idempotencyKey);
        if (!previousOrders.isEmpty() && !previousOrders.getFirst().equals(serviceOrderId)) throw new PaymentIdempotencyConflictException();
    }

    private ApprovedBudget approvedBudget(UUID serviceOrderId) {
        List<ApprovedBudget> budgets = jdbcTemplate.query("""
                SELECT budget_id, approved_amount FROM budget WHERE service_order_id = ? AND status = 'APPROVED' FOR SHARE
                """, (result, row) -> new ApprovedBudget(result.getObject("budget_id", UUID.class), result.getBigDecimal("approved_amount")), serviceOrderId);
        if (budgets.size() != 1) throw new ApprovedBudgetUnavailableException();
        ObservationLog.safely(() -> LOGGER.atDebug().addKeyValue("event.code", "PAYMENT_APPROVED_BUDGET_VALIDATED")
                .addKeyValue("service_order.id", serviceOrderId).addKeyValue("budget.id", budgets.getFirst().budgetId())
                .log("Referência comercial aprovada validada"));
        return budgets.getFirst();
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public PreparedCheckout prepareCheckout(ServiceOrderPaymentContext serviceOrder, UUID idempotencyKey) {
        // NEVER é aplicado pelo proxy antes de entrar aqui. O template retorna somente após o commit real.
        return checkoutPreparation.execute(transaction -> prepareReservedCheckout(serviceOrder, idempotencyKey));
    }

    private PreparedCheckout prepareReservedCheckout(ServiceOrderPaymentContext serviceOrder, UUID idempotencyKey) {
        if (serviceOrder == null || idempotencyKey == null) {
            throw new InvalidPaymentDataException("Contexto da OS e chave de idempotência são obrigatórios");
        }
        serviceOrder.requireCompleted();
        requireOpenContext(serviceOrder.serviceOrderId());
        requireIdempotencyOwner(serviceOrder.serviceOrderId(), idempotencyKey);
        ApprovedBudget approved = approvedBudget(serviceOrder.serviceOrderId());
        // Revalidar sob FOR UPDATE: a inspeção anterior não autoriza reservar nem criar externamente.
        Charge charge = createCharge(serviceOrder, approved.budgetId(), Currency.getInstance("BRL"), true);
        LoadedCharge loaded = requireForUpdate(charge.getChargeId());
        return reserveCheckout(loaded, idempotencyKey);
    }

    private PreparedCheckout reserveCheckout(LoadedCharge loaded, UUID idempotencyKey) {
        boolean attemptAlreadyExists = loaded.charge().getAttempts().stream()
                .anyMatch(attempt -> attempt.getIdempotencyKey().equals(idempotencyKey));
        PaymentAttempt attempt = loaded.charge().startAttempt(idempotencyKey);
        // PENDING legado sem reserva não comprova ausência de chamada anterior. Nunca o assumir.
        boolean reservationAcquired = !attemptAlreadyExists && loaded.charge().claimCheckoutCreation(attempt.getAttemptId());
        persistState(loaded);
        if (reservationAcquired) {
            logAfterCommit(loaded.charge(), attempt.getAttemptId(), "CHECKOUT_CREATION_RESERVED",
                    "Reserva de criação de checkout confirmada", false);
        }
        return new PreparedCheckout(loaded.charge(), attempt.getAttemptId(), reservationAcquired);
    }

    @Override
    public Charge createCharge(ServiceOrderPaymentContext serviceOrder, UUID budgetId, Currency currency) {
        if (serviceOrder == null) throw new InvalidPaymentDataException("Contexto da OS é obrigatório");
        requireOpenContext(serviceOrder.serviceOrderId());
        return createCharge(serviceOrder, budgetId, currency, false);
    }

    private Charge createCharge(ServiceOrderPaymentContext serviceOrder, UUID budgetId, Currency currency, boolean forUpdate) {
        if (serviceOrder == null || budgetId == null) {
            throw new InvalidPaymentDataException("Contexto da OS e orçamento são obrigatórios");
        }
        serviceOrder.requireCompleted();
        List<BigDecimal> approvedAmounts = jdbcTemplate.query("""
                SELECT approved_amount FROM budget
                WHERE budget_id = ? AND service_order_id = ? AND status = 'APPROVED'
                FOR SHARE
                """, (result, row) -> result.getBigDecimal("approved_amount"), budgetId, serviceOrder.serviceOrderId());
        if (approvedAmounts.isEmpty()) {
            throw new InvalidPaymentDataException("Cobrança exige orçamento aprovado da mesma OS");
        }
        Optional<Charge> previous = findByServiceOrderId(serviceOrder.serviceOrderId(), forUpdate);
        if (previous.isPresent()) {
            return requireEquivalentCharge(previous.get(), budgetId, approvedAmounts.getFirst(), currency);
        }
        Charge proposed = Charge.create(serviceOrder, budgetId, approvedAmounts.getFirst(), currency);
        jdbcTemplate.update("""
                INSERT INTO charge (charge_id, service_order_id, budget_id, amount, currency, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?)
                ON CONFLICT (service_order_id) DO NOTHING
                """, proposed.getChargeId(), proposed.getServiceOrderId(), proposed.getBudgetId(), proposed.getAmount(),
                proposed.getCurrency().getCurrencyCode(), Timestamp.from(proposed.getCreatedAt()), Timestamp.from(proposed.getUpdatedAt()));
        Charge existing = findByServiceOrderId(serviceOrder.serviceOrderId(), forUpdate).orElseThrow(ChargeNotFoundException::new);
        return requireEquivalentCharge(existing, budgetId, proposed.getAmount(), currency);
    }

    private void requireOpenContext(UUID serviceOrderId) {
        if (contexts.lockContext(serviceOrderId)) throw new ApprovedBudgetUnavailableException();
    }

    private Charge requireEquivalentCharge(Charge existing, UUID budgetId, BigDecimal approvedAmount, Currency currency) {
        if (approvedAmount == null || !existing.getBudgetId().equals(budgetId)
                || existing.getAmount().compareTo(approvedAmount) != 0 || !existing.getCurrency().equals(currency)) {
            throw new PaymentIdempotencyConflictException();
        }
        return existing;
    }

    @Override
    public Optional<Charge> findById(UUID chargeId) {
        // O lock compartilhado mantém as duas leituras do agregado coerentes inclusive em READ COMMITTED.
        return load(chargeId, false).map(LoadedCharge::charge);
    }

    @Override
    public Optional<Charge> findByServiceOrderId(UUID serviceOrderId) {
        return findByServiceOrderId(serviceOrderId, false);
    }

    private Optional<Charge> findByServiceOrderId(UUID serviceOrderId, boolean forUpdate) {
        List<UUID> chargeIds = jdbcTemplate.query("SELECT charge_id FROM charge WHERE service_order_id = ?",
                (result, row) -> result.getObject("charge_id", UUID.class), serviceOrderId);
        return chargeIds.isEmpty() ? Optional.empty() : load(chargeIds.getFirst(), forUpdate).map(LoadedCharge::charge);
    }

    @Override
    public PaymentAttempt startAttempt(UUID chargeId, UUID idempotencyKey) {
        LoadedCharge loaded = requireForUpdate(chargeId);
        PaymentAttempt attempt = loaded.charge().startAttempt(idempotencyKey);
        persistState(loaded);
        return attempt;
    }

    @Override
    public Optional<Charge> findByProviderOrderId(String providerOrderId) {
        List<UUID> chargeIds = jdbcTemplate.query("SELECT charge_id FROM payment_attempt WHERE provider_order_id = ?",
                (result, row) -> result.getObject("charge_id", UUID.class), providerOrderId);
        return chargeIds.isEmpty() ? Optional.empty() : load(chargeIds.getFirst(), false).map(LoadedCharge::charge);
    }

    @Override
    public Charge associateProviderOrder(UUID chargeId, UUID attemptId, String providerOrderId, String checkoutUrl) {
        LoadedCharge loaded = requireForUpdate(chargeId);
        boolean knownUrl = loaded.charge().getAttempts().stream().anyMatch(attempt ->
                attempt.getAttemptId().equals(attemptId) && attempt.getCheckoutUrl() != null);
        loaded.charge().associateProviderOrder(attemptId, providerOrderId, checkoutUrl);
        persistState(loaded);
        if (!knownUrl && checkoutUrl != null) {
            logAfterCommit(loaded.charge(), attemptId, "CHECKOUT_CREATED", "Checkout persistido", false);
        }
        return loaded.charge();
    }

    @Override
    public Charge recordPaymentMethod(UUID chargeId, UUID attemptId, PaymentMethod paymentMethod) {
        return applyTransition(chargeId, charge -> charge.recordPaymentMethod(attemptId, paymentMethod));
    }

    @Override
    public Charge markAttemptUnknown(UUID chargeId, UUID attemptId) {
        LoadedCharge loaded = requireForUpdate(chargeId);
        Instant previousUpdate = loaded.charge().getUpdatedAt();
        loaded.charge().markAttemptUnknown(attemptId);
        persistState(loaded);
        if (!previousUpdate.equals(loaded.charge().getUpdatedAt())) {
            logAfterCommit(loaded.charge(), attemptId, "CHECKOUT_OUTCOME_UNKNOWN",
                    "Resultado de checkout desconhecido; reconciliação pendente", true);
        }
        return loaded.charge();
    }

    @Override
    public Charge recordPendingAttempt(UUID chargeId, UUID attemptId, String providerPaymentId) {
        return applyTransition(chargeId, charge -> charge.recordPendingAttempt(attemptId, providerPaymentId));
    }

    @Override
    public Charge confirmAttemptFailure(UUID chargeId, UUID attemptId) {
        return applyTransition(chargeId, charge -> charge.confirmAttemptFailure(attemptId));
    }

    @Override
    public Charge confirmAttemptExpiration(UUID chargeId, UUID attemptId) {
        return applyTransition(chargeId, charge -> charge.confirmAttemptExpiration(attemptId));
    }

    @Override
    public Charge confirmPayment(OrderObservation observation) {
        if (observation == null || observation.chargeId() == null) throw new InvalidPaymentDataException("Observação financeira é obrigatória");
        LoadedCharge loaded = requireForUpdate(observation.chargeId());
        String providerPaymentId = OrderPaymentEvidence.requireApproved(loaded.charge(), observation);
        if (loaded.charge().getStatus() == ChargeStatus.REFUNDED) return loaded.charge();
        boolean alreadyPaid = loaded.charge().getStatus() == ChargeStatus.PAID;
        loaded.charge().confirmPayment(observation.attemptId(), providerPaymentId);
        if (!alreadyPaid) {
            persistState(loaded);
            IntegrationEventOutboxEntry event = paymentEvents.create(loaded.charge());
            outbox.append(event);
            logPaymentConfirmationAfterCommit(loaded.charge(), observation, providerPaymentId, event.eventId());
        }
        // TODO: SAGA - reconciliar PAID histórico sem EV-03; não gerar evento retroativo automaticamente (TD-007).
        return loaded.charge();
    }

    @Override
    public void recordFullRefund(OrderObservation observation) {
        LoadedCharge loaded = requireForUpdate(observation.chargeId());
        String providerPaymentId = OrderPaymentEvidence.requireFullRefund(loaded.charge(), observation);
        if (loaded.charge().recordFullRefund(observation.attemptId(), providerPaymentId)) {
            persistState(loaded);
            outbox.append(paymentEvents.refunded(loaded.charge()));
        }
    }

    private void logPaymentConfirmationAfterCommit(Charge charge, OrderObservation observation,
                                                   String providerPaymentId, UUID eventId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                ObservationLog.safely(() -> LOGGER.atInfo().addKeyValue("event.code", "PAYMENT_CONFIRMED")
                        .addKeyValue("service_order.id", charge.getServiceOrderId())
                        .addKeyValue("charge.id", charge.getChargeId())
                        .addKeyValue("payment_attempt.id", observation.attemptId()).addKeyValue("event.id", eventId)
                        .addKeyValue("provider_order.id", observation.providerOrderId())
                        .addKeyValue("provider_payment.id", providerPaymentId)
                        .addKeyValue("charge.previous_status", "OPEN").addKeyValue("charge.status", "PAID")
                        .addKeyValue("outbox.persisted", true)
                        .log("Quitação confirmada e evento persistido na Outbox"));
            }
        });
    }

    private Charge applyTransition(UUID chargeId, Consumer<Charge> transition) {
        LoadedCharge loaded = requireForUpdate(chargeId);
        transition.accept(loaded.charge());
        persistState(loaded);
        return loaded.charge();
    }

    private LoadedCharge requireForUpdate(UUID chargeId) {
        return load(chargeId, true).orElseThrow(ChargeNotFoundException::new);
    }

    private Optional<LoadedCharge> load(UUID chargeId, boolean forUpdate) {
        String lockQuery = forUpdate
                ? "SELECT charge_id FROM charge WHERE charge_id = ? FOR UPDATE"
                : "SELECT charge_id FROM charge WHERE charge_id = ? FOR SHARE";
        List<UUID> chargeIds = jdbcTemplate.query(lockQuery,
                (result, row) -> result.getObject("charge_id", UUID.class), chargeId);
        if (chargeIds.isEmpty()) {
            return Optional.empty();
        }
        ChargeEntity entity = chargeRepository.findById(chargeId).orElseThrow(ChargeNotFoundException::new);
        entityManager.refresh(entity);
        List<PaymentAttemptEntity> attempts = attemptRepository.findAllByChargeIdOrderByCreatedAtAscAttemptIdAsc(chargeId);
        attempts.forEach(entityManager::refresh);
        return Optional.of(new LoadedCharge(mapper.toDomain(entity, attempts), entity, attempts));
    }

    private void persistState(LoadedCharge loaded) {
        mapper.updateState(loaded.charge(), loaded.entity());
        for (PaymentAttempt attempt : loaded.charge().getAttempts()) {
            persistAttempt(attempt, loaded.attempts());
        }
        try {
            chargeRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw new PaymentPersistenceConflictException(exception);
        }
    }

    private void persistAttempt(PaymentAttempt attempt, List<PaymentAttemptEntity> persistedAttempts) {
        Optional<PaymentAttemptEntity> persisted = persistedAttempts.stream()
                .filter(entity -> entity.getAttemptId().equals(attempt.getAttemptId())).findFirst();
        if (persisted.isPresent()) {
            mapper.updateState(attempt, persisted.get());
        } else {
            entityManager.persist(mapper.toEntity(attempt));
        }
    }

    private void logAfterCommit(Charge charge, UUID attemptId, String eventCode, String message, boolean warning) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                ObservationLog.safely(() -> {
                    var attempt = charge.getAttempts().stream().filter(candidate -> candidate.getAttemptId().equals(attemptId)).findFirst().orElseThrow();
                    (warning ? LOGGER.atWarn() : LOGGER.atInfo()).addKeyValue("event.code", eventCode)
                            .addKeyValue("service_order.id", charge.getServiceOrderId())
                            .addKeyValue("charge.id", charge.getChargeId()).addKeyValue("budget.id", charge.getBudgetId())
                            .addKeyValue("payment_attempt.id", attemptId)
                            .addKeyValue("payment_attempt.status", attempt.getStatus())
                            .addKeyValue("provider_order.id", attempt.getProviderOrderId()).log(message);
                });
            }
        });
    }

    private record ApprovedBudget(UUID budgetId, BigDecimal amount) { }

    private record LoadedCharge(Charge charge, ChargeEntity entity, List<PaymentAttemptEntity> attempts) {
    }
}
