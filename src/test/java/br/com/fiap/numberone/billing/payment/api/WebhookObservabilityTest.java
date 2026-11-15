package br.com.fiap.numberone.billing.payment.api;

import br.com.fiap.numberone.billing.budget.support.EcsLogCapture;
import br.com.fiap.numberone.billing.infrastructure.observability.HttpObservationFilter;
import br.com.fiap.numberone.billing.payment.api.controllers.OrderWebhookController;
import br.com.fiap.numberone.billing.payment.api.exceptions.OrderWebhookExceptionHandler;
import br.com.fiap.numberone.billing.payment.api.security.OrderWebhookRequestFilter;
import br.com.fiap.numberone.billing.payment.application.results.OrderObservation;
import br.com.fiap.numberone.billing.payment.application.usecases.ConfirmPaymentUseCase;
import br.com.fiap.numberone.billing.payment.application.usecases.ObserveOrderWebhookUseCase;
import br.com.fiap.numberone.billing.payment.infrastructure.config.MercadoPagoWebhookProperties;
import br.com.fiap.numberone.billing.payment.infrastructure.webhook.MercadoPagoSignatureValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebhookObservabilityTest {
    private static final String SECRET = "private-hmac-secret";
    private final ObserveOrderWebhookUseCase observe = mock(ObserveOrderWebhookUseCase.class);
    private final ConfirmPaymentUseCase confirm = mock(ConfirmPaymentUseCase.class);

    @ParameterizedTest
    @CsvSource({"MISSING_SIGNATURE,401,SIGNATURE_HEADERS", "REPEATED_SIGNATURE,401,SIGNATURE_HEADERS",
            "REPEATED_REQUEST_ID,401,SIGNATURE_HEADERS", "MISSING_ORDER_ID,401,ORDER_ID",
            "REPEATED_ORDER_ID,401,ORDER_ID", "INVALID_SIGNATURE_FORMAT,401,HMAC",
            "SIGNATURE_MISMATCH,401,HMAC", "INVALID_NOTIFICATION,400,NOTIFICATION", "WEBHOOK_DISABLED,503,CONFIGURATION"})
    void shouldIdentifyRejectionStageAndKeepHttpContractWithoutSecrets(String reason, int status, String stage) throws Exception {
        String correlation = UUID.randomUUID().toString();
        var request = request().header("X-Correlation-Id", correlation);
        if (!reason.equals("MISSING_SIGNATURE")) request.header("x-signature", reason.equals("INVALID_SIGNATURE_FORMAT") ? "private-invalid-signature" :
                reason.equals("SIGNATURE_MISMATCH") ? "ts=1,v1=" + "0".repeat(64) : signature());
        if (reason.equals("REPEATED_SIGNATURE")) request.header("x-signature", "private-duplicate-signature");
        if (reason.equals("REPEATED_REQUEST_ID")) request.header("x-request-id", "private-request-one", "private-request-two");
        if (!reason.equals("MISSING_ORDER_ID")) request.queryParam("data.id", "ORD01ABC");
        if (reason.equals("REPEATED_ORDER_ID")) request.queryParam("data.id", "private-other-order");
        if (reason.equals("INVALID_NOTIFICATION")) request.content("{\"private-payload\":true}");
        try (var logs = new EcsLogCapture(OrderWebhookRequestFilter.class); var http = new EcsLogCapture(HttpObservationFilter.class)) {
            mvc(!reason.equals("WEBHOOK_DISABLED")).perform(request).andExpect(status().is(status));
            var rejection = logs.events().stream().filter(e -> e.path("event").path("code").asText().equals("PAYMENT_WEBHOOK_REJECTED")).toList();
            assertThat(rejection).hasSize(1);
            assertThat(rejection.getFirst().path("error").path("code").asText()).isEqualTo(reason);
            assertThat(rejection.getFirst().path("operation").path("stage").asText()).isEqualTo(stage);
            assertThat(rejection.getFirst().path("correlation").path("id").asText()).isEqualTo(correlation);
            var completed = http.events().getLast();
            assertThat(completed.path("http").path("response").path("status_code").asInt()).isEqualTo(status);
            assertThat(completed.path("error").path("code").asText()).isEqualTo(reason);
            assertThat(logs.events().toString() + http.events()).doesNotContain(SECRET, signature(), "private-", "id:ORD01ABC;");
        }
        verifyNoInteractions(observe, confirm);
    }

    @Test
    void shouldCorrelateDeferredUnknownOrderWithoutLoggingProcessingSuccess() throws Exception {
        when(observe.execute("ORD01ABC")).thenReturn(new OrderObservation("ORD01ABC", null, null, null,
                OrderObservation.Situation.UNKNOWN_ORDER, null));
        try (var logs = new EcsLogCapture(OrderWebhookController.class); var http = new EcsLogCapture(HttpObservationFilter.class)) {
            mvc(true).perform(request().queryParam("data.id", "ORD01ABC").header("x-signature", signature()))
                    .andExpect(status().isServiceUnavailable());
            var observed = logs.events().getFirst();
            assertThat(logs.events()).hasSize(1);
            assertThat(observed.path("event").path("code").asText()).isEqualTo("PAYMENT_ORDER_NOTIFICATION_DEFERRED");
            assertThat(observed.path("log").path("level").asText()).isEqualTo("WARN");
            assertThat(observed.path("provider_order").path("id").asText()).isEqualTo("ORD01ABC");
            assertThat(observed.path("payment").path("observation").asText()).isEqualTo("UNKNOWN_ORDER");
            assertThat(observed.path("correlation").path("id").asText())
                    .isEqualTo(http.events().getLast().path("correlation").path("id").asText()).isNotBlank();
            assertThat(http.events().getLast().path("error").path("code").asText()).isEqualTo("ORDER_NOT_ASSOCIATED_LOCALLY");
            assertThat(http.events().getLast().path("operation").path("stage").asText()).isEqualTo("WAITING_LOCAL_ASSOCIATION");
        }
        verifyNoInteractions(confirm);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void shouldIdentifyAcceptedStrategyWithoutLeakingSignatureOrChangingOrderId(boolean compatibility) throws Exception {
        when(observe.execute("ORD01ABC")).thenReturn(new OrderObservation("ORD01ABC", null, null, null,
                OrderObservation.Situation.PENDING, null));
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OrderWebhookRequestFilter.class);
        var previous = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        String correlation = UUID.randomUUID().toString();
        String signed = signature(compatibility ? "ord01abc" : "ORD01ABC");
        try (var logs = new EcsLogCapture(OrderWebhookRequestFilter.class)) {
            mvc(true).perform(request().queryParam("data.id", "ORD01ABC").header("x-signature", signed)
                    .header("X-Correlation-Id", correlation)).andExpect(status().isOk());
            var verified = logs.events().stream().filter(e -> e.path("event").path("code").asText()
                    .equals("PAYMENT_WEBHOOK_SIGNATURE_VERIFIED")).toList();
            assertThat(verified).hasSize(1);
            var event = verified.getFirst();
            assertThat(event.path("provider_order").path("id").asText()).isEqualTo("ORD01ABC");
            assertThat(event.path("correlation").path("id").asText()).isEqualTo(correlation);
            assertThat(event.path("webhook").path("signature_strategy").asText())
                    .isEqualTo(compatibility ? "LOWERCASE_COMPATIBILITY" : "ORIGINAL");
            assertThat(event.path("webhook").path("hmac_result").asText()).isEqualTo("VALID");
            assertThat(event.path("webhook").path("environment").asText()).isEqualTo("TEST");
            assertThat(logs.events().stream().filter(e -> e.path("event").path("code").asText()
                    .equals("PAYMENT_WEBHOOK_SIGNATURE_COMPATIBILITY_APPLIED")).count()).isEqualTo(compatibility ? 1 : 0);
            assertThat(logs.events().toString()).doesNotContain(SECRET, signed, "private-", "id:ORD01ABC;");
            assertThat(logs.events()).allSatisfy(log -> assertThat(log.has("diagnostic")).isFalse());
        } finally { logger.setLevel(previous); }
        verify(observe).execute("ORD01ABC");
        verify(confirm).execute(any());
    }

    private MockMvc mvc(boolean enabled) {
        return MockMvcBuilders.standaloneSetup(new OrderWebhookController(observe, confirm))
                .setControllerAdvice(new OrderWebhookExceptionHandler())
                .addFilters(new HttpObservationFilter(), new OrderWebhookRequestFilter(
                        new MercadoPagoWebhookProperties(enabled, "TEST", SECRET), new MercadoPagoSignatureValidator(SECRET))).build();
    }

    private MockHttpServletRequestBuilder request() {
        return post(OrderWebhookRequestFilter.PATH).contentType(MediaType.APPLICATION_JSON)
                .queryParam("private-query", "private-token").header("Authorization", "Bearer private-credential")
                .content("{\"type\":\"order\",\"live_mode\":false,\"action\":\"order.processed\",\"data\":{\"id\":\"ORD01ABC\"}}");
    }

    private String signature() throws Exception {
        return signature("ORD01ABC");
    }
    private String signature(String id) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "ts=1,v1=" + HexFormat.of().formatHex(mac.doFinal(("id:" + id + ";ts:1;").getBytes(StandardCharsets.UTF_8)));
    }
}
