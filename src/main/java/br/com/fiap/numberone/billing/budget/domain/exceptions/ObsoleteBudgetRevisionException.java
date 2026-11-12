package br.com.fiap.numberone.billing.budget.domain.exceptions;

public class ObsoleteBudgetRevisionException extends RuntimeException {

    public ObsoleteBudgetRevisionException() {
        super("A revisão do orçamento não está vigente para esta operação");
    }
}
