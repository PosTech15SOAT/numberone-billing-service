package br.com.fiap.numberone.billing.budget.application.exceptions;

public class BillingSagaConflict extends RuntimeException {
    public enum Reason { IDEMPOTENCY_CONFLICT, BUDGET_VERSION_CONFLICT, BUDGET_NOT_ELIGIBLE, FINANCIAL_EFFECT_PENDING }

    private final Reason reason;

    public BillingSagaConflict(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
