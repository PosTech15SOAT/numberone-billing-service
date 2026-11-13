package br.com.fiap.numberone.billing.budget.domain.exceptions;

public class InvalidServiceOrderStatusException extends RuntimeException {

    public InvalidServiceOrderStatusException(String message) {
        super(message);
    }
}
