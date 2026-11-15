package br.com.fiap.numberone.billing.budget.infrastructure.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties("billing.integrations")
public record BudgetIntegrationProperties(@NotNull URI osBaseUrl, @NotNull URI inventoryBaseUrl,
                                           @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {

    public BudgetIntegrationProperties {
        validateUrl(osBaseUrl);
        validateUrl(inventoryBaseUrl);
        if ((connectTimeout != null && (connectTimeout.isNegative() || connectTimeout.isZero()))
                || (readTimeout != null && (readTimeout.isNegative() || readTimeout.isZero()))) {
            throw new IllegalArgumentException("Os timeouts das integrações devem ser positivos");
        }
    }

    private static void validateUrl(URI uri) {
        if (uri != null && (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())))) {
            throw new IllegalArgumentException("As URLs das integrações devem ser endereços HTTP ou HTTPS absolutos");
        }
    }
}
