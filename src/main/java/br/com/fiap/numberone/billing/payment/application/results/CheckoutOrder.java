package br.com.fiap.numberone.billing.payment.application.results;

import java.net.URI;

/** Resultado de criação de checkout; não representa confirmação financeira. */
public record CheckoutOrder(String providerOrderId, URI checkoutUrl, String providerStatus) {
    @Override
    public String toString() {
        return "CheckoutOrder[details=REDACTED]";
    }
}
