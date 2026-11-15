package br.com.fiap.numberone.billing.payment.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "billing.payment.service-order", ignoreUnknownFields = false)
public record ServiceOrderPaymentProperties(Duration connectTimeout, Duration readTimeout) {
    public ServiceOrderPaymentProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
        validateTimeout(connectTimeout);
        validateTimeout(readTimeout);
    }

    private static void validateTimeout(Duration timeout) {
        if (timeout.isNegative() || timeout.isZero() || timeout.toMillis() < 1 || timeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("Timeout de CT-02A deve estar entre 1 ms e 1 minuto");
        }
    }
}
