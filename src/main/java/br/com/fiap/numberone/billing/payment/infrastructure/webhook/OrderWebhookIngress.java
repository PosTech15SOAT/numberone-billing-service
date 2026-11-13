package br.com.fiap.numberone.billing.payment.infrastructure.webhook;

public record OrderWebhookIngress(String providerOrderId, RuntimeException failure) {
    public static final String ATTRIBUTE = OrderWebhookIngress.class.getName();
    @Override public String toString() { return "OrderWebhookIngress[details=REDACTED]"; }
}
