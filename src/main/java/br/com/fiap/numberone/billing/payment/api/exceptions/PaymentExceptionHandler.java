package br.com.fiap.numberone.billing.payment.api.exceptions;

import br.com.fiap.numberone.billing.budget.infrastructure.logging.SafeExceptionDetails;
import br.com.fiap.numberone.billing.payment.api.controllers.PaymentAdminController;
import br.com.fiap.numberone.billing.payment.api.dtos.PaymentErrorResponse;
import br.com.fiap.numberone.billing.payment.application.exceptions.ApprovedBudgetUnavailableException;
import br.com.fiap.numberone.billing.payment.application.exceptions.ChargeNotFoundException;
import br.com.fiap.numberone.billing.payment.application.exceptions.CheckoutCreationException;
import br.com.fiap.numberone.billing.payment.application.exceptions.CheckoutOutcomeUnknownException;
import br.com.fiap.numberone.billing.payment.application.exceptions.PaymentPersistenceConflictException;
import br.com.fiap.numberone.billing.payment.application.exceptions.ServiceOrderNotFoundException;
import br.com.fiap.numberone.billing.payment.application.exceptions.ServiceOrderPaymentUnavailableException;
import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentDataException;
import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentStateException;
import br.com.fiap.numberone.billing.payment.domain.exceptions.PaymentIdempotencyConflictException;
import br.com.fiap.numberone.billing.payment.domain.exceptions.ServiceOrderNotCompletedException;
import br.com.fiap.numberone.billing.payment.infrastructure.rest.SellerIdentityVerificationException;
import br.com.fiap.numberone.billing.infrastructure.observability.ObservationLog;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.UUID;

@RestControllerAdvice(assignableTypes = PaymentAdminController.class)
public class PaymentExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PaymentExceptionHandler.class);

    @ExceptionHandler(PaymentRequestException.class)
    public ResponseEntity<PaymentErrorResponse> requestFailed(PaymentRequestException wrapper, HttpServletRequest request) {
        return switch (wrapper.getCause()) {
            case ServiceOrderNotFoundException ignored -> orderNotFound();
            case ChargeNotFoundException ignored -> chargeNotFound();
            case ServiceOrderNotCompletedException ignored -> notCompleted();
            case ApprovedBudgetUnavailableException ignored -> noBudget();
            case PaymentIdempotencyConflictException ignored -> idempotencyConflict();
            case PaymentPersistenceConflictException ignored -> idempotencyConflict();
            case InvalidPaymentStateException ignored -> stateConflict();
            case InvalidPaymentDataException ignored -> invalidInput();
            case ServiceOrderPaymentUnavailableException failure -> serviceOrderUnavailable(failure, request);
            case CheckoutOutcomeUnknownException failure -> unknown(failure, request);
            case CheckoutCreationException failure -> checkoutPreparationFailed(failure, request);
            default -> unexpected((Exception) wrapper.getCause(), request);
        };
    }

    @ExceptionHandler(ServiceOrderNotFoundException.class)
    public ResponseEntity<PaymentErrorResponse> orderNotFound() { return error(404, "OS_NAO_ENCONTRADA", "OS não encontrada"); }

    @ExceptionHandler(ChargeNotFoundException.class)
    public ResponseEntity<PaymentErrorResponse> chargeNotFound() { return error(404, "COBRANCA_NAO_ENCONTRADA", "Cobrança não encontrada"); }

    @ExceptionHandler(ServiceOrderNotCompletedException.class)
    public ResponseEntity<PaymentErrorResponse> notCompleted() { return error(409, "OS_NAO_CONCLUIDA", "Pagamento exige OS concluída"); }

    @ExceptionHandler(ApprovedBudgetUnavailableException.class)
    public ResponseEntity<PaymentErrorResponse> noBudget() { return error(409, "ORCAMENTO_APROVADO_INDISPONIVEL", "Checkout exige um único orçamento aprovado da mesma OS"); }

    @ExceptionHandler({PaymentIdempotencyConflictException.class, PaymentPersistenceConflictException.class})
    public ResponseEntity<PaymentErrorResponse> idempotencyConflict() { return error(409, "CONFLITO_IDEMPOTENCIA", "Dados da operação conflitam com uma identidade já registrada"); }

    @ExceptionHandler(InvalidPaymentStateException.class)
    public ResponseEntity<PaymentErrorResponse> stateConflict() { return error(409, "CONFLITO_ESTADO_PAGAMENTO", "Cobrança quitada, tentativa pendente ou transição incompatível; consulte a operação existente"); }

    @ExceptionHandler({InvalidPaymentDataException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<PaymentErrorResponse> invalidInput() { return error(400, "ENTRADA_INVALIDA", "Informe UUIDs válidos, uma única chave Idempotency-Key e nenhum corpo na solicitação"); }

    @ExceptionHandler(ServiceOrderPaymentUnavailableException.class)
    public ResponseEntity<PaymentErrorResponse> serviceOrderUnavailable(ServiceOrderPaymentUnavailableException failure, HttpServletRequest request) {
        int status = switch (failure.getFailure()) {
            case TIMEOUT -> 504;
            case INVALID_RESPONSE -> 502;
            case DISABLED, UNAVAILABLE -> 503;
        };
        ObservationLog.safely(() -> event(LOGGER.atWarn(), request, "PAYMENT_SERVICE_ORDER_LOOKUP_FAILED", failure)
                .addKeyValue("error.code", failure.getFailure().name()).log("Consulta confiável da OS indisponível"));
        return error(status, "INTEGRACAO_OS_" + failure.getFailure().name(), "Não foi possível consultar o estado confiável da OS");
    }

    @ExceptionHandler(CheckoutOutcomeUnknownException.class)
    public ResponseEntity<PaymentErrorResponse> unknown(CheckoutOutcomeUnknownException failure, HttpServletRequest request) {
        ObservationLog.safely(() -> event(LOGGER.atError(), request, "PAYMENT_CHECKOUT_RESULT_NOT_RECORDED", failure)
                .addKeyValue("charge.id", failure.getChargeId()).addKeyValue("payment_attempt.id", failure.getAttemptId())
                .log("Falha ao concluir o registro local do checkout; reconciliação necessária"));
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(new PaymentErrorResponse(503,
                "RESULTADO_CHECKOUT_DESCONHECIDO", "Resultado não confirmado localmente; consulte a operação existente sem gerar nova chave",
                failure.getChargeId(), failure.getAttemptId()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<PaymentErrorResponse> unexpected(Exception failure, HttpServletRequest request) {
        ObservationLog.safely(() -> event(LOGGER.atError(), request, "PAYMENT_REQUEST_FAILED", failure).log("Falha técnica na operação de pagamento"));
        return error(500, "FALHA_TECNICA", "Não foi possível concluir a operação; consulte o estado existente antes de repetir");
    }

    @ExceptionHandler(CheckoutCreationException.class)
    public ResponseEntity<PaymentErrorResponse> checkoutPreparationFailed(CheckoutCreationException failure, HttpServletRequest request) {
        if (failure instanceof SellerIdentityVerificationException identity
                && identity.getReason() == SellerIdentityVerificationException.Reason.ACTIVE_TRANSACTION) {
            return unexpected(failure, request);
        }
        String category = switch (failure.getKind()) {
            case DISABLED, AUTHORIZATION -> "CONFIGURATION";
            case TIMEOUT, COMMUNICATION, RATE_LIMIT, PROVIDER_FAILURE -> "INTEGRATION_UNAVAILABLE";
            case INVALID_RESPONSE -> "INVALID_RESPONSE";
            default -> "UNEXPECTED";
        };
        if (category.equals("UNEXPECTED")) return unexpected(failure, request);
        ObservationLog.safely(() -> event(category.equals("CONFIGURATION") ? LOGGER.atError() : LOGGER.atWarn(), request,
                "PAYMENT_CHECKOUT_PREPARATION_FAILED", failure)
                .addKeyValue("error.category", category).addKeyValue("error.code", failure.getKind().name())
                .addKeyValue("error.reason", failure instanceof SellerIdentityVerificationException identity
                        ? identity.getReason().name() : failure.getKind().name())
                .addKeyValue("operation.stage", "CHECKOUT_PREPARATION")
                .log("Checkout bloqueado antes da reserva"));
        return switch (failure.getKind()) {
            case TIMEOUT -> error(504, "CHECKOUT_TIMEOUT", "Tempo limite na preparação do checkout");
            case COMMUNICATION, RATE_LIMIT, PROVIDER_FAILURE -> error(503, "CHECKOUT_INDISPONIVEL", "Integração de checkout temporariamente indisponível");
            case DISABLED, AUTHORIZATION -> error(503, "CHECKOUT_CONFIGURACAO_INDISPONIVEL", "Configuração de checkout indisponível");
            case INVALID_RESPONSE -> error(502, "CHECKOUT_RESPOSTA_INVALIDA", "Integração de checkout retornou resposta inválida");
            default -> unexpected(failure, request);
        };
    }

    private LoggingEventBuilder event(LoggingEventBuilder event, HttpServletRequest request, String code, Throwable failure) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (variables instanceof Map<?, ?> paths && paths.get("serviceOrderId") instanceof String id) {
            try { event.addKeyValue("service_order.id", UUID.fromString(id)); } catch (IllegalArgumentException ignored) { }
        }
        return event.addKeyValue("event.code", code).addKeyValue("error.type", failure.getClass().getName())
                .addKeyValue("error.stack_trace", SafeExceptionDetails.stackTrace(failure));
    }

    private ResponseEntity<PaymentErrorResponse> error(int status, String code, String message) {
        if (status >= 400 && status < 500) ObservationLog.safely(() -> LOGGER.atWarn()
                .addKeyValue("event.code", "PAYMENT_REQUEST_REJECTED").addKeyValue("error.code", code)
                .log("Operação de pagamento rejeitada"));
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new PaymentErrorResponse(status, code, message));
    }
}
