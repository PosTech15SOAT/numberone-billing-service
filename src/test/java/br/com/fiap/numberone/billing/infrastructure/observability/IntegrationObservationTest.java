package br.com.fiap.numberone.billing.infrastructure.observability;

import br.com.fiap.numberone.billing.infrastructure.observability.support.FailingLogAppender;
import br.com.fiap.numberone.billing.budget.infrastructure.rest.InventoryRestGateway;
import br.com.fiap.numberone.billing.budget.support.EcsLogCapture;
import br.com.fiap.numberone.billing.payment.infrastructure.rest.ServiceOrderPaymentMapper;
import br.com.fiap.numberone.billing.payment.infrastructure.rest.ServiceOrderPaymentRestGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class IntegrationObservationTest {
    @ParameterizedTest
    @CsvSource({"TIMEOUT,TIMEOUT", "CONNECTION,CONNECTION_FAILED", "INVALID,INVALID_RESPONSE", "PROCESSING,PROCESSING_FAILED"})
    void shouldDistinguishFailuresWithoutLoggingExceptionMessages(String kind, String expected) throws Exception {
        Throwable cause = switch (kind) {
            case "TIMEOUT" -> new ResourceAccessException("private-token", new SocketTimeoutException("private-secret"));
            case "CONNECTION" -> new ResourceAccessException("private-token", new ConnectException("private-secret"));
            default -> new IllegalStateException("private-card-payload");
        };
        try (var logs = new EcsLogCapture(IntegrationObservation.class)) {
            try (var observation = new IntegrationObservation("MERCADO_PAGO", "ORDER_GET")) {
                observation.failed(cause, kind.equals("INVALID") ? "INVALID_RESPONSE" : "PROCESSING_FAILED");
            }
            assertThat(logs.events().getLast().path("error").path("code").asText()).isEqualTo(expected);
            assertThat(logs.events().toString()).doesNotContain("private-");
        }
    }

    @Test
    void shouldObserveSellerAndOrderCreationUsingSimulatedHttpWithoutLoggingCredentials() throws Exception {
        var identityBuilder = RestClient.builder().baseUrl("http://identity.test");
        var identity = MockRestServiceServer.bindTo(identityBuilder).build();
        identity.expect(requestTo("http://identity.test/users/me")).andRespond(withSuccess("{\"id\":123}", MediaType.APPLICATION_JSON));
        var ordersBuilder = RestClient.builder().baseUrl("http://orders.test");
        var orders = MockRestServiceServer.bindTo(ordersBuilder).build();
        String url = "https://www.mercadopago.com.br/checkout/v1/redirect?pref_id=private-checkout";
        orders.expect(requestTo("http://orders.test/v1/orders")).andRespond(withSuccess(
                "{\"id\":\"ORD01ABC\",\"status\":\"created\",\"checkout_url\":\"" + url + "\"}", MediaType.APPLICATION_JSON));
        var properties = new br.com.fiap.numberone.billing.payment.infrastructure.config.MercadoPagoProperties(
                true, "TEST", "TEST-private-fictitious-token", null, null, "123");
        var seller = new br.com.fiap.numberone.billing.payment.infrastructure.rest.MercadoPagoSellerIdentityVerifier(identityBuilder.build(), properties);
        var gateway = new br.com.fiap.numberone.billing.payment.infrastructure.rest.MercadoPagoCheckoutGateway(ordersBuilder.build(),
                new br.com.fiap.numberone.billing.payment.infrastructure.rest.MercadoPagoOrderMapper(), properties, seller);
        var charge = br.com.fiap.numberone.billing.payment.domain.entities.Charge.create(
                new br.com.fiap.numberone.billing.payment.domain.references.ServiceOrderPaymentContext(UUID.randomUUID(), "COMPLETED"),
                UUID.randomUUID(), new java.math.BigDecimal("10.00"), java.util.Currency.getInstance("BRL"));
        var attempt = charge.startAttempt(UUID.randomUUID());
        var previous = org.slf4j.MDC.getCopyOfContextMap();
        String correlation = UUID.randomUUID().toString();
        org.slf4j.MDC.put("correlation.id", correlation);
        try (var logs = new EcsLogCapture(IntegrationObservation.class)) {
            assertThat(gateway.validateSeller().createCheckout(charge, attempt.getAttemptId()).providerOrderId()).isEqualTo("ORD01ABC");
            assertThat(logs.events()).hasSize(4);
            assertThat(logs.events()).allSatisfy(event -> assertThat(event.path("correlation").path("id").asText()).isEqualTo(correlation));
            var completed = logs.events().getLast();
            assertThat(completed.path("provider_order").path("id").asText()).isEqualTo("ORD01ABC");
            assertThat(completed.path("charge").path("id").asText()).isEqualTo(charge.getChargeId().toString());
            assertThat(logs.events().toString()).doesNotContain("private-fictitious-token", "private-checkout", attempt.getIdempotencyKey().toString());
        } finally { if (previous == null) org.slf4j.MDC.clear(); else org.slf4j.MDC.setContextMap(previous); }
        identity.verify();
        orders.verify();
    }

    @Test
    void shouldObserveActualOsAdapterAndPreserveReturnedContext() throws Exception {
        UUID id = UUID.randomUUID();
        var builder = RestClient.builder().baseUrl("http://os.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://os.test/internal/service-orders/" + id)).andRespond(withSuccess(
                "{\"serviceOrderId\":\"" + id + "\",\"status\":\"COMPLETED\"}", MediaType.APPLICATION_JSON));
        try (var logs = new EcsLogCapture(IntegrationObservation.class)) {
            var result = new ServiceOrderPaymentRestGateway(builder.build(), new ServiceOrderPaymentMapper()).findById(id);
            assertThat(result.serviceOrderId()).isEqualTo(id);
            assertThat(result.status()).isEqualTo("COMPLETED");
            assertThat(logs.events()).hasSize(2);
            var completed = logs.events().getLast();
            assertThat(completed.path("operation").path("name").asText()).isEqualTo("PAYMENT_CONTEXT_GET");
            assertThat(completed.path("http").path("response").path("status_code").asInt()).isEqualTo(200);
            assertThat(completed.path("service_order").path("id").asText()).isEqualTo(id.toString());
        }
        server.verify();
    }

    @Test
    void shouldRetainHttpErrorStatusFromInventoryWithoutLoggingResponseBody() throws Exception {
        var builder = RestClient.builder().baseUrl("http://inventory.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://inventory.test/internal/inventory/consultar-precos"))
                .andRespond(withServerError().body("private-inventory-token"));
        try (var logs = new EcsLogCapture(IntegrationObservation.class)) {
            assertThatThrownBy(() -> new InventoryRestGateway(builder.build()).findByInventoryItemIds(Set.of(UUID.randomUUID())))
                    .isInstanceOf(br.com.fiap.numberone.billing.budget.application.exceptions.ExternalServiceException.class);
            var failure = logs.events().getLast();
            assertThat(failure.path("error").path("code").asText()).isEqualTo("HTTP_ERROR");
            assertThat(failure.path("http").path("response").path("status_code").asInt()).isEqualTo(500);
            assertThat(logs.events().toString()).doesNotContain("private-inventory-token");
        }
        server.verify();
    }
    @Test
    void shouldLogExpectedOsNotFoundAsWarningWithOriginalHttpStatus() throws Exception {
        UUID id = UUID.randomUUID();
        var builder = RestClient.builder().baseUrl("http://os.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://os.test/internal/service-orders/" + id + "/quotation-context"))
                .andRespond(withResourceNotFound().body("private-provider-body"));
        try (var logs = new EcsLogCapture(IntegrationObservation.class)) {
            assertThatThrownBy(() -> new br.com.fiap.numberone.billing.budget.infrastructure.rest.ServiceOrderRestGateway(
                    builder.build()).findByServiceOrderId(id))
                    .isInstanceOf(br.com.fiap.numberone.billing.budget.application.exceptions.ServiceOrderNotFoundException.class);
            var failure = logs.events().getLast();
            assertThat(failure.path("http").path("response").path("status_code").asInt()).isEqualTo(404);
            assertThat(failure.path("log").path("level").asText()).isEqualTo("WARN");
            assertThat(failure.path("error").path("code").asText()).isEqualTo("HTTP_ERROR");
            assertThat(logs.events().toString()).doesNotContain("private-provider-body");
        }
        server.verify();
    }

    @Test
    void shouldPreserveSuccessfulResultWhenStartAndCompletionLogsFail() {
        try (var logging = new FailingLogAppender(IntegrationObservation.class)) {
            java.util.function.Supplier<String> operation = () -> {
                try (var observation = new IntegrationObservation("OS_SERVICE", "PAYMENT_CONTEXT_GET")) {
                    observation.status(200);
                    return "Resultado confirmado";
                }
            };
            assertThat(operation.get()).isEqualTo("Resultado confirmado");
            assertThat(logging.eventCodes()).containsExactly("INTEGRATION_STARTED", "INTEGRATION_COMPLETED");
        }
    }

    @Test
    void shouldPreserveOriginalExceptionAndCauseWhenObservationLogsFail() {
        var cause = new SocketTimeoutException("Falha externa");
        var failure = new IllegalStateException("Falha funcional", cause);
        try (var logging = new FailingLogAppender(IntegrationObservation.class)) {
            assertThatThrownBy(() -> {
                try (var observation = new IntegrationObservation("OS_SERVICE", "PAYMENT_CONTEXT_GET")) {
                    observation.status(504);
                    observation.failed(failure, "TIMEOUT");
                    throw failure;
                }
            }).isSameAs(failure).hasCause(cause);
            assertThat(failure.getSuppressed()).isEmpty();
            assertThat(logging.eventCodes()).containsExactly("INTEGRATION_STARTED", "INTEGRATION_FAILED");
        }
    }

}
