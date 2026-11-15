package br.com.fiap.numberone.billing.budget.infrastructure.persistence.gateways;

import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetNotFoundException;
import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetAuthorizationException;
import br.com.fiap.numberone.billing.budget.application.results.PublicBudgetView;
import br.com.fiap.numberone.billing.budget.domain.valueobjects.BudgetApprovalAuthorization;
import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.enums.BudgetStatus;
import br.com.fiap.numberone.billing.budget.domain.exceptions.BudgetCalculationException;
import br.com.fiap.numberone.billing.budget.domain.valueobjects.BudgetComposition;
import br.com.fiap.numberone.billing.budget.infrastructure.persistence.entities.BudgetEntity;
import br.com.fiap.numberone.billing.budget.infrastructure.persistence.mappers.BudgetPersistenceMapper;
import br.com.fiap.numberone.billing.budget.infrastructure.persistence.repositories.BudgetRepository;
import br.com.fiap.numberone.billing.budget.infrastructure.persistence.repositories.BudgetRevisionCounterRepository;

import br.com.fiap.numberone.billing.budget.infrastructure.outbox.BudgetIntegrationEventFactory;
import br.com.fiap.numberone.billing.budget.infrastructure.outbox.IntegrationEventOutboxRepository;
import br.com.fiap.numberone.billing.budget.infrastructure.notifications.BudgetNotificationRepository;
import jakarta.persistence.EntityManager;
import br.com.fiap.numberone.billing.infrastructure.observability.ObservationLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import br.com.fiap.numberone.billing.budget.application.exceptions.ExternalServiceException;
import br.com.fiap.numberone.billing.infrastructure.rest.ServiceRequestHeaders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

@Component
@Transactional(readOnly = true)
public class BudgetGatewayImpl implements BudgetGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger(BudgetGatewayImpl.class);

    private final BudgetRepository repository;
    private final BudgetPersistenceMapper mapper;
    private final BudgetRevisionCounterRepository counterRepository;
    private final EntityManager entityManager;
    private final BudgetIntegrationEventFactory eventFactory;
    private final IntegrationEventOutboxRepository outboxRepository;
    private final BudgetNotificationRepository notificationRepository;

    public BudgetGatewayImpl(BudgetRepository repository, BudgetPersistenceMapper mapper,
                             BudgetRevisionCounterRepository counterRepository, EntityManager entityManager,
                             BudgetIntegrationEventFactory eventFactory, IntegrationEventOutboxRepository outboxRepository,
                             BudgetNotificationRepository notificationRepository) {
        this.repository = repository;
        this.mapper = mapper;
        this.counterRepository = counterRepository;
        this.entityManager = entityManager;
        this.eventFactory = eventFactory;
        this.outboxRepository = outboxRepository;
        this.notificationRepository = notificationRepository;
    }

    @Override
    @Transactional
    public Budget createDraft(UUID serviceOrderId, BigDecimal quotedAmount, BudgetComposition composition) {
        counterRepository.requireOpenContext(serviceOrderId);
        int version = counterRepository.nextVersion(serviceOrderId);
        Budget.validateRevisionCreation(repository.existsByServiceOrderIdAndStatus(serviceOrderId, BudgetStatus.APPROVED));
        Budget budget = Budget.createDraft(serviceOrderId, version, quotedAmount, composition);
        validateCompositionVersion(composition);
        Budget persisted = mapper.toDomain(repository.saveAndFlush(mapper.toEntity(budget)));
        logAfterCommit(persisted, null, null);
        return persisted;
    }

    @Override
    @Transactional
    public Budget sendForApproval(UUID budgetId, String customerEmail) {
        Budget sent = transition(budgetId, (budget, latestSentVersion) -> {
            budget.validateSendingRevision(latestSentVersion,
                    repository.existsByServiceOrderIdAndStatus(budget.getServiceOrderId(), BudgetStatus.APPROVED));
            budget.markAsSent();
        });
        notificationRepository.schedule(sent, customerEmail);
        return sent;
    }

    @Override
    @Transactional
    public Budget resendForApproval(UUID budgetId, String customerEmail) {
        BudgetEntity entity = loadLockedEntity(budgetId);
        Budget budget = mapper.toDomain(entity);
        budget.validateApprovalResend();
        budget.validateCurrentRevision(repository.findLatestSentVersion(budget.getServiceOrderId(), Budget.sentRevisionStatuses()));
        notificationRepository.validateResendInterval(budgetId);
        notificationRepository.schedule(budget, customerEmail);
        return budget;
    }

    @Override
    public boolean isCurrentSentRevision(UUID budgetId) {
        return repository.findById(budgetId).map(entity -> entity.getStatus() == BudgetStatus.SENT
                && Integer.valueOf(entity.getBudgetVersion()).equals(repository.findLatestSentVersion(
                        entity.getServiceOrderId(), Budget.sentRevisionStatuses()))).orElse(false);
    }

    @Override
    @Transactional
    public Budget approve(UUID budgetId) {
        return transition(budgetId, (budget, latestSentVersion) -> {
            budget.validateCurrentRevision(latestSentVersion);
            budget.approve();
        });
    }

    @Override
    @Transactional
    public Budget registerApproval(UUID budgetId, String employeeId) {
        return transition(budgetId, (budget, latestSentVersion) -> budget.registerApproval(latestSentVersion,
                budget.getStatus() == BudgetStatus.DRAFT
                        && repository.existsByServiceOrderIdAndStatus(budget.getServiceOrderId(), BudgetStatus.APPROVED),
                employeeId));
    }

    @Override
    @Transactional
    public Budget reject(UUID budgetId) {
        return transition(budgetId, (budget, latestSentVersion) -> {
            budget.validateCurrentRevision(latestSentVersion);
            budget.reject();
        });
    }

    @Override
    public PublicBudgetView getAuthorizedBudget(String token) {
        var authorization = notificationRepository.findAuthorization(token);
        Budget budget = repository.findById(authorization.budgetId()).map(mapper::toDomain)
                .orElseThrow(() -> new BudgetAuthorizationException(BudgetAuthorizationException.Reason.INVALID));
        validateAuthorization(authorization, budget,
                repository.findLatestSentVersion(budget.getServiceOrderId(), Budget.sentRevisionStatuses()),
                notificationRepository.currentTime());
        return new PublicBudgetView(budget.getBudgetId(), budget.getBudgetVersion(), budget.getQuotedAmount(),
                authorization.expiresAt(), budget.getComposition());
    }

    @Override
    @Transactional
    public Budget approveRemotely(String token) { return decideRemotely(token, true); }

    @Override
    @Transactional
    public Budget rejectRemotely(String token) { return decideRemotely(token, false); }

    private Budget decideRemotely(String token, boolean approve) {
        // A primeira leitura identifica a revisão; validade e consumo são reavaliados sob o lock da OS.
        var initialAuthorization = notificationRepository.findAuthorization(token);
        return transition(initialAuthorization.budgetId(), (budget, latestSentVersion) -> {
            var authorization = notificationRepository.findAuthorization(token);
            validateAuthorization(authorization, budget, latestSentVersion, notificationRepository.currentTime());
            if (repository.existsByServiceOrderIdAndStatus(budget.getServiceOrderId(), BudgetStatus.APPROVED)) {
                throw new BudgetAuthorizationException(BudgetAuthorizationException.Reason.ALREADY_DECIDED);
            }
            budget.validateCurrentRevision(latestSentVersion);
            if (approve) {
                budget.approveRemotely();
            } else {
                budget.reject();
            }
            notificationRepository.consumeAuthorization(authorization.authorizationId());
        });
    }

    private void validateAuthorization(BudgetApprovalAuthorization authorization, Budget budget,
                                       Integer latestSentVersion, Instant now) {
        if (!authorization.budgetId().equals(budget.getBudgetId())) {
            throw new BudgetAuthorizationException(BudgetAuthorizationException.Reason.INVALID);
        }
        if (authorization.consumedAt() != null) {
            throw new BudgetAuthorizationException(BudgetAuthorizationException.Reason.CONSUMED);
        }
        if (isDecided(budget)) {
            throw new BudgetAuthorizationException(BudgetAuthorizationException.Reason.ALREADY_DECIDED);
        }
        if (!isCurrentSentRevision(budget, latestSentVersion)) {
            throw new BudgetAuthorizationException(BudgetAuthorizationException.Reason.OBSOLETE);
        }
        if (authorization.revokedAt() != null) {
            throw new BudgetAuthorizationException(BudgetAuthorizationException.Reason.REVOKED);
        }
        if (!authorization.isActiveAt(now)) {
            throw new BudgetAuthorizationException(BudgetAuthorizationException.Reason.EXPIRED);
        }
    }

    private Budget transition(UUID budgetId, BiConsumer<Budget, Integer> transition) {
        BudgetEntity entity = loadLockedEntity(budgetId);
        Budget budget = mapper.toDomain(entity);
        Integer latestSentVersion = repository.findLatestSentVersion(budget.getServiceOrderId(), Budget.sentRevisionStatuses());
        BudgetStatus previousStatus = budget.getStatus();
        transition.accept(budget, latestSentVersion);
        validateApprovalForSaga(budget);
        Budget persisted = persistState(budget, entity);
        UUID eventId = appendTransitionEvent(persisted);
        revokeAuthorizationsIfDecided(persisted);
        logAfterCommit(persisted, previousStatus, eventId);
        return persisted;
    }

    private Budget persistState(Budget budget, BudgetEntity entity) {
        mapper.updateState(budget, entity);
        repository.flush();
        return mapper.toDomain(entity);
    }

    private UUID appendTransitionEvent(Budget budget) {
        validateCompositionVersion(budget.getComposition());
        var event = eventFactory.create(budget, ServiceRequestHeaders.correlationId());
        outboxRepository.append(event);
        return event.eventId();
    }

    private void validateCompositionVersion(BudgetComposition composition) {
        if (composition == null || composition.compositionVersion() == null
                || composition.compositionVersion() < 1) {
            throw new ExternalServiceException("Orçamento sem versão de composição da OS",
                    ExternalServiceException.Failure.INVALID_RESPONSE, null);
        }
    }

    private void validateApprovalForSaga(Budget budget) {
        var composition = budget.getComposition();
        if (budget.getStatus() == BudgetStatus.APPROVED && composition != null && composition.compositionVersion() != null) {
            var amount = budget.getApprovedAmount().setScale(2);
            if (amount.signum() <= 0 || amount.precision() > 19) {
                throw new BudgetCalculationException("A aprovação exige valor positivo em BRL com até 17 dígitos inteiros");
            }
        }
    }

    private void revokeAuthorizationsIfDecided(Budget budget) {
        if (isDecided(budget)) {
            notificationRepository.revokeForServiceOrder(budget.getServiceOrderId());
        }
    }

    private boolean isDecided(Budget budget) {
        return budget.getStatus() == BudgetStatus.APPROVED || budget.getStatus() == BudgetStatus.REJECTED;
    }

    private boolean isCurrentSentRevision(Budget budget, Integer latestSentVersion) {
        return budget.getStatus() == BudgetStatus.SENT && latestSentVersion != null
                && budget.getBudgetVersion() == latestSentVersion;
    }

    private void logAfterCommit(Budget budget, BudgetStatus previousStatus, UUID eventId) {
        BudgetStatus status = budget.getStatus();
        // O commit inclui a Outbox e, no envio, o agendamento da notificação.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                ObservationLog.safely(() -> {
                    var log = LOGGER.atInfo().addKeyValue("budget.id", budget.getBudgetId())
                            .addKeyValue("budget.version", budget.getBudgetVersion())
                            .addKeyValue("service_order.id", budget.getServiceOrderId())
                            .addKeyValue("budget.status", status)
                            .addKeyValue("outbox.persisted", status != BudgetStatus.DRAFT);
                    if (previousStatus != null) log.addKeyValue("budget.previous_status", previousStatus);
                    if (eventId != null) log.addKeyValue("event.id", eventId);
                    switch (status) {
                        case SENT -> log.addKeyValue("event.code", "BUDGET_APPROVAL_REQUESTED")
                                .log("Orçamento enviado para aprovação");
                        case APPROVED -> log.addKeyValue("event.code", "BUDGET_APPROVED").log("Orçamento aprovado");
                        case REJECTED -> log.addKeyValue("event.code", "BUDGET_REJECTED").log("Orçamento rejeitado");
                        case DRAFT -> log.addKeyValue("event.code", budget.getBudgetVersion() == 1 ? "BUDGET_CREATED" : "BUDGET_REVISION_CREATED")
                                .log("Orçamento calculado e persistido");
                    }
                });
            }
        });
    }

    private BudgetEntity loadLockedEntity(UUID budgetId) {
        UUID serviceOrderId = repository.findServiceOrderIdByBudgetId(budgetId).orElseThrow(BudgetNotFoundException::new);
        counterRepository.requireOpenContext(serviceOrderId);
        BudgetEntity entity = repository.findById(budgetId).orElseThrow(BudgetNotFoundException::new);
        // Recarregar também quando a transação chamadora já tinha a entidade no contexto JPA.
        entityManager.refresh(entity);
        return entity;
    }

    @Override
    public Optional<Budget> findById(UUID budgetId) {
        return repository.findById(budgetId).map(mapper::toDomain);
    }

    @Override
    public Optional<Budget> findByServiceOrderIdAndBudgetVersion(UUID serviceOrderId, int budgetVersion) {
        return repository.findByServiceOrderIdAndBudgetVersion(serviceOrderId, budgetVersion)
                .map(mapper::toDomain);
    }

    @Override
    public List<Budget> findAllByServiceOrderId(UUID serviceOrderId) {
        return repository.findAllByServiceOrderIdOrderByBudgetVersionAsc(serviceOrderId).stream()
                .map(mapper::toDomain)
                .toList();
    }
}
