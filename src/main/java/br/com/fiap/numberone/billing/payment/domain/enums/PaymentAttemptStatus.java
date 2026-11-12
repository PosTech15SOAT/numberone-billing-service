package br.com.fiap.numberone.billing.payment.domain.enums;

public enum PaymentAttemptStatus {
    PENDING, UNKNOWN, PAID, FAILED, EXPIRED;

    public boolean blocksNewAttempt() {
        return this == PENDING || this == UNKNOWN || this == PAID;
    }
}
