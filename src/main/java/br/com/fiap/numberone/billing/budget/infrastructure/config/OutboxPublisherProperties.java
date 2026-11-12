package br.com.fiap.numberone.billing.budget.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("billing.outbox")
public record OutboxPublisherProperties(int batchSize, Duration leaseDuration, Duration pollInterval,
                                        int maxAttempts, Duration initialBackoff, Duration maxBackoff) {

    public OutboxPublisherProperties {
        if (batchSize < 1 || maxAttempts < 1 || !positive(leaseDuration) || !positive(pollInterval)
                || !positive(initialBackoff) || !positive(maxBackoff) || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("Configuração de lote, posse, intervalo ou retentativas da Outbox inválida");
        }
    }

    public Duration retryDelay(int attemptCount) {
        long delay = initialBackoff.toMillis();
        long maximum = maxBackoff.toMillis();
        for (int attempt = 1; attempt < attemptCount && delay < maximum; attempt++) {
            delay = delay > maximum / 2 ? maximum : Math.min(delay * 2, maximum);
        }
        return Duration.ofMillis(delay);
    }

    private static boolean positive(Duration duration) {
        return duration != null && duration.toMillis() > 0;
    }
}
