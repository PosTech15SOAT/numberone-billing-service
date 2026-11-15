package br.com.fiap.numberone.billing.budget.api.controllers;

import br.com.fiap.numberone.billing.budget.application.exceptions.BillingSagaConflict;
import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetNotFoundException;
import br.com.fiap.numberone.billing.budget.application.gateways.BillingSagaStore;
import br.com.fiap.numberone.billing.budget.application.gateways.BillingSagaStore.Cancellation;
import br.com.fiap.numberone.billing.budget.application.usecases.CloseBillingContext;
import br.com.fiap.numberone.billing.budget.domain.enums.BudgetStatus;
import br.com.fiap.numberone.billing.payment.application.exceptions.ServiceOrderNotFoundException;
import br.com.fiap.numberone.billing.payment.application.exceptions.ServiceOrderPaymentUnavailableException;
import br.com.fiap.numberone.billing.payment.application.gateways.ChargeGateway;
import br.com.fiap.numberone.billing.payment.domain.enums.ChargeStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static br.com.fiap.numberone.billing.infrastructure.observability.HttpObservationFilter.CORRELATION;
import org.slf4j.MDC;

@RestController
@RequestMapping("/internal")
public class BillingSagaController {
    private final BillingSagaStore budgets;
    private final ChargeGateway charges;
    private final CloseBillingContext cancellation;

    public BillingSagaController(BillingSagaStore budgets, ChargeGateway charges, CloseBillingContext cancellation) {
        this.budgets = budgets;
        this.charges = charges;
        this.cancellation = cancellation;
    }

    @GetMapping("/budgets/service-orders/{orderId}/current")
    public ResponseEntity<?> current(@PathVariable UUID orderId) {
        var budget = budgets.currentBudget(orderId).orElseThrow(BudgetNotFoundException::new);
        var composition = budget.getComposition();
        if (composition == null || composition.compositionVersion() == null || composition.compositionVersion() < 1) {
            return problem(503, "COMPOSITION_VERSION_UNAVAILABLE");
        }
        var amount = budget.getStatus() == BudgetStatus.APPROVED ? budget.getApprovedAmount() : budget.getQuotedAmount();
        return ResponseEntity.ok(Map.of("serviceOrderId", orderId, "budgetId", budget.getBudgetId(),
                "budgetVersion", budget.getBudgetVersion(), "compositionVersion", composition.compositionVersion(),
                "status", budget.getStatus(), "amount", amount.setScale(2).toPlainString(), "currency", "BRL"));
    }

    @GetMapping("/payments/service-orders/{orderId}/settlement")
    public Settlement settlement(@PathVariable UUID orderId) {
        UUID paymentId = charges.findByServiceOrderId(orderId)
                .filter(charge -> charge.getStatus() == ChargeStatus.PAID)
                .map(charge -> charge.getChargeId()).orElse(null);
        return new Settlement(orderId, paymentId != null, paymentId != null ? "PAID" : "UNSETTLED",
                paymentId, Instant.now());
    }

    @PostMapping("/budgets/{budgetId}/cancellation")
    public CancellationResponse cancelBudget(@PathVariable UUID budgetId, @RequestHeader("Idempotency-Key") UUID key,
                                              @RequestBody CancelBudgetRequest request) {
        validateRequest(key, request.operationId(), request.reason());
        if (request.budgetVersion() == null || request.budgetVersion() < 1) throw new InvalidCancellation();
        return CancellationResponse.from(cancellation.cancelBudget(key, budgetId, request.budgetVersion()));
    }

    @PostMapping("/budgets/service-orders/{orderId}/cancellation")
    public CancellationResponse cancelContext(@PathVariable UUID orderId, @RequestHeader("Idempotency-Key") UUID key,
                                               @RequestBody CancelContextRequest request) {
        validateRequest(key, request.operationId(), request.reason());
        return CancellationResponse.from(cancellation.execute(new Cancellation(key, orderId, null, null)));
    }

    private void validateRequest(UUID key, UUID operationId, String reason) {
        if (!key.equals(operationId) || !"SERVICE_ORDER_CANCELLED".equals(reason)) throw new InvalidCancellation();
    }

    @ExceptionHandler({InvalidCancellation.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, org.springframework.web.bind.MissingRequestHeaderException.class})
    ResponseEntity<?> invalid() { return problem(400, "INVALID_REQUEST"); }

    @ExceptionHandler({BudgetNotFoundException.class, ServiceOrderNotFoundException.class})
    ResponseEntity<?> notFound() { return problem(404, "NOT_FOUND"); }

    @ExceptionHandler(BillingSagaConflict.class)
    ResponseEntity<?> conflict(BillingSagaConflict error) { return problem(409, error.reason().name()); }

    @ExceptionHandler(ServiceOrderPaymentUnavailableException.class)
    ResponseEntity<?> unavailable(ServiceOrderPaymentUnavailableException error) {
        return error.getFailure() == ServiceOrderPaymentUnavailableException.Failure.TIMEOUT
                ? problem(504, "DEPENDENCY_TIMEOUT") : problem(503, "DEPENDENCY_UNAVAILABLE");
    }

    private ResponseEntity<?> problem(int status, String code) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", code, "correlationId", MDC.get(CORRELATION)));
    }

    private static class InvalidCancellation extends RuntimeException { }
    public record CancelBudgetRequest(UUID operationId, Integer budgetVersion, String reason) { }
    public record CancelContextRequest(UUID operationId, String reason) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Settlement(UUID serviceOrderId, boolean settled, String paymentStatus, UUID paymentId, Instant verifiedAt) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CancellationResponse(UUID operationId, UUID serviceOrderId, UUID budgetId, Integer budgetVersion, String result) {
        static CancellationResponse from(BillingSagaStore.ClosedContext receipt) {
            return new CancellationResponse(receipt.operationId(), receipt.serviceOrderId(),
                    receipt.budgetId(), receipt.budgetVersion(), receipt.result());
        }
    }
}
