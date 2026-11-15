package br.com.fiap.numberone.billing.payment.application.exceptions;

public class CheckoutCreationException extends RuntimeException {
    public enum Kind {
        DISABLED, TIMEOUT, COMMUNICATION, INVALID_RESPONSE, REQUEST_REJECTED,
        AUTHORIZATION, IDEMPOTENCY_CONFLICT, RATE_LIMIT, PROVIDER_FAILURE, OTHER_REJECTION
    }

    private final Kind kind;
    private final Integer httpStatus;
    private final boolean resultUnknown;

    public CheckoutCreationException(Kind kind, Integer httpStatus, boolean resultUnknown, Throwable cause) {
        super(message(kind), cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.resultUnknown = resultUnknown;
    }

    private static String message(Kind kind) {
        return switch (kind) {
            case DISABLED -> "Criação externa de checkout desabilitada";
            case TIMEOUT -> "Tempo limite na criação de checkout; resultado desconhecido";
            case COMMUNICATION -> "Falha de comunicação na criação de checkout; resultado desconhecido";
            case INVALID_RESPONSE -> "Resposta de checkout inválida; resultado desconhecido";
            case REQUEST_REJECTED -> "Provedor recusou os dados da criação de checkout";
            case AUTHORIZATION -> "Provedor recusou a autorização para criar checkout";
            case IDEMPOTENCY_CONFLICT -> "Conflito de idempotência no provedor; reconciliar a tentativa original";
            case RATE_LIMIT -> "Limite de requisições do provedor atingido; reconciliar a tentativa original";
            case PROVIDER_FAILURE -> "Falha do provedor na criação de checkout; resultado desconhecido";
            case OTHER_REJECTION -> "Provedor recusou a requisição de criação de checkout";
        };
    }

    public Kind getKind() { return kind; }
    public Integer getHttpStatus() { return httpStatus; }
    public boolean isResultUnknown() { return resultUnknown; }
}
