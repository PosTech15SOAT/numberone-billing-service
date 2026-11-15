package br.com.fiap.numberone.billing.payment.api.controllers;

import br.com.fiap.numberone.billing.payment.api.exceptions.PaymentRequestException;
import br.com.fiap.numberone.billing.payment.application.results.OrderObservation;
import br.com.fiap.numberone.billing.payment.application.usecases.ObserveOrderWebhookUseCase;
import br.com.fiap.numberone.billing.payment.application.usecases.ConfirmPaymentUseCase;
import br.com.fiap.numberone.billing.payment.infrastructure.webhook.OrderWebhookIngress;
import jakarta.servlet.http.HttpServletRequest;
import br.com.fiap.numberone.billing.infrastructure.observability.HttpObservationFilter;
import br.com.fiap.numberone.billing.infrastructure.observability.ObservationLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static br.com.fiap.numberone.billing.payment.api.security.OrderWebhookRequestFilter.PATH;

@RestController
public class OrderWebhookController {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderWebhookController.class);
    private final ObserveOrderWebhookUseCase observe;
    private final ConfirmPaymentUseCase confirm;

    public OrderWebhookController(ObserveOrderWebhookUseCase observe, ConfirmPaymentUseCase confirm) {
        this.observe = observe;
        this.confirm = confirm;
    }

    @PostMapping(PATH)
    public ResponseEntity<Void> receive(HttpServletRequest request) {
        try {
            OrderWebhookIngress ingress = verifiedIngress(request);
            request.setAttribute(HttpObservationFilter.STAGE, "FINANCIAL_LOOKUP");
            OrderObservation result = observe.execute(ingress.providerOrderId());
            if (result.situation() == OrderObservation.Situation.UNKNOWN_ORDER) {
                return deferUntilLocalAssociation(request, result);
            }
            request.setAttribute(HttpObservationFilter.STAGE, "FINANCIAL_EVIDENCE");
            confirm.execute(result);
            request.setAttribute(HttpObservationFilter.STAGE, "OBSERVED_" + result.situation().name());
            recordObservation(result);
            return ResponseEntity.ok().build();
        } catch (RuntimeException failure) {
            throw new PaymentRequestException(failure);
        }
    }

    private OrderWebhookIngress verifiedIngress(HttpServletRequest request) {
        OrderWebhookIngress ingress = (OrderWebhookIngress) request.getAttribute(OrderWebhookIngress.ATTRIBUTE);
        if (ingress == null) throw new IllegalStateException("Fronteira autenticada da notificação indisponível");
        if (ingress.failure() != null) throw ingress.failure();
        return ingress;
    }

    private ResponseEntity<Void> deferUntilLocalAssociation(HttpServletRequest request, OrderObservation result) {
        request.setAttribute(HttpObservationFilter.STAGE, "WAITING_LOCAL_ASSOCIATION");
        request.setAttribute(HttpObservationFilter.ERROR, "ORDER_NOT_ASSOCIATED_LOCALLY");
        ObservationLog.safely(() -> LOGGER.atWarn().addKeyValue("event.code", "PAYMENT_ORDER_NOTIFICATION_DEFERRED")
                .addKeyValue("provider_order.id", result.providerOrderId())
                .addKeyValue("payment.observation", result.situation().name())
                .log("Notificação aguarda associação local da order"));
        return ResponseEntity.status(503).build();
    }

    private void recordObservation(OrderObservation result) {
        ObservationLog.safely(() -> (result.situation() == OrderObservation.Situation.INDETERMINATE ? LOGGER.atWarn() : LOGGER.atInfo())
                .addKeyValue("event.code", "PAYMENT_ORDER_NOTIFICATION_OBSERVED")
                .addKeyValue("provider_order.id", result.providerOrderId()).addKeyValue("service_order.id", result.serviceOrderId())
                .addKeyValue("charge.id", result.chargeId()).addKeyValue("payment_attempt.id", result.attemptId())
                .addKeyValue("payment.observation", result.situation().name())
                .log("Notificação consultada e processada"));
    }
}
