package br.com.fiap.numberone.billing.budget.application.gateways;

import br.com.fiap.numberone.billing.budget.domain.references.InventoryItemPrice;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface InventoryGateway {

    List<InventoryItemPrice> findByInventoryItemIds(Set<UUID> inventoryItemIds);
}
