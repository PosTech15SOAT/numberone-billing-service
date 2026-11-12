package br.com.fiap.numberone.billing.payment.infrastructure.rest;

import br.com.fiap.numberone.billing.infrastructure.observability.IntegrationObservation;
import br.com.fiap.numberone.billing.payment.application.exceptions.ServiceOrderNotFoundException;
import br.com.fiap.numberone.billing.payment.application.exceptions.ServiceOrderPaymentUnavailableException;
import br.com.fiap.numberone.billing.payment.application.exceptions.ServiceOrderPaymentUnavailableException.Failure;
import br.com.fiap.numberone.billing.payment.application.gateways.ServiceOrderPaymentGateway;
import br.com.fiap.numberone.billing.payment.domain.references.ServiceOrderPaymentContext;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

/** PROPOSTA CT-02A; construção operacional restrita pela configuração ao modo local loopback. */
public class ServiceOrderPaymentRestGateway implements ServiceOrderPaymentGateway {
    private static final int MAX_RESPONSE_BYTES = 16384;

    private final RestClient client;
    private final ServiceOrderPaymentMapper mapper;

    public ServiceOrderPaymentRestGateway(RestClient client, ServiceOrderPaymentMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public ServiceOrderPaymentContext findById(UUID serviceOrderId) {
        var observation = new IntegrationObservation("OS_SERVICE", "PAYMENT_CONTEXT_GET");
        observation.identifier("service_order.id", serviceOrderId);
        try {
            byte[] response = fetchContext(serviceOrderId, observation);
            return toContext(serviceOrderId, response);
        } catch (RuntimeException exception) {
            RuntimeException failure = translateFailure(exception);
            observation.failed(failure, failure instanceof ServiceOrderPaymentUnavailableException known ? known.getFailure().name() : "PROCESSING_FAILED");
            throw failure;
        } finally {
            observation.close();
        }
    }

    private byte[] fetchContext(UUID serviceOrderId, IntegrationObservation observation) {
        return client.get().uri("/internal/service-orders/{serviceOrderId}", serviceOrderId)
                .accept(MediaType.APPLICATION_JSON)
                .exchange((request, response) -> readResponse(response, observation));
    }

    private RuntimeException translateFailure(RuntimeException failure) {
        return switch (failure) {
            case ResourceAccessException communication -> new ServiceOrderPaymentUnavailableException(
                    isTimeout(communication) ? Failure.TIMEOUT : Failure.UNAVAILABLE, communication);
            case RestClientException communication -> new ServiceOrderPaymentUnavailableException(Failure.UNAVAILABLE, communication);
            default -> failure;
        };
    }

    private byte[] readResponse(ClientHttpResponse response, IntegrationObservation observation) throws IOException {
        int status = response.getStatusCode().value();
        observation.status(status);
        if (status == 404) throw new ServiceOrderNotFoundException();
        if (status < 200 || status >= 300) throw httpFailure(status);
        if (response.getHeaders().getContentType() == null
                || !MediaType.APPLICATION_JSON.isCompatibleWith(response.getHeaders().getContentType())) {
            throw new ServiceOrderPaymentUnavailableException(Failure.INVALID_RESPONSE, null);
        }
        byte[] content = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
        if (content.length > MAX_RESPONSE_BYTES) {
            throw new ServiceOrderPaymentUnavailableException(Failure.INVALID_RESPONSE, null);
        }
        return content;
    }

    private ServiceOrderPaymentUnavailableException httpFailure(int status) {
        Failure failure = status == 408 ? Failure.TIMEOUT : status < 400 ? Failure.INVALID_RESPONSE : Failure.UNAVAILABLE;
        return new ServiceOrderPaymentUnavailableException(failure,
                new RestClientResponseException("Resposta HTTP CT-02A", status, "", null,
                        new byte[0], StandardCharsets.UTF_8));
    }

    private ServiceOrderPaymentContext toContext(UUID serviceOrderId, byte[] body) {
        try {
            return mapper.toContext(serviceOrderId, body);
        } catch (IllegalArgumentException exception) {
            throw new ServiceOrderPaymentUnavailableException(Failure.INVALID_RESPONSE, exception);
        }
    }

    private boolean isTimeout(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (failure != null && visited.add(failure)) {
            if (failure instanceof HttpTimeoutException || failure instanceof SocketTimeoutException) return true;
            failure = failure.getCause();
        }
        return false;
    }
}
