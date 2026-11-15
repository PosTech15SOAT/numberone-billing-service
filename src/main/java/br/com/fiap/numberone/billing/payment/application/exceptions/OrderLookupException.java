package br.com.fiap.numberone.billing.payment.application.exceptions;

public class OrderLookupException extends RuntimeException {
    public enum Kind { BLOCKED, TIMEOUT, COMMUNICATION, NOT_FOUND, AUTHORIZATION, RATE_LIMIT,
        PROVIDER_FAILURE, REQUEST_REJECTED, INVALID_RESPONSE, ACTIVE_TRANSACTION }
    private final Kind kind;
    private final Integer httpStatus;

    public OrderLookupException(Kind kind, Integer httpStatus, Throwable cause) {
        super("Consulta da order não concluída com segurança", cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    public Kind getKind() { return kind; }
    public Integer getHttpStatus() { return httpStatus; }
}
