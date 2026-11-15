package br.com.fiap.numberone.billing.budget.domain.references;

import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetDataException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.BudgetCalculationException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.CustomerEmailException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidServiceOrderStatusException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.IneligibleServiceOrderException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public record ServiceOrderQuotationContext(UUID serviceOrderId, String status, List<Item> items, String customerEmail,
                                           Integer compositionVersion) {

    public ServiceOrderQuotationContext(UUID serviceOrderId, String status, List<Item> items, String customerEmail) {
        this(serviceOrderId, status, items, customerEmail, null);
    }

    public ServiceOrderQuotationContext(UUID serviceOrderId, String status, List<Item> items) {
        this(serviceOrderId, status, items, null);
    }

    public ServiceOrderQuotationContext {
        if (serviceOrderId == null || items == null) {
            throw new InvalidBudgetDataException("O contexto da OS deve informar seu identificador e seus itens");
        }
        items = List.copyOf(items);
    }

    public void validateBudgetCreation() {
        if (!"IN_DIAGNOSIS".equals(status) && !"REJECTED".equals(status)) {
            throw new IneligibleServiceOrderException("O estado da ordem de serviço não permite criar orçamento: " + status);
        }
    }

    public void validateBudgetSendingEligibility() {
        if (!"IN_DIAGNOSIS".equals(status) && !"REJECTED".equals(status)) {
            throw new InvalidServiceOrderStatusException("O estado da ordem de serviço não permite enviar orçamento: " + status);
        }
    }

    public void validateBudgetSending() {
        validateBudgetSendingEligibility();
        if (customerEmail == null || customerEmail.isBlank()) {
            throw new CustomerEmailException();
        }
    }

    public Set<UUID> requiredInventoryItemIds() {
        return items.stream().filter(item -> isItemEligibleForBudget(item.status()))
                .flatMap(item -> item.supplies().stream())
                .map(supply -> {
                    if (supply.inventoryItemId() == null) {
                        throw new InvalidBudgetDataException("O insumo deve ter identificador para consultar seu preço");
                    }
                    return supply.inventoryItemId();
                }).collect(Collectors.toUnmodifiableSet());
    }

    public static boolean isItemEligibleForBudget(String status) {
        if (status == null) {
            throw new BudgetCalculationException("O status do item está ausente para calcular o orçamento");
        }
        return switch (status) {
            case "PENDING", "WAITING_FOR_PARTS_AND_SUPPLIES", "IN_PROGRESS", "COMPLETED" -> true;
            case "CANCELLED" -> false;
            default -> throw new BudgetCalculationException("O status do item é desconhecido para calcular o orçamento: " + status);
        };
    }

    public record Item(UUID serviceOrderItemId, UUID automotiveServiceId, BigDecimal serviceAmount,
                       String status, List<Supply> supplies) {
        public Item {
            supplies = supplies == null ? List.of() : List.copyOf(supplies);
        }

    }

    public record Supply(UUID inventoryItemId, Integer quantity) {
    }
}
