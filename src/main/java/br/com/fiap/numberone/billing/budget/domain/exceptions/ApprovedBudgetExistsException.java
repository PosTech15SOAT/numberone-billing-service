package br.com.fiap.numberone.billing.budget.domain.exceptions;

public class ApprovedBudgetExistsException extends RuntimeException {

    public ApprovedBudgetExistsException() {
        super("Já existe orçamento aprovado para esta ordem de serviço; não é permitido criar ou enviar outra revisão");
    }
}
