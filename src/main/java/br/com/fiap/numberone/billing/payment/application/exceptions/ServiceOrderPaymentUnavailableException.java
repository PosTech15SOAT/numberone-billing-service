package br.com.fiap.numberone.billing.payment.application.exceptions;

public class ServiceOrderPaymentUnavailableException extends RuntimeException {
    public enum Failure { DISABLED, TIMEOUT, UNAVAILABLE, INVALID_RESPONSE }
    private final Failure failure;

    public ServiceOrderPaymentUnavailableException() {
        super("Consulta confiável da OS indisponível; contrato CT-02A e autenticação pendentes com Matheus");
        this.failure = Failure.DISABLED;
    }

    public ServiceOrderPaymentUnavailableException(Throwable cause) {
        this(Failure.UNAVAILABLE, cause);
    }

    public ServiceOrderPaymentUnavailableException(Failure failure, Throwable cause) {
        super(switch (failure) {
            case DISABLED -> "Consulta confiável da OS desabilitada";
            case TIMEOUT -> "Tempo limite na consulta da OS";
            case UNAVAILABLE -> "Consulta da OS indisponível";
            case INVALID_RESPONSE -> "Resposta inválida na consulta da OS";
        }, cause);
        this.failure = failure;
    }

    public Failure getFailure() { return failure; }
}
