package br.com.fiap.numberone.billing.budget.api.publicview;

public record PublicBudgetRequest(String token, boolean confirmed) {
    public static final String ATTRIBUTE = PublicBudgetRequest.class.getName();
    @Override public String toString() { return "PublicBudgetRequest[protected]"; }
}
