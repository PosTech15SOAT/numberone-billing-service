package br.com.fiap.numberone.billing.budget.domain.references;

import java.math.BigDecimal;
import java.util.UUID;

public record InventoryItemPrice(UUID inventoryItemId, BigDecimal salePrice, String currency) {
}
