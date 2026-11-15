package br.com.fiap.numberone.billing.budget.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("billing.outbox.sqs")
public record SqsPublicationProperties(String region, URI queueUrl, URI endpoint,
                                       Duration connectionTimeout, Duration readTimeout, Duration apiCallTimeout) {

    public SqsPublicationProperties {
        if (region == null || region.isBlank() || !httpUri(queueUrl) || (endpoint != null && !httpUri(endpoint))
                || !positive(connectionTimeout) || !positive(readTimeout) || !positive(apiCallTimeout)
                || connectionTimeout.compareTo(apiCallTimeout) > 0 || readTimeout.compareTo(apiCallTimeout) > 0) {
            throw new IllegalArgumentException("Configuração de região, fila, endpoint ou timeouts SQS inválida");
        }
    }

    private static boolean httpUri(URI uri) {
        return uri != null && uri.getHost() != null && uri.getUserInfo() == null
                && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()));
    }

    private static boolean positive(Duration duration) {
        return duration != null && duration.toMillis() > 0;
    }
}
