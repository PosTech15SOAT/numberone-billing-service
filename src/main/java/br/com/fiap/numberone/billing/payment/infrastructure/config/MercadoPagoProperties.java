package br.com.fiap.numberone.billing.payment.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "billing.payment.mercado-pago", ignoreUnknownFields = false)
public record MercadoPagoProperties(boolean enabled, String environment, String accessToken,
                                    Duration connectTimeout, Duration readTimeout, String expectedTestSellerId) {
    public MercadoPagoProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
        validateTimeout(connectTimeout);
        validateTimeout(readTimeout);
        if (environment != null && !environment.isBlank() && !"TEST".equals(environment)) {
            throw new IllegalArgumentException("Mercado Pago permite exclusivamente ambiente TEST neste projeto");
        }
        if (accessToken != null && !accessToken.isEmpty()
                && (accessToken.length() > 4096 || !accessToken.matches("(?:TEST-|APP_USR-)[A-Za-z0-9_-]+"))) {
            throw new IllegalArgumentException("Formato da credencial Mercado Pago inválido; origem deve ser conferida no painel Teste");
        }
        if (expectedTestSellerId != null && !expectedTestSellerId.isEmpty() && !validSellerId(expectedTestSellerId)) {
            throw new IllegalArgumentException("Identificador esperado do vendedor de teste inválido");
        }
        if (enabled && (!"TEST".equals(environment) || accessToken == null || accessToken.isBlank()
                || !validSellerId(expectedTestSellerId))) {
            throw new IllegalArgumentException("Habilitar Mercado Pago exige TEST explícito, credencial e vendedor de teste previamente conferido");
        }
    }

    private static boolean validSellerId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) return false;
        try { return Long.parseLong(value) > 0; }
        catch (NumberFormatException ignored) { return false; }
    }

    private static void validateTimeout(Duration timeout) {
        if (timeout.compareTo(Duration.ofMillis(1)) < 0 || timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("Timeouts do Mercado Pago devem estar entre 1 ms e o limite suportado pelo cliente HTTP");
        }
    }

    @Override
    public String toString() {
        return "MercadoPagoProperties[enabled=" + enabled + ", environment=" + environment + ", accessToken=REDACTED, expectedTestSellerId=REDACTED]";
    }
}
