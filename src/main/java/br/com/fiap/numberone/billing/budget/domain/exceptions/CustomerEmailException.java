package br.com.fiap.numberone.billing.budget.domain.exceptions;

public class CustomerEmailException extends RuntimeException {

    public CustomerEmailException() {
        super("O e-mail do cliente é obrigatório para solicitar aprovação do orçamento");
    }
}
