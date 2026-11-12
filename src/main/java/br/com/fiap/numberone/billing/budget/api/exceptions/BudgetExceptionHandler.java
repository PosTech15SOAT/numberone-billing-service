package br.com.fiap.numberone.billing.budget.api.exceptions;

import br.com.fiap.numberone.billing.budget.api.controllers.BudgetAdminController;
import br.com.fiap.numberone.billing.budget.api.dtos.responses.ErrorResponse;
import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetNotFoundException;
import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetNotificationUnavailableException;
import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetResendTooSoonException;
import br.com.fiap.numberone.billing.budget.application.exceptions.EmployeeIdentityUnavailableException;
import br.com.fiap.numberone.billing.budget.application.exceptions.ExternalServiceException;
import br.com.fiap.numberone.billing.budget.application.exceptions.ServiceOrderNotFoundException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.ApprovedBudgetExistsException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.BudgetCalculationException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.CustomerEmailException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.IneligibleServiceOrderException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetDataException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetStatusException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidServiceOrderStatusException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.ObsoleteBudgetRevisionException;
import br.com.fiap.numberone.billing.budget.infrastructure.logging.SafeExceptionDetails;

import br.com.fiap.numberone.billing.infrastructure.observability.ObservationLog;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@RestControllerAdvice(basePackageClasses = BudgetAdminController.class)
public class BudgetExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(BudgetExceptionHandler.class);

    @ExceptionHandler(BudgetNotificationUnavailableException.class)
    public ResponseEntity<ErrorResponse> notificationUnavailable(BudgetNotificationUnavailableException exception) {
        // Somente a mensagem controlada da aplicação; mensagens das causas externas são omitidas.
        ObservationLog.safely(() -> LOGGER.atError().addKeyValue("event.code", "BUDGET_NOTIFICATION_PREPARATION_FAILED")
                .addKeyValue("error.type", exception.getClass().getName())
                .addKeyValue("error.message", exception.getMessage())
                .addKeyValue("error.stack_trace", SafeExceptionDetails.stackTrace(exception))
                .log("Falha ao preparar notificação de aprovação"));
        return error(503, "Não foi possível preparar a notificação de aprovação do orçamento");
    }

    @ExceptionHandler(BudgetResendTooSoonException.class)
    public ResponseEntity<ErrorResponse> resendTooSoon(BudgetResendTooSoonException exception) {
        return error(409, exception.getMessage());
    }

    @ExceptionHandler({ServiceOrderNotFoundException.class, BudgetNotFoundException.class})
    public ResponseEntity<ErrorResponse> notFound(RuntimeException exception) {
        return error(404, exception.getMessage());
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<ErrorResponse> concurrentUpdate(RuntimeException exception) {
        ObservationLog.safely(() -> LOGGER.atWarn().addKeyValue("event.code", "BUDGET_VERSION_CONFLICT")
                .addKeyValue("error.type", exception.getClass().getName()).log("Operação de orçamento rejeitada"));
        return error(409, "O orçamento foi alterado por outra operação. Consulte o estado atual antes de tentar novamente");
    }

    @ExceptionHandler({ObsoleteBudgetRevisionException.class, ApprovedBudgetExistsException.class})
    public ResponseEntity<ErrorResponse> revisionConflict(RuntimeException exception) {
        ObservationLog.safely(() -> LOGGER.atWarn().addKeyValue("event.code", "BUDGET_REVISION_CONFLICT")
                .addKeyValue("error.type", exception.getClass().getName()).log("Operação de orçamento rejeitada"));
        return error(409, exception.getMessage());
    }

    @ExceptionHandler(IneligibleServiceOrderException.class)
    public ResponseEntity<ErrorResponse> ineligible(IneligibleServiceOrderException exception) {
        ObservationLog.safely(() -> LOGGER.atWarn().addKeyValue("event.code", "BUDGET_ORDER_INELIGIBLE")
                .addKeyValue("error.type", exception.getClass().getName()).log("Operação de orçamento rejeitada"));
        return error(409, exception.getMessage());
    }

    @ExceptionHandler({BudgetCalculationException.class, CustomerEmailException.class,
            InvalidBudgetStatusException.class, InvalidServiceOrderStatusException.class})
    public ResponseEntity<ErrorResponse> calculation(RuntimeException exception) {
        ObservationLog.safely(() -> LOGGER.atWarn().addKeyValue("event.code", "BUDGET_RULE_REJECTED")
                .addKeyValue("error.type", exception.getClass().getName()).log("Operação de orçamento rejeitada"));
        return error(422, exception.getMessage());
    }

    @ExceptionHandler(InvalidBudgetDataException.class)
    public ResponseEntity<ErrorResponse> invalidData(InvalidBudgetDataException exception) {
        return error(400, exception.getMessage());
    }

    @ExceptionHandler(EmployeeIdentityUnavailableException.class)
    public ResponseEntity<ErrorResponse> employeeIdentityUnavailable(EmployeeIdentityUnavailableException exception) {
        return error(503, exception.getMessage());
    }

    @ExceptionHandler(ExternalServiceException.class)
    public ResponseEntity<ErrorResponse> externalService(ExternalServiceException exception) {
        int status = switch (exception.getFailure()) {
            case TIMEOUT -> 504;
            case UNAVAILABLE -> 503;
            case INVALID_RESPONSE -> 502;
        };
        ObservationLog.safely(() -> LOGGER.atWarn().addKeyValue("event.code", "BUDGET_EXTERNAL_SERVICE_FAILED")
                .addKeyValue("error.code", exception.getFailure().name())
                .addKeyValue("error.type", exception.getClass().getName())
                .addKeyValue("error.stack_trace", SafeExceptionDetails.stackTrace(exception))
                .log("Falha na integração na operação de orçamento"));
        return error(status, exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException exception) {
        List<String> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage()).toList();
        return ResponseEntity.badRequest().body(new ErrorResponse(400, "Erro de validação", errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException exception) {
        return error(400, "O corpo da requisição está inválido ou mal formatado");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> typeMismatch(MethodArgumentTypeMismatchException exception) {
        return error(400, "O identificador deve ser um UUID válido");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception exception) {
        ObservationLog.safely(() -> LOGGER.atError().addKeyValue("event.code", "BUDGET_REQUEST_FAILED")
                .addKeyValue("error.type", exception.getClass().getName())
                .addKeyValue("error.stack_trace", SafeExceptionDetails.stackTrace(exception))
                .log("Erro inesperado na operação de orçamento"));
        return error(500, "Erro interno, tente novamente mais tarde");
    }

    private ResponseEntity<ErrorResponse> error(int status, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(status, message, List.of()));
    }
}
