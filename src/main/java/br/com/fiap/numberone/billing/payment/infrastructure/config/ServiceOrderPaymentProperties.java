package br.com.fiap.numberone.billing.payment.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

/** PROPOSTA: somente desenvolvimento loopback até a definição de autenticação CT-02A. */
@ConfigurationProperties(prefix = "billing.payment.service-order", ignoreUnknownFields = false)
public record ServiceOrderPaymentProperties(boolean localUnauthenticatedEnabled, String baseUrl,
                                           Duration connectTimeout, Duration readTimeout) {
    public ServiceOrderPaymentProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
        validateTimeout(connectTimeout);
        validateTimeout(readTimeout);
        if (localUnauthenticatedEnabled) validateLocalUrl(baseUrl);
    }

    private static void validateLocalUrl(String value) {
        URI endpoint;
        try {
            endpoint = value == null ? null : URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("URL local de CT-02A inválida");
        }
        if (endpoint == null || endpoint.getScheme() == null || !Set.of("http", "https").contains(endpoint.getScheme())
                || endpoint.getHost() == null || !Set.of("localhost", "127.0.0.1", "[::1]").contains(endpoint.getHost())
                || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || !(endpoint.getRawPath().isEmpty() || endpoint.getRawPath().equals("/"))
                || endpoint.getPort() == 0 || endpoint.getPort() > 65535) {
            throw new IllegalArgumentException("CT-02A sem autenticação permite somente uma URL loopback local sem credenciais");
        }
    }

    private static void validateTimeout(Duration timeout) {
        if (timeout.isNegative() || timeout.isZero() || timeout.toMillis() < 1 || timeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("Timeout de CT-02A deve estar entre 1 ms e 1 minuto");
        }
    }

    @Override
    public String toString() { return "ServiceOrderPaymentProperties[localUnauthenticatedEnabled=" + localUnauthenticatedEnabled + "]"; }
}
