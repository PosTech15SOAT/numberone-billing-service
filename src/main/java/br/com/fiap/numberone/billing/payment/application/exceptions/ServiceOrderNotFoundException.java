package br.com.fiap.numberone.billing.payment.application.exceptions;

public class ServiceOrderNotFoundException extends RuntimeException {
    public ServiceOrderNotFoundException() {
        super("OS não encontrada");
    }
}
