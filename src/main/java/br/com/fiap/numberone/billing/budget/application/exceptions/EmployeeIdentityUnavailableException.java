package br.com.fiap.numberone.billing.budget.application.exceptions;

public class EmployeeIdentityUnavailableException extends RuntimeException {

    public EmployeeIdentityUnavailableException() {
        super("Contexto de auditoria do funcionário indisponível para registrar a aprovação presencial");
    }
}
