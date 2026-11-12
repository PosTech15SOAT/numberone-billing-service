package br.com.fiap.numberone.billing.budget.application.exceptions;

public class ServiceOrderNotFoundException extends RuntimeException {

    public ServiceOrderNotFoundException() {
        super("Ordem de serviço não encontrada");
    }
}
