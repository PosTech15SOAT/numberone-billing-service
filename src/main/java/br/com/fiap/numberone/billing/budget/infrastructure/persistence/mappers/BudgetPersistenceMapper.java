package br.com.fiap.numberone.billing.budget.infrastructure.persistence.mappers;

import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.valueobjects.BudgetComposition;
import br.com.fiap.numberone.billing.budget.infrastructure.persistence.entities.BudgetEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class BudgetPersistenceMapper {

    private final JsonMapper jsonMapper;

    public BudgetPersistenceMapper(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public BudgetEntity toEntity(Budget budget) {
        return new BudgetEntity(budget.getBudgetId(), budget.getServiceOrderId(), budget.getBudgetVersion(),
                budget.getQuotedAmount(), budget.getApprovedAmount(), budget.getStatus(), budget.getSentAt(),
                budget.getApprovedAt(), budget.getCreatedAt(), budget.getUpdatedAt(),
                budget.getComposition() == null ? null : jsonMapper.writeValueAsString(budget.getComposition()),
                budget.getApprovalOrigin(), budget.getApprovedByEmployeeId());
    }

    public void updateState(Budget budget, BudgetEntity entity) {
        entity.updateState(budget.getApprovedAmount(), budget.getStatus(), budget.getSentAt(),
                budget.getApprovedAt(), budget.getUpdatedAt(), budget.getApprovalOrigin(), budget.getApprovedByEmployeeId());
    }

    public Budget toDomain(BudgetEntity entity) {
        return Budget.restore(entity.getBudgetId(), entity.getServiceOrderId(), entity.getBudgetVersion(),
                entity.getQuotedAmount(), entity.getApprovedAmount(), entity.getStatus(), entity.getSentAt(),
                entity.getApprovedAt(), entity.getCreatedAt(), entity.getUpdatedAt(),
                entity.getCommercialSnapshot() == null ? null
                        : jsonMapper.readValue(entity.getCommercialSnapshot(), BudgetComposition.class),
                entity.getApprovalOrigin(), entity.getApprovedByEmployeeId());
    }
}
