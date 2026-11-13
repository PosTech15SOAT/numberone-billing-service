package br.com.fiap.numberone.billing.budget.domain.valueobjects;

import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetDataException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.BudgetCalculationException;
import br.com.fiap.numberone.billing.budget.domain.references.InventoryItemPrice;
import br.com.fiap.numberone.billing.budget.domain.references.ServiceOrderQuotationContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record BudgetComposition(List<ServiceItem> items) {

    public BudgetComposition {
        if (items == null) {
            throw new InvalidBudgetDataException("Os itens da composição do orçamento são obrigatórios");
        }
        items = List.copyOf(items);
    }

    public static BudgetComposition from(ServiceOrderQuotationContext context, List<InventoryItemPrice> prices) {
        if (prices == null) {
            throw new BudgetCalculationException("A consulta de preços não retornou um resultado válido");
        }
        Map<UUID, InventoryItemPrice> indexedPrices = new HashMap<>();
        for (InventoryItemPrice price : prices) {
            if (indexedPrices.putIfAbsent(price.inventoryItemId(), price) != null) {
                throw new BudgetCalculationException("A consulta retornou preços duplicados para o mesmo insumo");
            }
        }
        List<ServiceItem> items = context.items().stream().map(item -> new ServiceItem(
                item.serviceOrderItemId(), item.automotiveServiceId(), item.serviceAmount(), item.status(),
                item.supplies().stream().map(supply -> {
                    InventoryItemPrice price = indexedPrices.get(supply.inventoryItemId());
                    return new Supply(supply.inventoryItemId(), supply.quantity(),
                            price == null ? null : price.salePrice(), price == null ? null : price.currency());
                }).toList())).toList();
        return new BudgetComposition(items);
    }

    public static void validateManualAmount(BigDecimal quotedAmount) {
        if (quotedAmount != null && quotedAmount.signum() <= 0) {
            throw new InvalidBudgetDataException("O valor manual do orçamento deve ser maior que zero");
        }
        if (quotedAmount != null) {
            validateQuotedAmount(quotedAmount);
        }
    }

    public static void validateQuotedAmount(BigDecimal quotedAmount) {
        if (quotedAmount == null || !hasCentPrecision(quotedAmount)) {
            throw new InvalidBudgetDataException("O valor proposto deve ter no máximo duas casas decimais, sem arredondamento");
        }
    }

    public BigDecimal resolveQuotedAmount(BigDecimal manualAmount) {
        validateManualAmount(manualAmount);
        if (manualAmount != null) {
            return manualAmount;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (ServiceItem item : items) {
            if (!ServiceOrderQuotationContext.isItemEligibleForBudget(item.status())) {
                continue;
            }
            if (item.serviceAmount() == null) {
                throw new BudgetCalculationException("O valor do serviço está ausente para calcular o orçamento");
            }
            if (!hasCentPrecision(item.serviceAmount())) {
                throw new BudgetCalculationException("O valor do serviço deve ter no máximo duas casas decimais, sem arredondamento");
            }
            total = total.add(item.serviceAmount());
            for (Supply supply : item.supplies()) {
                if (supply.salePrice() == null || supply.quantity() == null) {
                    throw new BudgetCalculationException("O preço e a quantidade do insumo são necessários para calcular o orçamento");
                }
                if (!hasCentPrecision(supply.salePrice())) {
                    throw new BudgetCalculationException("O preço do insumo deve ter no máximo duas casas decimais, sem arredondamento");
                }
                total = total.add(supply.salePrice().multiply(BigDecimal.valueOf(supply.quantity())));
            }
        }
        return total;
    }

    private static boolean hasCentPrecision(BigDecimal amount) {
        try {
            amount.setScale(2, RoundingMode.UNNECESSARY);
            return true;
        } catch (ArithmeticException exception) {
            return false;
        }
    }

    public record ServiceItem(UUID serviceOrderItemId, UUID automotiveServiceId, BigDecimal serviceAmount,
                              String status, List<Supply> supplies) {
        public ServiceItem {
            supplies = List.copyOf(supplies);
        }
    }

    public record Supply(UUID inventoryItemId, Integer quantity, BigDecimal salePrice, String currency) {
    }
}
