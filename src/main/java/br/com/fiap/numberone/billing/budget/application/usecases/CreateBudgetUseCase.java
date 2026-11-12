package br.com.fiap.numberone.billing.budget.application.usecases;

import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.application.gateways.InventoryGateway;
import br.com.fiap.numberone.billing.budget.application.gateways.ServiceOrderGateway;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetDataException;
import br.com.fiap.numberone.billing.budget.domain.references.InventoryItemPrice;
import br.com.fiap.numberone.billing.budget.domain.references.ServiceOrderQuotationContext;
import br.com.fiap.numberone.billing.budget.domain.valueobjects.BudgetComposition;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class CreateBudgetUseCase {

    private final ServiceOrderGateway serviceOrderGateway;
    private final InventoryGateway inventoryGateway;
    private final BudgetGateway budgetGateway;

    public CreateBudgetUseCase(ServiceOrderGateway serviceOrderGateway,
                               InventoryGateway inventoryGateway, BudgetGateway budgetGateway) {
        this.serviceOrderGateway = serviceOrderGateway;
        this.inventoryGateway = inventoryGateway;
        this.budgetGateway = budgetGateway;
    }

    public Budget execute(UUID serviceOrderId, BigDecimal manualAmount) {
        if (serviceOrderId == null) {
            throw new InvalidBudgetDataException("O identificador da ordem de serviço é obrigatório");
        }

        BudgetComposition.validateManualAmount(manualAmount);

        ServiceOrderQuotationContext context = serviceOrderGateway.findByServiceOrderId(serviceOrderId);

        ServiceOrderContextValidation.requireMatching(context, serviceOrderId);

        context.validateBudgetCreation();
        List<InventoryItemPrice> prices = List.of();

        if (manualAmount == null) {
            Set<UUID> inventoryItemIds = context.requiredInventoryItemIds();
            if (!inventoryItemIds.isEmpty()) {
                prices = inventoryGateway.findByInventoryItemIds(inventoryItemIds);
            }
        }

        BudgetComposition composition = BudgetComposition.from(context, prices);
        BigDecimal quotedAmount = composition.resolveQuotedAmount(manualAmount);

        return budgetGateway.createDraft(serviceOrderId, quotedAmount, composition);
    }
}
