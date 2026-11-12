package br.com.fiap.numberone.billing.budget.application.exceptions;

public class ExternalServiceException extends RuntimeException {

    public enum Failure {
        UNAVAILABLE, TIMEOUT, INVALID_RESPONSE
    }

    private final Failure failure;

    public ExternalServiceException(String message, Failure failure, Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }

    public Failure getFailure() {
        return failure;
    }
}
