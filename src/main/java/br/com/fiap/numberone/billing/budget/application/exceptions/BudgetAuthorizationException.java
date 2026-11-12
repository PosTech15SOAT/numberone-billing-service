package br.com.fiap.numberone.billing.budget.application.exceptions;

public class BudgetAuthorizationException extends RuntimeException {
    public enum Reason { INVALID, EXPIRED, REVOKED, CONSUMED, ALREADY_DECIDED, OBSOLETE, CONFIRMATION_REQUIRED }
    private final Reason reason;

    public BudgetAuthorizationException(Reason reason) {
        super(switch (reason) {
            case INVALID -> "O link de autorização é inválido";
            case EXPIRED -> "Este link expirou. Entre em contato com a oficina para solicitar um novo link";
            case REVOKED -> "Este link foi substituído ou revogado. Utilize o link mais recente ou entre em contato com a oficina";
            case CONSUMED -> "Este link já foi utilizado para registrar uma decisão";
            case ALREADY_DECIDED -> "O orçamento já foi decidido. Nenhuma nova decisão foi registrada";
            case OBSOLETE -> "Esta revisão do orçamento não está mais vigente. Solicite o link da revisão atual à oficina";
            case CONFIRMATION_REQUIRED -> "Marque a confirmação antes de aprovar ou rejeitar o orçamento";
        });
        this.reason = reason;
    }

    public Reason getReason() { return reason; }
}
