package br.com.fiap.numberone.billing.budget.api.mappers;

import br.com.fiap.numberone.billing.budget.api.dtos.responses.BudgetResponse;
import br.com.fiap.numberone.billing.budget.api.dtos.responses.BudgetHistoryResponse;
import br.com.fiap.numberone.billing.budget.application.results.BudgetHistory;
import br.com.fiap.numberone.billing.budget.application.results.BudgetDetails;
import br.com.fiap.numberone.billing.budget.api.dtos.responses.BudgetDetailsResponse;
import br.com.fiap.numberone.billing.budget.api.dtos.responses.BudgetNotificationResponse;
import br.com.fiap.numberone.billing.budget.api.dtos.responses.BudgetStatusResponse;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import org.springframework.stereotype.Component;

@Component
public class BudgetApiMapper {

    public BudgetDetailsResponse toDetailsResponse(BudgetDetails details) {
        var notification = details.notification();
        var response = notification == null ? null : new BudgetNotificationResponse(
                switch (notification.status()) {
                    case PENDING -> "PENDENTE";
                    case SENT -> "ENVIADA";
                    case FAILED -> "FALHA";
                    case CANCELLED -> "CANCELADA";
                }, notification.attempts(), notification.lastAttemptAt(), notification.sentAt(), details.canResend());
        return new BudgetDetailsResponse(toResponse(details.budget()), response);
    }

    public BudgetResponse toResponse(Budget budget) {
        return new BudgetResponse(budget.getBudgetId(), budget.getServiceOrderId(), budget.getQuotedAmount(),
                budget.getApprovedAmount(), toStatus(budget), budget.getSentAt(), budget.getApprovedAt(),
                budget.getCreatedAt(), budget.getUpdatedAt());
    }
    public BudgetHistoryResponse toHistoryResponse(BudgetHistory history) {
        var revisions = history.budgets().stream()
                .map(budget -> new BudgetHistoryResponse.RevisionResponse(budget.getBudgetId(),
                        budget.getBudgetVersion(), budget.getQuotedAmount(), budget.getApprovedAmount(),
                        toStatus(budget), budget.getSentAt(), budget.getApprovedAt()))
                .toList();
        return new BudgetHistoryResponse(history.serviceOrderId(), revisions, history.referenceBudgetId());
    }

    private BudgetStatusResponse toStatus(Budget budget) {
        return switch (budget.getStatus()) {
            case DRAFT -> BudgetStatusResponse.RASCUNHO;
            case SENT -> BudgetStatusResponse.ENVIADO;
            case APPROVED -> BudgetStatusResponse.APROVADO;
            case REJECTED -> BudgetStatusResponse.REJEITADO;
            case CANCELLED -> BudgetStatusResponse.CANCELADO;
        };
    }
}
