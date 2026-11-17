package br.com.fiap.numberone.billing.shared.infrastructure.outbox;

import br.com.fiap.numberone.billing.budget.application.exceptions.IntegrationEventPublicationException;
import br.com.fiap.numberone.billing.budget.application.exceptions.IntegrationEventPublicationException.Failure;
import br.com.fiap.numberone.billing.budget.application.gateways.IntegrationEventPublicationGateway;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class HttpIntegrationEventPublicationGateway implements IntegrationEventPublicationGateway, AutoCloseable {
    private final HttpClient client;
    private final URI publishUrl;
    private final Path tokenFile;
    private final Duration timeout;

    public HttpIntegrationEventPublicationGateway(URI baseUrl, Path tokenFile, Duration connectTimeout, Duration timeout) {
        if (!"https".equals(baseUrl.getScheme()) || baseUrl.getHost() == null || baseUrl.getUserInfo() != null
                || baseUrl.getRawQuery() != null || baseUrl.getRawFragment() != null
                || !List.of("", "/").contains(baseUrl.getPath())) {
            throw new IllegalArgumentException("A ponte SQS exige uma URL base HTTPS.");
        }
        this.publishUrl = baseUrl.resolve("/eventos");
        this.tokenFile = tokenFile;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    @Override
    public void publish(String envelope) {
        try {
            var request = HttpRequest.newBuilder(publishUrl).timeout(timeout)
                    .header("Authorization", "Bearer " + Files.readString(tokenFile).strip())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)).build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status != 201) {
                var failure = status == 400 || status == 413 ? Failure.REJECTED : Failure.UNAVAILABLE;
                throw new IntegrationEventPublicationException(failure, status);
            }
        } catch (HttpTimeoutException ex) {
            throw new IntegrationEventPublicationException(Failure.TIMEOUT, 0, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IntegrationEventPublicationException(Failure.UNAVAILABLE, 0, ex);
        } catch (IOException ex) {
            throw new IntegrationEventPublicationException(Failure.UNAVAILABLE, 0, ex);
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
