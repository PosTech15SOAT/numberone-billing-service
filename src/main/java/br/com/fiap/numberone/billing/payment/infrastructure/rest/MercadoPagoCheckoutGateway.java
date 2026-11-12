package br.com.fiap.numberone.billing.payment.infrastructure.rest;

import br.com.fiap.numberone.billing.infrastructure.observability.IntegrationObservation;
import br.com.fiap.numberone.billing.payment.application.exceptions.CheckoutCreationException;
import br.com.fiap.numberone.billing.payment.application.exceptions.CheckoutCreationException.Kind;
import br.com.fiap.numberone.billing.payment.application.gateways.CheckoutGateway;
import br.com.fiap.numberone.billing.payment.application.results.CheckoutOrder;
import br.com.fiap.numberone.billing.payment.domain.entities.Charge;
import br.com.fiap.numberone.billing.payment.domain.entities.PaymentAttempt;
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
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

public class MercadoPagoCheckoutGateway implements CheckoutGateway {
    private static final int MAX_RESPONSE_BYTES = 65536;
    private final RestClient restClient;
    private final MercadoPagoOrderMapper mapper;
    private final MercadoPagoProperties properties;
    private final MercadoPagoSellerIdentityVerifier sellerIdentity;

    public MercadoPagoCheckoutGateway(RestClient restClient, MercadoPagoOrderMapper mapper, MercadoPagoProperties properties,
                                      MercadoPagoSellerIdentityVerifier sellerIdentity) {
        this.restClient = restClient;
        this.mapper = mapper;
        this.properties = properties;
        this.sellerIdentity = Objects.requireNonNull(sellerIdentity);
    }

    @Override
    public ValidatedCheckout validateSeller() {
        if (!properties.enabled()) throw new CheckoutCreationException(Kind.DISABLED, null, false, null);
        String validatedCredential = sellerIdentity.verifyExpectedSeller();
        AtomicBoolean consumed = new AtomicBoolean();
        return (charge, attemptId) -> {
            if (!consumed.compareAndSet(false, true)) throw new IllegalStateException("Operação de criação validada já utilizada");
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new SellerIdentityVerificationException(SellerIdentityVerificationException.Reason.ACTIVE_TRANSACTION, null, null);
            }
            return createWithValidatedCredential(charge, attemptId, validatedCredential);
        };
    }

    private CheckoutOrder createWithValidatedCredential(Charge charge, UUID attemptId, String validatedCredential) {
        var observation = new IntegrationObservation("MERCADO_PAGO", "ORDER_CREATE");
        observation.identifier("charge.id", charge == null ? null : charge.getChargeId());
        observation.identifier("payment_attempt.id", attemptId);
        observation.identifier("service_order.id", charge == null ? null : charge.getServiceOrderId());
        try {
            PaymentAttempt attempt = mapper.requireAttempt(charge, attemptId);
            byte[] request = mapper.toRequest(charge, attempt);
            byte[] response = postOrder(request, attempt, validatedCredential, observation);
            CheckoutOrder order = toOrder(response, charge, attempt);
            observation.identifier("provider_order.id", order.providerOrderId());
            return order;
        } catch (RuntimeException exception) {
            RuntimeException failure = translateFailure(exception);
            observation.failed(failure, failure instanceof CheckoutCreationException known ? known.getKind().name() : "PROCESSING_FAILED");
            throw failure;
        } finally {
            observation.close();
        }
    }

    private byte[] postOrder(byte[] request, PaymentAttempt attempt, String validatedCredential, IntegrationObservation observation) {
        return restClient.post().uri("/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validatedCredential)
                .header("X-Idempotency-Key", attempt.getIdempotencyKey().toString())
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).body(request)
                .exchange((httpRequest, httpResponse) -> readResponse(httpResponse, observation));
    }

    private RuntimeException translateFailure(RuntimeException failure) {
        return switch (failure) {
            case ResourceAccessException communication -> new CheckoutCreationException(
                    isTimeout(communication) ? Kind.TIMEOUT : Kind.COMMUNICATION, null, true, communication);
            case RestClientException communication -> new CheckoutCreationException(Kind.COMMUNICATION, null, true, communication);
            default -> failure;
        };
    }

    private byte[] readResponse(ClientHttpResponse response, IntegrationObservation observation) throws IOException {
        int status = response.getStatusCode().value();
        observation.status(status);
        if (status < 200 || status >= 300) throw httpFailure(status);
        byte[] body = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
        if (body.length > MAX_RESPONSE_BYTES) {
            throw new CheckoutCreationException(Kind.INVALID_RESPONSE, status, true,
                    new IllegalArgumentException("Resposta da order excede o limite local"));
        }
        return body;
    }

    private CheckoutOrder toOrder(byte[] response, Charge charge, PaymentAttempt attempt) {
        try {
            return mapper.toResult(response, charge, attempt);
        } catch (IllegalArgumentException exception) {
            throw new CheckoutCreationException(Kind.INVALID_RESPONSE, null, true, exception);
        }
    }

    private static CheckoutCreationException httpFailure(int status) {
        Kind kind = switch (status) {
            case 400, 422 -> Kind.REQUEST_REJECTED;
            case 401, 403 -> Kind.AUTHORIZATION;
            case 408 -> Kind.TIMEOUT;
            case 409 -> Kind.IDEMPOTENCY_CONFLICT;
            case 429 -> Kind.RATE_LIMIT;
            default -> status >= 500 ? Kind.PROVIDER_FAILURE : status >= 400 ? Kind.OTHER_REJECTION : Kind.INVALID_RESPONSE;
        };
        boolean unknown = kind != Kind.REQUEST_REJECTED && kind != Kind.AUTHORIZATION && kind != Kind.OTHER_REJECTION;
        // Não conservar corpo/headers de erro: podem conter credenciais ou dados privados.
        RestClientResponseException cause = new RestClientResponseException("Resposta HTTP do provedor de checkout",
                status, "", null, new byte[0], StandardCharsets.UTF_8);
        return new CheckoutCreationException(kind, status, unknown, cause);
    }

    private static boolean isTimeout(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (failure != null && visited.add(failure)) {
            if (failure instanceof HttpTimeoutException || failure instanceof SocketTimeoutException) {
                return true;
            }
            failure = failure.getCause();
        }
        return false;
    }
}
