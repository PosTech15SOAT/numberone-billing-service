package br.com.fiap.numberone.billing.payment.infrastructure.rest;

import br.com.fiap.numberone.billing.infrastructure.observability.IntegrationObservation;
import br.com.fiap.numberone.billing.payment.application.exceptions.OrderLookupException;
import br.com.fiap.numberone.billing.payment.application.exceptions.OrderLookupException.Kind;
import br.com.fiap.numberone.billing.payment.application.gateways.OrderLookupGateway;
import br.com.fiap.numberone.billing.payment.application.results.ProviderOrderSnapshot;
import br.com.fiap.numberone.billing.payment.infrastructure.config.MercadoPagoProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import java.util.Objects;
import java.util.Set;

/** Consulta local TEST: valida o vendedor fora de transação e usa exatamente a credencial validada. */
public class MercadoPagoOrderLookupGateway implements OrderLookupGateway {
    private static final int MAX_RESPONSE_BYTES = 65536;

    private final RestClient client;
    private final MercadoPagoProperties properties;
    private final MercadoPagoOrderDetailsMapper mapper;
    private final MercadoPagoSellerIdentityVerifier sellerIdentity;

    public MercadoPagoOrderLookupGateway(RestClient client, MercadoPagoProperties properties, MercadoPagoOrderDetailsMapper mapper,
                                        MercadoPagoSellerIdentityVerifier sellerIdentity) {
        this.client = client;
        this.properties = properties;
        this.mapper = mapper;
        this.sellerIdentity = Objects.requireNonNull(sellerIdentity);
    }

    @Override
    public ProviderOrderSnapshot findById(String providerOrderId) {
        try (var observation = new IntegrationObservation("MERCADO_PAGO", "ORDER_GET")) {
            try {
                if (!properties.enabled()) throw failure(Kind.BLOCKED, null, null);
                if (TransactionSynchronizationManager.isActualTransactionActive()) throw failure(Kind.ACTIVE_TRANSACTION, null, null);
                if (providerOrderId == null || !providerOrderId.matches("[A-Za-z0-9_-]{1,255}")) throw failure(Kind.INVALID_RESPONSE, null, null);
                observation.identifier("provider_order.id", providerOrderId);
                String validatedCredential = verifySellerCredential();
                byte[] body = fetchOrder(providerOrderId, validatedCredential, observation);
                try {
                    return mapper.toSnapshot(providerOrderId, body);
                } catch (IllegalArgumentException exception) {
                    throw failure(Kind.INVALID_RESPONSE, 200, exception);
                }
            } catch (RuntimeException failure) {
                observation.failed(failure, failure instanceof OrderLookupException known ? known.getKind().name() : "PROCESSING_FAILED");
                throw failure;
            }
        }
    }

    private String verifySellerCredential() {
        try {
            return sellerIdentity.verifyExpectedSeller();
        } catch (SellerIdentityVerificationException exception) {
            Kind kind = switch (exception.getReason()) {
                case DISABLED -> Kind.BLOCKED;
                case ACTIVE_TRANSACTION -> Kind.ACTIVE_TRANSACTION;
                case AUTHORIZATION, IDENTITY_MISMATCH -> Kind.AUTHORIZATION;
                case TIMEOUT -> Kind.TIMEOUT;
                case COMMUNICATION -> Kind.COMMUNICATION;
                case INVALID_RESPONSE -> Kind.INVALID_RESPONSE;
            };
            throw failure(kind, exception.getHttpStatus(), exception);
        }
    }

    private byte[] fetchOrder(String providerOrderId, String validatedCredential, IntegrationObservation observation) {
        try {
            return client.get().uri("/v1/orders/{order_id}", providerOrderId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + validatedCredential).accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> readResponse(response, observation));
        } catch (ResourceAccessException exception) {
            throw failure(isTimeout(exception) ? Kind.TIMEOUT : Kind.COMMUNICATION, null, exception);
        } catch (RestClientException exception) {
            throw failure(Kind.COMMUNICATION, null, exception);
        }
    }

    private byte[] readResponse(ClientHttpResponse response, IntegrationObservation observation) throws IOException {
        int status = response.getStatusCode().value();
        observation.status(status);
        if (status != 200) throw httpFailure(status);
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) throw failure(Kind.INVALID_RESPONSE, status, null);
        byte[] content = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
        if (content.length > MAX_RESPONSE_BYTES) throw failure(Kind.INVALID_RESPONSE, status, null);
        return content;
    }

    private OrderLookupException httpFailure(int status) {
        Kind kind = switch (status) {
            case 404 -> Kind.NOT_FOUND;
            case 401, 403 -> Kind.AUTHORIZATION;
            case 408 -> Kind.TIMEOUT;
            case 429 -> Kind.RATE_LIMIT;
            default -> status >= 500 ? Kind.PROVIDER_FAILURE : status >= 400 ? Kind.REQUEST_REJECTED : Kind.INVALID_RESPONSE;
        };
        RestClientResponseException cause = new RestClientResponseException("Resposta HTTP da consulta de order",
                status, "", null, new byte[0], StandardCharsets.UTF_8);
        return failure(kind, status, cause);
    }

    private boolean isTimeout(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (failure != null && visited.add(failure)) {
            if (failure instanceof SocketTimeoutException || failure instanceof HttpTimeoutException) return true;
            failure = failure.getCause();
        }
        return false;
    }

    private OrderLookupException failure(Kind kind, Integer status, Throwable cause) {
        return new OrderLookupException(kind, status, cause);
    }
}
