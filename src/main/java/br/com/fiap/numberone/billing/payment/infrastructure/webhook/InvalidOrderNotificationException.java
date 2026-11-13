package br.com.fiap.numberone.billing.payment.infrastructure.webhook;

public class InvalidOrderNotificationException extends RuntimeException {
    public enum Reason { INVALID_NOTIFICATION, MISSING_SIGNATURE, REPEATED_SIGNATURE,
        REPEATED_REQUEST_ID, MISSING_ORDER_ID, REPEATED_ORDER_ID, INVALID_SIGNATURE_FORMAT,
        INVALID_REQUEST_ID, INVALID_ORDER_ID, SIGNATURE_MISMATCH }
    private final int status;
    private final Reason reason;
    public InvalidOrderNotificationException(int status) {
        this(status, status == 401 ? Reason.INVALID_SIGNATURE_FORMAT : Reason.INVALID_NOTIFICATION);
    }
    public InvalidOrderNotificationException(int status, Reason reason) {
        super("Notificação de order não aceita");
        this.status = status;
        this.reason = reason;
    }
    public Reason getReason() { return reason; }
    public int getStatus() { return status; }
}
