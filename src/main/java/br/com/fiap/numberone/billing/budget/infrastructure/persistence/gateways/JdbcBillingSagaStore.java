package br.com.fiap.numberone.billing.budget.infrastructure.persistence.gateways;

import br.com.fiap.numberone.billing.budget.application.exceptions.BillingSagaConflict;
import br.com.fiap.numberone.billing.budget.application.gateways.BillingSagaStore;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.infrastructure.notifications.BudgetNotificationRepository;
import br.com.fiap.numberone.billing.budget.infrastructure.persistence.mappers.BudgetPersistenceMapper;
import br.com.fiap.numberone.billing.budget.infrastructure.persistence.repositories.BudgetRepository;
import br.com.fiap.numberone.billing.budget.infrastructure.persistence.repositories.BudgetRevisionCounterRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static br.com.fiap.numberone.billing.budget.application.exceptions.BillingSagaConflict.Reason.BUDGET_NOT_ELIGIBLE;
import static br.com.fiap.numberone.billing.budget.application.exceptions.BillingSagaConflict.Reason.BUDGET_VERSION_CONFLICT;
import static br.com.fiap.numberone.billing.budget.application.exceptions.BillingSagaConflict.Reason.FINANCIAL_EFFECT_PENDING;
import static br.com.fiap.numberone.billing.budget.application.exceptions.BillingSagaConflict.Reason.IDEMPOTENCY_CONFLICT;

@Component
public class JdbcBillingSagaStore implements BillingSagaStore {
    private final JdbcTemplate jdbc;
    private final BudgetRepository budgets;
    private final BudgetPersistenceMapper mapper;
    private final BudgetRevisionCounterRepository contexts;
    private final BudgetNotificationRepository notifications;

    public JdbcBillingSagaStore(JdbcTemplate jdbc, BudgetRepository budgets, BudgetPersistenceMapper mapper,
                               BudgetRevisionCounterRepository contexts, BudgetNotificationRepository notifications) {
        this.jdbc = jdbc;
        this.budgets = budgets;
        this.mapper = mapper;
        this.contexts = contexts;
        this.notifications = notifications;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Budget> currentBudget(UUID orderId) {
        return currentBudgetId(orderId).flatMap(budgets::findById).map(mapper::toDomain);
    }

    @Override
    public Optional<UUID> orderForBudget(UUID budgetId) {
        return budgets.findServiceOrderIdByBudgetId(budgetId);
    }

    @Override
    public Optional<ClosedContext> previousCancellation(Cancellation request) {
        return jdbc.query("""
                SELECT service_order_id, requested_budget_id, requested_budget_version,
                       cancelled_budget_id, cancelled_budget_version
                FROM billing_cancellation_receipt WHERE operation_id = ?
                """, (row, index) -> {
            UUID orderId = row.getObject("service_order_id", UUID.class);
            if (!orderId.equals(request.serviceOrderId())
                    || !Objects.equals(row.getObject("requested_budget_id", UUID.class), request.budgetId())
                    || !Objects.equals(row.getObject("requested_budget_version", Integer.class), request.budgetVersion())) {
                throw new BillingSagaConflict(IDEMPOTENCY_CONFLICT);
            }
            return new ClosedContext(request.operationId(), orderId, row.getObject("cancelled_budget_id", UUID.class),
                    row.getObject("cancelled_budget_version", Integer.class));
        }, request.operationId()).stream().findFirst();
    }

    @Override
    @Transactional
    public ClosedContext closeContext(Cancellation request) {
        UUID orderId = request.serviceOrderId();
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text, 39113))",
                row -> { }, request.operationId());
        boolean alreadyClosed = contexts.lockContext(orderId);
        return previousCancellation(request).orElseGet(() -> {
            if (alreadyClosed) throw new BillingSagaConflict(BUDGET_NOT_ELIGIBLE);
            return recordClosure(request, orderId);
        });
    }

    private ClosedContext recordClosure(Cancellation request, UUID orderId) {
        Budget current = currentBudget(orderId).orElse(null);
        UUID budgetId = current == null ? null : current.getBudgetId();
        Integer version = current == null ? null : current.getBudgetVersion();
        if (request.budgetId() != null && (!request.budgetId().equals(budgetId)
                || !request.budgetVersion().equals(version))) {
            throw new BillingSagaConflict(BUDGET_VERSION_CONFLICT);
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM charge WHERE service_order_id = ?)",
                Boolean.class, orderId))) {
            throw new BillingSagaConflict(FINANCIAL_EFFECT_PENDING);
        }
        if (budgetId != null) {
            jdbc.update("""
                    UPDATE budget SET status = 'CANCELLED', updated_at = LOCALTIMESTAMP, row_version = row_version + 1
                    WHERE budget_id = ?
                    """, budgetId);
            notifications.revokeForServiceOrder(orderId);
        }
        jdbc.update("UPDATE budget_revision_counter SET context_closed = TRUE WHERE service_order_id = ?", orderId);
        jdbc.update("""
                INSERT INTO billing_cancellation_receipt
                    (operation_id, service_order_id, requested_budget_id, requested_budget_version,
                     cancelled_budget_id, cancelled_budget_version)
                VALUES (?, ?, ?, ?, ?, ?)
                """, request.operationId(), orderId, request.budgetId(), request.budgetVersion(), budgetId, version);
        return new ClosedContext(request.operationId(), orderId, budgetId, version);
    }

    private Optional<UUID> currentBudgetId(UUID orderId) {
        return jdbc.query("""
                SELECT budget_id FROM budget WHERE service_order_id = ?
                ORDER BY CASE status WHEN 'APPROVED' THEN 0 WHEN 'CANCELLED' THEN 0 WHEN 'DRAFT' THEN 2 ELSE 1 END,
                         budget_version DESC
                LIMIT 1
                """, (row, index) -> row.getObject("budget_id", UUID.class), orderId).stream().findFirst();
    }
}
