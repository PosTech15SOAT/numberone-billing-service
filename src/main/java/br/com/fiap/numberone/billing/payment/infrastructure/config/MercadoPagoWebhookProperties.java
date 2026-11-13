package br.com.fiap.numberone.billing.payment.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "billing.payment.mercado-pago-webhook", ignoreUnknownFields = false)
public record MercadoPagoWebhookProperties(boolean enabled, String environment, String secret) {
    public MercadoPagoWebhookProperties {
        if (environment != null && !environment.isBlank() && !environment.equals("TEST")) {
            throw new IllegalArgumentException("Webhook Mercado Pago permite somente TEST");
        }
        if (enabled && (!"TEST".equals(environment) || secret == null || secret.isBlank() || secret.length() > 4096
                || !secret.equals(secret.strip()) || secret.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("Webhook exige TEST explícito e segredo externo válido");
        }
    }

    @Override
    public String toString() {
        return "MercadoPagoWebhookProperties[enabled=" + enabled + ", secret=REDACTED]";
    }
}
