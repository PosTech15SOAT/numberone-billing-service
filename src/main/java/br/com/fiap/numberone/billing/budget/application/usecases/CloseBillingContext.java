package br.com.fiap.numberone.billing.budget.application.usecases;

import br.com.fiap.numberone.billing.budget.application.exceptions.BillingSagaConflict;
import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetNotFoundException;
import br.com.fiap.numberone.billing.budget.application.gateways.BillingSagaStore;
import br.com.fiap.numberone.billing.budget.application.gateways.BillingSagaStore.Cancellation;
import br.com.fiap.numberone.billing.budget.application.gateways.BillingSagaStore.ClosedContext;
import br.com.fiap.numberone.billing.payment.application.gateways.ServiceOrderPaymentGateway;

import java.util.Set;
import java.util.UUID;

public class CloseBillingContext {
    private static final Set<String> BEFORE_EXECUTION = Set.of(
            "RECEIVED", "IN_DIAGNOSIS", "WAITING_APPROVAL", "APPROVED", "REJECTED");

    private final BillingSagaStore store;
    private final ServiceOrderPaymentGateway orders;

    public CloseBillingContext(BillingSagaStore store, ServiceOrderPaymentGateway orders) {
        this.store = store;
        this.orders = orders;
    }

    public ClosedContext cancelBudget(UUID operationId, UUID budgetId, int budgetVersion) {
        UUID orderId = store.orderForBudget(budgetId).orElseThrow(BudgetNotFoundException::new);
        return execute(new Cancellation(operationId, orderId, budgetId, budgetVersion));
    }

    public ClosedContext execute(Cancellation request) {
        var previous = store.previousCancellation(request);
        if (previous.isPresent()) return previous.get();
        var state = orders.findById(request.serviceOrderId());
        if (!state.cancellationInProgress() || !BEFORE_EXECUTION.contains(state.status())) {
            // Uma repetição pode coincidir com a conclusão da compensação na OS.
            return store.previousCancellation(request)
                    .orElseThrow(() -> new BillingSagaConflict(BillingSagaConflict.Reason.BUDGET_NOT_ELIGIBLE));
        }
        return store.closeContext(request);
    }
}
