package br.com.fiap.numberone.billing.payment.api;

import br.com.fiap.numberone.billing.infrastructure.observability.support.FailingLogAppender;
import org.junit.jupiter.params.provider.CsvSource;
import br.com.fiap.numberone.billing.payment.api.controllers.OrderWebhookController;
import br.com.fiap.numberone.billing.payment.api.exceptions.OrderWebhookExceptionHandler;
import br.com.fiap.numberone.billing.payment.api.security.OrderWebhookRequestFilter;
import br.com.fiap.numberone.billing.payment.application.exceptions.OrderLookupException;
import br.com.fiap.numberone.billing.payment.application.results.OrderObservation;
import br.com.fiap.numberone.billing.payment.application.usecases.ObserveOrderWebhookUseCase;
import br.com.fiap.numberone.billing.payment.application.usecases.ConfirmPaymentUseCase;
import br.com.fiap.numberone.billing.payment.infrastructure.config.MercadoPagoWebhookProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static br.com.fiap.numberone.billing.payment.support.MercadoPagoWebhookFixtures.SECRET;
import static br.com.fiap.numberone.billing.payment.support.MercadoPagoWebhookFixtures.signature;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OrderWebhookHttpTest {
    private final ObserveOrderWebhookUseCase observe = mock(ObserveOrderWebhookUseCase.class);
    private final ConfirmPaymentUseCase confirm = mock(ConfirmPaymentUseCase.class);
    private MockMvc mvc;

    @BeforeEach void setUp() {
        mvc = mvc(true);
        when(observe.execute("ORD01ABC")).thenReturn(new OrderObservation("ORD01ABC", null, null, null, OrderObservation.Situation.PENDING, null));
    }

    @Test
    void shouldReceiveAuthenticOrderWithoutAdministrativeIdentityAndReturnEmptyAcknowledgment() throws Exception {
        for (int index = 0; index < 2; index++) mvc.perform(valid()).andExpect(status().isOk()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(observe, times(2)).execute("ORD01ABC");
    }

    @Test
    void shouldDeferRepeatedUnknownOrderWithoutConfirmingOrExposingLocalDetails() throws Exception {
        when(observe.execute("ORD01ABC")).thenReturn(new OrderObservation("ORD01ABC", null, null, null,
                OrderObservation.Situation.UNKNOWN_ORDER, null));
        for (int index = 0; index < 2; index++) {
            mvc.perform(valid()).andExpect(status().isServiceUnavailable()).andExpect(content().string(""))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        verify(observe, times(2)).execute("ORD01ABC");
        verifyNoInteractions(confirm);
    }

    @Test
    void shouldRejectInvalidOrMissingSignatureBeforeApplication() throws Exception {
        mvc.perform(valid().header("x-signature", "duplicate")).andExpect(status().isUnauthorized());
        mvc.perform(post(OrderWebhookRequestFilter.PATH).queryParam("data.id", "ORD01ABC").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(OrderWebhookRequestFilter.PATH).queryParam("data.id", "ORD01ABC").contentType(MediaType.APPLICATION_JSON)
                        .header("x-signature", "ts=1,v1=" + "0".repeat(64)).content(body())).andExpect(status().isUnauthorized());
        verifyNoInteractions(observe);
    }

    @Test
    void shouldValidateUppercaseTestOrderFromQueryBeforeProcessingBody() throws Exception {
        String orderId = "ORDTST01ABC";
        when(observe.execute(orderId)).thenReturn(new OrderObservation(orderId, null, null, null, OrderObservation.Situation.PENDING, null));
        mvc.perform(post(OrderWebhookRequestFilter.PATH).queryParam("data.id", orderId).contentType(MediaType.APPLICATION_JSON)
                .header("x-request-id", "request-123").header("x-signature", signature(orderId, "request-123", "1"))
                .content(body().replace("ORD01ABC", orderId))).andExpect(status().isOk());
        verify(observe).execute(orderId);
    }

    @Test
    void shouldRejectSignatureForBodyIdWhenQueryContainsDifferentId() throws Exception {
        mvc.perform(post(OrderWebhookRequestFilter.PATH).queryParam("data.id", "ORDTST01ABC").contentType(MediaType.APPLICATION_JSON)
                .header("x-request-id", "request-123").header("x-signature", signature("ORD01ABC", "request-123", "1"))
                .content(body())).andExpect(status().isUnauthorized());
        verifyNoInteractions(observe, confirm);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ORDTST01ABC", "OrDtSt01AbC"})
    void shouldPreserveOriginalBusinessIdAndDuplicateDeliveryWhenSignedInLowercase(String orderId) throws Exception {
        when(observe.execute(orderId)).thenReturn(new OrderObservation(orderId, null, null, null,
                OrderObservation.Situation.PENDING, null));
        for (int index = 0; index < 2; index++) {
            mvc.perform(post(OrderWebhookRequestFilter.PATH).queryParam("data.id", orderId)
                    .contentType(MediaType.APPLICATION_JSON).header("x-request-id", "request-123")
                    .header("x-signature", signature(orderId.toLowerCase(java.util.Locale.ROOT), "request-123", "1"))
                    .content(body().replace("ORD01ABC", orderId))).andExpect(status().isOk());
        }
        verify(observe, times(2)).execute(orderId);
        verify(confirm, times(2)).execute(any());
    }

    @Test
    void shouldRejectMissingOrDuplicatedSignedIdentifier() throws Exception {
        mvc.perform(post(OrderWebhookRequestFilter.PATH).contentType(MediaType.APPLICATION_JSON)
                .header("x-signature", signature("ORD01ABC", "request-123", "1")).content(body())).andExpect(status().isUnauthorized());
        mvc.perform(valid().queryParam("data.id", "other")).andExpect(status().isUnauthorized());
        verifyNoInteractions(observe);
    }

    @Test
    void shouldRejectMissingRequestIdWhenSignatureIncludedIt() throws Exception {
        mvc.perform(post(OrderWebhookRequestFilter.PATH).queryParam("data.id", "ORD01ABC").contentType(MediaType.APPLICATION_JSON)
                .header("x-signature", signature("ORD01ABC", "request-123", "1")).content(body())).andExpect(status().isUnauthorized());
        verifyNoInteractions(observe);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "private-json-malformed", "[]", "{}", "null", "{\"type\":\"payment\"}",
            "{\"type\":\"order\",\"live_mode\":true,\"data\":{\"id\":\"ORD01ABC\"}}",
            "{\"type\":\"order\",\"live_mode\":false,\"data\":{\"id\":\"other\"}}",
            "{\"type\":\"order\",\"live_mode\":\"false\",\"data\":{\"id\":\"ORD01ABC\"}}"})
    void shouldRejectInvalidOrProductionPayload(String payload) throws Exception {
        mvc.perform(valid().content(payload)).andExpect(status().isBadRequest());
        verifyNoInteractions(observe);
    }

    @Test
    void shouldRejectOversizedBodyOrWrongTopicAndContentType() throws Exception {
        mvc.perform(valid().content("x".repeat(65537))).andExpect(status().isPayloadTooLarge());
        mvc.perform(valid().queryParam("type", "payment")).andExpect(status().isBadRequest());
        mvc.perform(valid().contentType(MediaType.TEXT_PLAIN)).andExpect(status().isBadRequest());
        verifyNoInteractions(observe);
    }

    @ParameterizedTest
    @CsvSource({"BLOCKED,503", "TIMEOUT,504", "COMMUNICATION,503", "NOT_FOUND,503", "INVALID_RESPONSE,502", "PROVIDER_FAILURE,503"})
    void shouldNotAcknowledgeLookupFailureAsFinancialSuccess(OrderLookupException.Kind kind, int status) throws Exception {
        when(observe.execute("ORD01ABC")).thenThrow(new OrderLookupException(kind, null, new IllegalStateException("private-external-token")));
        mvc.perform(valid()).andExpect(status().is(status)).andExpect(jsonPath("$.codigo").value("CONSULTA_ORDER_" + kind.name()));
    }

    @Test
    void shouldBlockDisabledIngressWithoutCallingApplication() throws Exception {
        mvc(false).perform(valid()).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(observe);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEBUG", "TRACE"})
    @ExtendWith(OutputCaptureExtension.class)
    void shouldHideSignatureBodyQueryAndTechnicalMessagesFromMvc(String level, CapturedOutput output) throws Exception {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.springframework.web");
        ch.qos.logback.classic.Level previous = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.valueOf(level));
        String signed = signature("ORD01ABC", "request-123", "1");
        try {
            mvc.perform(valid().queryParam("secret", "private-query-value")).andExpect(status().isOk());
            doThrow(new IllegalStateException("private-external-token")).when(observe).execute("ORD01ABC");
            mvc.perform(valid()).andExpect(status().isInternalServerError());
            assertThat(output.getAll()).doesNotContain(signed, SECRET, "private-query-value", "private@example.test", "private-external-token");
            assertThat(output.getAll()).contains(IllegalStateException.class.getName());
        } finally { logger.setLevel(previous); }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shouldNotAcknowledgeConfirmationPersistenceFailureOrExposeItsMessage(CapturedOutput output) throws Exception {
        doThrow(new IllegalStateException("private-financial-payload-token")).when(confirm).execute(any());
        mvc.perform(valid()).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.codigo").value("FALHA_TECNICA_NOTIFICACAO"));
        assertThat(output.getAll()).doesNotContain("private-financial-payload-token");
        verify(observe).execute("ORD01ABC");
        verify(confirm).execute(any());
    }

    private MockMvc mvc(boolean enabled) {
        return MockMvcBuilders.standaloneSetup(new OrderWebhookController(observe, confirm)).setControllerAdvice(new OrderWebhookExceptionHandler())
                .addFilters(new OrderWebhookRequestFilter(new MercadoPagoWebhookProperties(enabled, "TEST", SECRET),
                        new br.com.fiap.numberone.billing.payment.infrastructure.webhook.MercadoPagoSignatureValidator(SECRET)))
                .setMessageConverters(new JacksonJsonHttpMessageConverter()).build();
    }
    private MockHttpServletRequestBuilder valid() {
        return post(OrderWebhookRequestFilter.PATH).queryParam("data.id", "ORD01ABC").contentType(MediaType.APPLICATION_JSON)
                .header("x-request-id", "request-123").header("x-signature", signature("ORD01ABC", "request-123", "1")).content(body());
    }
    private String body() {
        return "{\"type\":\"order\",\"live_mode\":false,\"action\":\"order.processed\",\"data\":{\"id\":\"ORD01ABC\",\"status\":\"approved\"},\"payer\":{\"email\":\"private@example.test\"}}";
    }

    @ParameterizedTest
    @CsvSource({"TIMEOUT,504", "INVALID_RESPONSE,502", "COMMUNICATION,503"})
    void shouldPreserveLookupHttpFailureWhenLoggingFails(OrderLookupException.Kind kind, int httpStatus) throws Exception {
        var failure = new OrderLookupException(kind, null, new IllegalStateException("Falha externa"));
        when(observe.execute("ORD01ABC")).thenThrow(failure);
        try (var logging = new FailingLogAppender(OrderWebhookExceptionHandler.class)) {
            var result = mvc.perform(valid()).andExpect(status().is(httpStatus))
                    .andExpect(jsonPath("$.codigo").value("CONSULTA_ORDER_" + kind.name())).andReturn();
            assertThat(result.getResolvedException()).hasCause(failure);
            assertThat(logging.eventCodes()).containsExactly("PAYMENT_ORDER_OBSERVATION_FAILED");
        }
        verify(observe).execute("ORD01ABC");
        verifyNoInteractions(confirm);
    }

    @Test
    void shouldPreserveUnexpectedHttpFailureWhenLoggingFails() throws Exception {
        var failure = new IllegalStateException("Falha funcional");
        when(observe.execute("ORD01ABC")).thenThrow(failure);
        try (var logging = new FailingLogAppender(OrderWebhookExceptionHandler.class)) {
            var result = mvc.perform(valid()).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.codigo").value("FALHA_TECNICA_NOTIFICACAO")).andReturn();
            assertThat(result.getResolvedException()).hasCause(failure);
            assertThat(logging.eventCodes()).containsExactly("PAYMENT_ORDER_NOTIFICATION_FAILED");
        }
        verify(observe).execute("ORD01ABC");
        verifyNoInteractions(confirm);
    }

}
