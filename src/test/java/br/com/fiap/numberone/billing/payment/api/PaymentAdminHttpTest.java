package br.com.fiap.numberone.billing.payment.api;

import br.com.fiap.numberone.billing.infrastructure.observability.support.FailingLogAppender;
import br.com.fiap.numberone.billing.payment.api.controllers.PaymentAdminController;
import br.com.fiap.numberone.billing.payment.api.exceptions.PaymentExceptionHandler;
import br.com.fiap.numberone.billing.payment.api.mappers.PaymentApiMapper;
import br.com.fiap.numberone.billing.payment.application.exceptions.*;
import br.com.fiap.numberone.billing.payment.application.results.ChargeCheckoutResult;
import br.com.fiap.numberone.billing.payment.application.results.CheckoutResult;
import br.com.fiap.numberone.billing.payment.application.usecases.CreateCheckoutUseCase;
import br.com.fiap.numberone.billing.payment.application.usecases.GetChargeCheckoutUseCase;
import br.com.fiap.numberone.billing.payment.domain.enums.PaymentAttemptStatus;
import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentStateException;
import br.com.fiap.numberone.billing.payment.domain.exceptions.PaymentIdempotencyConflictException;
import br.com.fiap.numberone.billing.payment.domain.exceptions.ServiceOrderNotCompletedException;
import br.com.fiap.numberone.billing.payment.infrastructure.rest.SellerIdentityVerificationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PaymentAdminHttpTest {
    private final CreateCheckoutUseCase create = mock(CreateCheckoutUseCase.class);
    private final GetChargeCheckoutUseCase query = mock(GetChargeCheckoutUseCase.class);
    private final UUID orderId = UUID.randomUUID();
    private final UUID key = UUID.randomUUID();
    private final UUID chargeId = UUID.randomUUID();
    private final UUID attemptId = UUID.randomUUID();
    private final String url = "https://www.mercadopago.com.br/checkout/v1/redirect?pref_id=private-checkout-test";
    private MockMvc mvc;

    @BeforeEach
    void setUp() { mvc = mvc(); }

    @Test
    void shouldCreateAndReplayUsingConsumerKeyAndPortugueseFields() throws Exception {
        when(create.execute(orderId, key)).thenReturn(result(PaymentAttemptStatus.PENDING, url));
        for (int index = 0; index < 2; index++) {
            mvc.perform(post(path()).header("Idempotency-Key", key)).andExpect(status().isOk())
                    .andExpect(header().string("Location", "http://localhost" + path()))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.idCobranca").value(chargeId.toString()))
                    .andExpect(jsonPath("$.idTentativa").value(attemptId.toString()))
                    .andExpect(jsonPath("$.idOrdemProvedor").value("order-test"))
                    .andExpect(jsonPath("$.urlCheckout").value(url))
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.situacao").value("DISPONIVEL"))
                    .andExpect(jsonPath("$.idempotencyKey").doesNotExist());
        }
        verify(create, times(2)).execute(orderId, key);
        verifyNoInteractions(query);
    }

    @ParameterizedTest
    @CsvSource({"PENDING,EM_PROCESSAMENTO", "UNKNOWN,RESULTADO_DESCONHECIDO"})
    void shouldReturnControlledAcceptedState(PaymentAttemptStatus status, String situation) throws Exception {
        when(create.execute(orderId, key)).thenReturn(result(status, null));
        mvc.perform(post(path()).header("Idempotency-Key", key)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.situacao").value(situation)).andExpect(jsonPath("$.urlCheckout").isEmpty());
    }

    @Test
    void shouldReadChargeAndCheckoutWithoutExecutingCreation() throws Exception {
        when(query.execute(orderId)).thenReturn(new ChargeCheckoutResult(chargeId, orderId, new BigDecimal("530.12"),
                "BRL", "OPEN", result(PaymentAttemptStatus.PENDING, url)));
        mvc.perform(get(path())).andExpect(status().isOk())
                .andExpect(jsonPath("$.idOrdemServico").value(orderId.toString()))
                .andExpect(jsonPath("$.valor").value(530.12)).andExpect(jsonPath("$.moeda").value("BRL"))
                .andExpect(jsonPath("$.checkout.urlCheckout").value(url)).andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(create);
    }

    @Test
    void shouldReadChargeWithNoAttempts() throws Exception {
        when(query.execute(orderId)).thenReturn(new ChargeCheckoutResult(chargeId, orderId, BigDecimal.TEN, "BRL", "OPEN", null));
        mvc.perform(get(path())).andExpect(status().isOk()).andExpect(jsonPath("$.checkout").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-uuid", "123", "1-1-1-1-1", "00000000-0000-0000-0000-invalid"})
    void shouldRejectInvalidServiceOrderId(String invalidId) throws Exception {
        mvc.perform(post(path().replace(orderId.toString(), invalidId)).header("Idempotency-Key", key))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("ENTRADA_INVALIDA"));
        mvc.perform(get(path().replace(orderId.toString(), invalidId))).andExpect(status().isBadRequest());
        verifyNoInteractions(create, query);
    }

    @Test
    void shouldRejectMissingKey() throws Exception {
        mvc.perform(post(path())).andExpect(status().isBadRequest());
        verifyNoInteractions(create);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid-key", "1-1-1-1-1", "00000000-0000-0000-0000-000000000000,00000000-0000-0000-0000-000000000001"})
    void shouldRejectInvalidOrCombinedKey(String invalidKey) throws Exception {
        mvc.perform(post(path()).header("Idempotency-Key", invalidKey)).andExpect(status().isBadRequest());
        verifyNoInteractions(create);
    }

    @Test
    void shouldRejectDuplicateKeys() throws Exception {
        mvc.perform(post(path()).header("Idempotency-Key", key.toString(), key.toString())).andExpect(status().isBadRequest());
        verifyNoInteractions(create);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"valor\":0.01}", "{\"moeda\":\"USD\",\"status\":\"COMPLETED\"}", "{\"idOrcamento\":\"arbitrary\"}"})
    void shouldRejectAnyRequestBody(String body) throws Exception {
        mvc.perform(post(path()).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(create);
    }

    @Test
    void shouldExecuteBothOperationsWithoutAdministrativeContext() throws Exception {
        when(create.execute(orderId, key)).thenReturn(result(PaymentAttemptStatus.PENDING, url));
        when(query.execute(orderId)).thenReturn(new ChargeCheckoutResult(chargeId, orderId, BigDecimal.TEN,
                "BRL", "OPEN", result(PaymentAttemptStatus.PENDING, url)));
        mvc.perform(post(path()).header("Idempotency-Key", key)).andExpect(status().isOk());
        mvc.perform(get(path())).andExpect(status().isOk());
        verify(create).execute(orderId, key);
        verify(query).execute(orderId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "context-only"})
    void shouldIgnoreAdministrativeContextIncludingRepeatedHeaders(String subject) throws Exception {
        when(create.execute(orderId, key)).thenReturn(result(PaymentAttemptStatus.PENDING, url));
        when(query.execute(orderId)).thenReturn(new ChargeCheckoutResult(chargeId, orderId, BigDecimal.TEN,
                "BRL", "OPEN", result(PaymentAttemptStatus.PENDING, url)));
        mvc.perform(post(path()).header("X-Authenticated-Subject", subject, "second").header("Idempotency-Key", key))
                .andExpect(status().isOk());
        mvc.perform(get(path()).header("X-Authenticated-Subject", subject, "second")).andExpect(status().isOk());
        verify(create).execute(orderId, key);
        verify(query).execute(orderId);
    }

    @ParameterizedTest
    @CsvSource({"NOT_FOUND,404,OS_NAO_ENCONTRADA", "NOT_COMPLETED,409,OS_NAO_CONCLUIDA",
            "NO_BUDGET,409,ORCAMENTO_APROVADO_INDISPONIVEL", "IDEMPOTENCY,409,CONFLITO_IDEMPOTENCIA",
            "STATE,409,CONFLITO_ESTADO_PAGAMENTO", "UNAVAILABLE,503,INTEGRACAO_OS_UNAVAILABLE",
            "TIMEOUT,504,INTEGRACAO_OS_TIMEOUT", "INVALID,502,INTEGRACAO_OS_INVALID_RESPONSE"})
    void shouldTranslateBusinessAndIntegrationFailures(String kind, int expectedStatus, String code) throws Exception {
        RuntimeException failure = switch (kind) {
            case "NOT_FOUND" -> new ServiceOrderNotFoundException();
            case "NOT_COMPLETED" -> new ServiceOrderNotCompletedException();
            case "NO_BUDGET" -> new ApprovedBudgetUnavailableException();
            case "IDEMPOTENCY" -> new PaymentIdempotencyConflictException();
            case "STATE" -> new InvalidPaymentStateException("untrusted detail");
            case "TIMEOUT" -> new ServiceOrderPaymentUnavailableException(ServiceOrderPaymentUnavailableException.Failure.TIMEOUT, null);
            case "INVALID" -> new ServiceOrderPaymentUnavailableException(ServiceOrderPaymentUnavailableException.Failure.INVALID_RESPONSE, null);
            default -> new ServiceOrderPaymentUnavailableException(new IllegalStateException("private"));
        };
        when(create.execute(orderId, key)).thenThrow(failure);
        mvc.perform(post(path()).header("Idempotency-Key", key)).andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.codigo").value(code)).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
    }

    @Test
    void shouldTranslateMissingCharge() throws Exception {
        when(query.execute(orderId)).thenThrow(new ChargeNotFoundException());
        mvc.perform(get(path())).andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("COBRANCA_NAO_ENCONTRADA"));
    }

    @ParameterizedTest
    @CsvSource({"DISABLED,CONFIGURATION,503,CHECKOUT_CONFIGURACAO_INDISPONIVEL",
            "AUTHORIZATION,CONFIGURATION,503,CHECKOUT_CONFIGURACAO_INDISPONIVEL",
            "TIMEOUT,INTEGRATION_UNAVAILABLE,504,CHECKOUT_TIMEOUT",
            "COMMUNICATION,INTEGRATION_UNAVAILABLE,503,CHECKOUT_INDISPONIVEL",
            "RATE_LIMIT,INTEGRATION_UNAVAILABLE,503,CHECKOUT_INDISPONIVEL",
            "PROVIDER_FAILURE,INTEGRATION_UNAVAILABLE,503,CHECKOUT_INDISPONIVEL",
            "INVALID_RESPONSE,INVALID_RESPONSE,502,CHECKOUT_RESPOSTA_INVALIDA"})
    @ExtendWith(OutputCaptureExtension.class)
    void shouldTranslatePreparationFailureUsingApprovedContractWithoutExposingDetails(
            CheckoutCreationException.Kind kind, String category, int expectedStatus, String code, CapturedOutput output) throws Exception {
        when(create.execute(orderId, key)).thenThrow(new CheckoutCreationException(kind, null, false,
                new IllegalStateException("private-credential " + url)));
        String body = mvc.perform(post(path()).header("Idempotency-Key", key))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.codigo").value(code))
                .andExpect(jsonPath("$.mensagem").isString())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body + output.getAll()).doesNotContain("private-credential", url, key.toString());
        assertThat(output.getAll()).contains("PAYMENT_CHECKOUT_PREPARATION_FAILED", category, kind.name(), "CHECKOUT_PREPARATION");
        verifyNoInteractions(query);
    }

    @ParameterizedTest
    @CsvSource({"DISABLED,503,CHECKOUT_CONFIGURACAO_INDISPONIVEL",
            "ACTIVE_TRANSACTION,500,FALHA_TECNICA", "AUTHORIZATION,503,CHECKOUT_CONFIGURACAO_INDISPONIVEL",
            "IDENTITY_MISMATCH,503,CHECKOUT_CONFIGURACAO_INDISPONIVEL",
            "INVALID_RESPONSE,502,CHECKOUT_RESPOSTA_INVALIDA", "TIMEOUT,504,CHECKOUT_TIMEOUT",
            "COMMUNICATION,503,CHECKOUT_INDISPONIVEL"})
    @ExtendWith(OutputCaptureExtension.class)
    void shouldPreserveTypedSellerFailureAndKeepUnexpectedTransactionFailureDistinct(
            SellerIdentityVerificationException.Reason reason, int expectedStatus, String code, CapturedOutput output) throws Exception {
        when(create.execute(orderId, key)).thenThrow(new SellerIdentityVerificationException(reason, null,
                new IllegalStateException("private-identity")));
        String body = mvc.perform(post(path()).header("Idempotency-Key", key))
                .andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.codigo").value(code))
                .andReturn().getResponse().getContentAsString();
        assertThat(body + output.getAll()).doesNotContain("private-identity");
        assertThat(output.getAll()).contains(reason == SellerIdentityVerificationException.Reason.ACTIVE_TRANSACTION
                ? "PAYMENT_REQUEST_FAILED" : "PAYMENT_CHECKOUT_PREPARATION_FAILED");
    }

    @ParameterizedTest
    @EnumSource(value = CheckoutCreationException.Kind.class, names = {"REQUEST_REJECTED", "IDEMPOTENCY_CONFLICT", "OTHER_REJECTION"})
    void shouldKeepUnclassifiedPreparationFailuresAsUnexpected(CheckoutCreationException.Kind kind) throws Exception {
        when(create.execute(orderId, key)).thenThrow(new CheckoutCreationException(kind, null, false, null));
        mvc.perform(post(path()).header("Idempotency-Key", key)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.codigo").value("FALHA_TECNICA"));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shouldReturnRecoverableIdentitiesAndOmitPrivateExceptionMessages(CapturedOutput output) throws Exception {
        when(create.execute(orderId, key)).thenThrow(new CheckoutOutcomeUnknownException(chargeId, attemptId,
                new IllegalStateException("private-token " + url)));
        String response = mvc.perform(post(path()).header("Idempotency-Key", key)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.codigo").value("RESULTADO_CHECKOUT_DESCONHECIDO"))
                .andExpect(jsonPath("$.idCobranca").value(chargeId.toString())).andReturn().getResponse().getContentAsString();
        assertThat(response + output.getAll()).doesNotContain("private-token", url, key.toString());
        assertThat(output.getAll()).contains("PAYMENT_CHECKOUT_RESULT_NOT_RECORDED", IllegalStateException.class.getName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEBUG", "TRACE"})
    @ExtendWith(OutputCaptureExtension.class)
    void shouldHideUnexpectedTechnicalDetails(String level, CapturedOutput output) throws Exception {
        ch.qos.logback.classic.Logger spring = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.springframework.web");
        ch.qos.logback.classic.Level previous = spring.getLevel();
        spring.setLevel(ch.qos.logback.classic.Level.valueOf(level));
        try {
            when(query.execute(orderId)).thenThrow(new IllegalStateException("private-credential"));
            String body = mvc.perform(get(path())).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.codigo").value("FALHA_TECNICA")).andReturn().getResponse().getContentAsString();
            assertThat(body + output.getAll()).doesNotContain("private-credential");
            assertThat(output.getAll()).contains(IllegalStateException.class.getName());
        } finally { spring.setLevel(previous); }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shouldOmitCheckoutUrlAndKeysFromSpringMvcTrace(CapturedOutput output) throws Exception {
        ch.qos.logback.classic.Logger spring = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.springframework.web");
        ch.qos.logback.classic.Level previous = spring.getLevel();
        spring.setLevel(ch.qos.logback.classic.Level.TRACE);
        try {
            when(create.execute(orderId, key)).thenReturn(result(PaymentAttemptStatus.PENDING, url));
            mvc.perform(post(path()).header("Idempotency-Key", key)).andExpect(status().isOk());
            when(query.execute(orderId)).thenReturn(new ChargeCheckoutResult(chargeId, orderId, BigDecimal.TEN, "BRL", "OPEN", result(PaymentAttemptStatus.PENDING, url)));
            mvc.perform(get(path())).andExpect(status().isOk());
            assertThat(output.getAll()).doesNotContain(url, key.toString(), "validated-employee");
        } finally { spring.setLevel(previous); }
    }

    private CheckoutResult result(PaymentAttemptStatus status, String checkoutUrl) {
        return new CheckoutResult(chargeId, attemptId, checkoutUrl == null ? null : "order-test", checkoutUrl, status);
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new PaymentAdminController(create, query, new PaymentApiMapper()))
                .setControllerAdvice(new PaymentExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter()).build();
    }

    private String path() { return "/api/admin/ordens-servico/" + orderId + "/pagamentos/checkout"; }

    @Test
    void shouldPreserveUnknownCheckoutResponseAndOriginalCauseWhenLoggingFails() throws Exception {
        var cause = new IllegalStateException("Confirmação local indisponível");
        var failure = new CheckoutOutcomeUnknownException(chargeId, attemptId, cause);
        when(create.execute(orderId, key)).thenThrow(failure);
        try (var logging = new FailingLogAppender(PaymentExceptionHandler.class)) {
            var result = mvc.perform(post(path()).header("Idempotency-Key", key))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.codigo").value("RESULTADO_CHECKOUT_DESCONHECIDO"))
                    .andExpect(jsonPath("$.idCobranca").value(chargeId.toString()))
                    .andExpect(jsonPath("$.idTentativa").value(attemptId.toString())).andReturn();
            assertThat(result.getResolvedException()).hasCause(failure);
            assertThat(failure.getCause()).isSameAs(cause);
            assertThat(logging.eventCodes()).containsExactly("PAYMENT_CHECKOUT_RESULT_NOT_RECORDED");
        }
        verify(create).execute(orderId, key);
        verifyNoInteractions(query);
    }

}
