package br.com.fiap.numberone.billing.budget.application.exceptions;

public class IntegrationEventPublicationException extends RuntimeException {

    public enum Failure { TIMEOUT, UNAVAILABLE, REJECTED }

    private final Failure failure;

    public IntegrationEventPublicationException(Failure failure, int statusCode) {
        this(failure, statusCode, null);
    }

    public IntegrationEventPublicationException(Failure failure, int statusCode, Throwable cause) {
        super("SQS_" + failure.name() + " HTTP_" + statusCode, cause);
        this.failure = failure;
    }

    public boolean requiresReview() {
        return failure == Failure.REJECTED;
    }
}
