package br.com.fiap.numberone.billing.payment.api.exceptions;

import br.com.fiap.numberone.billing.infrastructure.observability.ObservationLog;
import br.com.fiap.numberone.billing.budget.infrastructure.logging.SafeExceptionDetails;
import br.com.fiap.numberone.billing.payment.api.controllers.OrderWebhookController;
import br.com.fiap.numberone.billing.payment.api.dtos.PaymentErrorResponse;
import br.com.fiap.numberone.billing.payment.application.exceptions.OrderLookupException;
import br.com.fiap.numberone.billing.payment.infrastructure.webhook.InvalidOrderNotificationException;
import jakarta.servlet.http.HttpServletRequest;
import br.com.fiap.numberone.billing.infrastructure.observability.HttpObservationFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = OrderWebhookController.class)
public class OrderWebhookExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderWebhookExceptionHandler.class);

    @ExceptionHandler(PaymentRequestException.class)
    public ResponseEntity<PaymentErrorResponse> rejected(PaymentRequestException wrapper, HttpServletRequest request) {
        Throwable failure = wrapper.getCause();
        if (failure instanceof InvalidOrderNotificationException invalid) {
            return error(invalid.getStatus(), invalid.getStatus() == 401 ? "NOTIFICACAO_NAO_AUTENTICADA" : "NOTIFICACAO_INVALIDA");
        }
        if (failure instanceof OrderLookupException lookup) {
            return orderLookupFailed(lookup, request);
        }
        if (request.getAttribute(HttpObservationFilter.ERROR) == null) ObservationLog.safely(() -> LOGGER.atError().addKeyValue("event.code", "PAYMENT_ORDER_NOTIFICATION_FAILED")
                .addKeyValue("error.type", failure.getClass().getName()).addKeyValue("error.stack_trace", SafeExceptionDetails.stackTrace(failure))
                .log("Falha técnica na recepção da notificação"));
        return error(500, "FALHA_TECNICA_NOTIFICACAO");
    }

    private ResponseEntity<PaymentErrorResponse> orderLookupFailed(OrderLookupException failure, HttpServletRequest request) {
        boolean ingressFailure = request.getAttribute(HttpObservationFilter.ERROR) != null;
        if (!ingressFailure) request.setAttribute(HttpObservationFilter.ERROR, failure.getKind().name());
        int status = switch (failure.getKind()) {
            case TIMEOUT -> 504;
            case INVALID_RESPONSE -> 502;
            default -> 503;
        };
        if (!ingressFailure) ObservationLog.safely(() -> LOGGER.atWarn().addKeyValue("event.code", "PAYMENT_ORDER_OBSERVATION_FAILED")
                .addKeyValue("error.code", failure.getKind().name()).addKeyValue("error.type", failure.getClass().getName())
                .addKeyValue("error.stack_trace", SafeExceptionDetails.stackTrace(failure))
                .log("Consulta segura da order não concluída"));
        return error(status, "CONSULTA_ORDER_" + failure.getKind().name());
    }

    private ResponseEntity<PaymentErrorResponse> error(int status, String code) {
        return ResponseEntity.status(status).body(new PaymentErrorResponse(status, code, "Notificação não processada com segurança"));
    }
}
