package br.com.fiap.numberone.billing.payment.infrastructure.rest;

import br.com.fiap.numberone.billing.infrastructure.observability.IntegrationObservation;
import br.com.fiap.numberone.billing.payment.infrastructure.config.MercadoPagoProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

import static br.com.fiap.numberone.billing.payment.infrastructure.rest.SellerIdentityVerificationException.Reason;

/** Confere o titular contra o ID obtido independentemente no painel; não certifica o ambiente TEST. */
public final class MercadoPagoSellerIdentityVerifier {
    private static final int MAX_RESPONSE_BYTES = 65536;

    private final RestClient client;
    private final MercadoPagoProperties properties;
    private final JsonMapper json = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public MercadoPagoSellerIdentityVerifier(RestClient client, MercadoPagoProperties properties) {
        this.client = Objects.requireNonNull(client);
        this.properties = Objects.requireNonNull(properties);
    }

    public String verifyExpectedSeller() {
        try (var observation = new IntegrationObservation("MERCADO_PAGO", "SELLER_IDENTITY_GET")) {
            try {
                if (!properties.enabled()) throw failure(Reason.DISABLED, null, null);
                if (TransactionSynchronizationManager.isActualTransactionActive()) throw failure(Reason.ACTIVE_TRANSACTION, null, null);
                byte[] body = fetchSeller(observation);
                validateSellerIdentity(body);
                return properties.accessToken();
            } catch (RuntimeException failure) {
                observation.failed(failure, failure instanceof SellerIdentityVerificationException known ? known.getReason().name() : "PROCESSING_FAILED");
                throw failure;
            }
        }
    }

    private byte[] fetchSeller(IntegrationObservation observation) {
        try {
            return client.get().uri("/users/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.accessToken()).accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> readResponse(response, observation));
        } catch (ResourceAccessException exception) {
            throw failure(isTimeout(exception) ? Reason.TIMEOUT : Reason.COMMUNICATION, null, exception);
        } catch (RestClientException exception) {
            throw failure(Reason.COMMUNICATION, null, exception);
        } catch (IllegalArgumentException exception) {
            throw failure(Reason.INVALID_RESPONSE, null, exception);
        }
    }

    private byte[] readResponse(ClientHttpResponse response, IntegrationObservation observation) throws IOException {
        int status = response.getStatusCode().value();
        observation.status(status);
        if (status != 200) throw httpFailure(status);
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) throw failure(Reason.INVALID_RESPONSE, status, null);
        byte[] content = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
        if (content.length > MAX_RESPONSE_BYTES) throw failure(Reason.INVALID_RESPONSE, status, null);
        return content;
    }

    private SellerIdentityVerificationException httpFailure(int status) {
        Reason reason = switch (status) {
            case 401, 403 -> Reason.AUTHORIZATION;
            case 408 -> Reason.TIMEOUT;
            default -> Reason.INVALID_RESPONSE;
        };
        RestClientResponseException cause = new RestClientResponseException("Resposta HTTP da validação do vendedor",
                status, "", null, new byte[0], StandardCharsets.UTF_8);
        return failure(reason, status, cause);
    }

    private void validateSellerIdentity(byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode sellerId = root == null ? null : root.get("id");
            if (root == null || !root.isObject() || sellerId == null || !sellerId.isIntegralNumber()
                    || !sellerId.canConvertToLong() || sellerId.asLong() <= 0) throw failure(Reason.INVALID_RESPONSE, 200, null);
            if (!Long.toString(sellerId.asLong()).equals(properties.expectedTestSellerId())) throw failure(Reason.IDENTITY_MISMATCH, 200, null);
        } catch (JacksonException exception) {
            throw failure(Reason.INVALID_RESPONSE, 200, exception);
        }
    }

    private boolean isTimeout(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (failure != null && visited.add(failure)) {
            if (failure instanceof SocketTimeoutException || failure instanceof HttpTimeoutException) return true;
            failure = failure.getCause();
        }
        return false;
    }

    private SellerIdentityVerificationException failure(Reason reason, Integer status, Throwable cause) {
        return new SellerIdentityVerificationException(reason, status, cause);
    }
}
