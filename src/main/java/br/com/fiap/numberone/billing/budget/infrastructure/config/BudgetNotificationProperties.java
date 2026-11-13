package br.com.fiap.numberone.billing.budget.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("billing.notifications")
public record BudgetNotificationProperties(String encryptionKey, String approvalBaseUrl, Duration authorizationTtl,
                                            Duration resendInterval, int batchSize, int maxAttempts,
                                            Duration leaseDuration, Duration pollInterval,
                                            Duration initialBackoff, Duration maxBackoff, String from) {
    public BudgetNotificationProperties {
        if (!positive(authorizationTtl) || !positive(resendInterval) || batchSize < 1 || maxAttempts < 1
                || !positive(leaseDuration) || !positive(pollInterval) || !positive(initialBackoff)
                || !positive(maxBackoff) || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("Configuração de autorização ou notificações inválida");
        }
    }

    public Duration retryDelay(int attempt) {
        long delay = initialBackoff.toMillis(), maximum = maxBackoff.toMillis();
        for (int i = 1; i < attempt && delay < maximum; i++) {
            delay = delay > maximum / 2 ? maximum : Math.min(delay * 2, maximum);
        }
        return Duration.ofMillis(delay);
    }

    private static boolean positive(Duration duration) {
        return duration != null && duration.toMillis() > 0;
    }

    @Override
    public String toString() { return "BudgetNotificationProperties[protected]"; }
}
