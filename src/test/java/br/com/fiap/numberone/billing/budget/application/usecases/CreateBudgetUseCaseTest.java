package br.com.fiap.numberone.billing.budget.application.usecases;

import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.application.gateways.InventoryGateway;
import br.com.fiap.numberone.billing.budget.application.gateways.ServiceOrderGateway;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.enums.BudgetStatus;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetDataException;
import br.com.fiap.numberone.billing.budget.domain.references.InventoryItemPrice;
import br.com.fiap.numberone.billing.budget.domain.references.ServiceOrderQuotationContext;
import br.com.fiap.numberone.billing.budget.domain.valueobjects.BudgetComposition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateBudgetUseCaseTest {

    @Mock private ServiceOrderGateway serviceOrderGateway;
    @Mock private InventoryGateway inventoryGateway;
    @Mock private BudgetGateway budgetGateway;
    private CreateBudgetUseCase useCase;
    private final UUID orderId = UUID.randomUUID();
    private final UUID partId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        useCase = new CreateBudgetUseCase(serviceOrderGateway, inventoryGateway, budgetGateway);
    }

    @ParameterizedTest
    @ValueSource(strings = {"IN_DIAGNOSIS", "REJECTED"})
    void shouldCreateManualDraftWithTechnicalSnapshotWithoutCallingInventory(String status) {
        when(serviceOrderGateway.findByServiceOrderId(orderId)).thenReturn(context(orderId, status));
        stubPersistence();

        Budget result = useCase.execute(orderId, new BigDecimal("9.99"));

        assertThat(result.getStatus()).isEqualTo(BudgetStatus.DRAFT);
        assertThat(result.getQuotedAmount()).isEqualByComparingTo("9.99");
        assertThat(result.getApprovedAmount()).isNull();
        assertThat(result.getComposition().items().getFirst().supplies().getFirst().salePrice()).isNull();
        verifyNoInteractions(inventoryGateway);
    }

    @Test
    void shouldCreateCalculatedDraftWithInventoryPricesAndDeduplicateRequestedIdentifiers() {
        when(serviceOrderGateway.findByServiceOrderId(orderId)).thenReturn(context(orderId, "IN_DIAGNOSIS"));
        when(inventoryGateway.findByInventoryItemIds(Set.of(partId)))
                .thenReturn(List.of(new InventoryItemPrice(partId, new BigDecimal("200.00"), "BRL")));
        stubPersistence();

        Budget result = useCase.execute(orderId, null);

        assertThat(result.getQuotedAmount()).isEqualByComparingTo("750.00");
        assertThat(result.getComposition().items().getFirst().supplies()).hasSize(2);
        verify(inventoryGateway).findByInventoryItemIds(Set.of(partId));
    }

    @Test
    void shouldSkipInventoryWhenThereAreNoSupplies() {
        var item = new ServiceOrderQuotationContext.Item(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, "PENDING", List.of());
        when(serviceOrderGateway.findByServiceOrderId(orderId))
                .thenReturn(new ServiceOrderQuotationContext(orderId, "IN_DIAGNOSIS", List.of(item)));
        stubPersistence();

        assertThat(useCase.execute(orderId, null).getQuotedAmount()).isEqualByComparingTo("10");
        verifyNoInteractions(inventoryGateway);
    }

    @Test
    void shouldNotPersistWhenARequiredPriceIsMissing() {
        when(serviceOrderGateway.findByServiceOrderId(orderId)).thenReturn(context(orderId, "IN_DIAGNOSIS"));
        when(inventoryGateway.findByInventoryItemIds(Set.of(partId))).thenReturn(List.of());

        assertThatThrownBy(() -> useCase.execute(orderId, null)).isInstanceOf(InvalidBudgetDataException.class);
        verifyNoInteractions(budgetGateway);
    }

    @Test
    void shouldNotPersistWhenInventoryFails() {
        when(serviceOrderGateway.findByServiceOrderId(orderId)).thenReturn(context(orderId, "IN_DIAGNOSIS"));
        when(inventoryGateway.findByInventoryItemIds(Set.of(partId))).thenThrow(new IllegalStateException("Inventory indisponível"));

        assertThatThrownBy(() -> useCase.execute(orderId, null)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(budgetGateway);
    }

    @Test
    void shouldNotPersistWhenServiceOrderLookupFails() {
        when(serviceOrderGateway.findByServiceOrderId(orderId)).thenThrow(new IllegalStateException("OS indisponível"));

        assertThatThrownBy(() -> useCase.execute(orderId, BigDecimal.TEN)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(inventoryGateway, budgetGateway);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RECEIVED", "WAITING_APPROVAL", "APPROVED", "IN_PROGRESS", "COMPLETED", "CANCELLED", "DELIVERED"})
    void shouldRejectIneligibleServiceOrder(String status) {
        when(serviceOrderGateway.findByServiceOrderId(orderId)).thenReturn(context(orderId, status));

        assertThatThrownBy(() -> useCase.execute(orderId, BigDecimal.TEN)).isInstanceOf(InvalidBudgetDataException.class);
        verifyNoInteractions(inventoryGateway, budgetGateway);
    }

    @Test
    void shouldRejectContextForAnotherServiceOrder() {
        when(serviceOrderGateway.findByServiceOrderId(orderId)).thenReturn(context(UUID.randomUUID(), "IN_DIAGNOSIS"));

        assertThatThrownBy(() -> useCase.execute(orderId, BigDecimal.TEN)).isInstanceOf(InvalidBudgetDataException.class);
        verifyNoInteractions(inventoryGateway, budgetGateway);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.000", "-1", "-1.000", "0.001", "1.234", "1.2301"})
    void shouldRejectInvalidManualAmountBeforeConsultingSources(String amount) {
        assertThatThrownBy(() -> useCase.execute(orderId, new BigDecimal(amount)))
                .isInstanceOf(InvalidBudgetDataException.class);
        verifyNoInteractions(serviceOrderGateway, inventoryGateway, budgetGateway);
    }

    @Test
    void shouldRejectMissingServiceOrderIdentifier() {
        assertThatThrownBy(() -> useCase.execute(null, BigDecimal.TEN)).isInstanceOf(InvalidBudgetDataException.class);
        verifyNoInteractions(serviceOrderGateway, inventoryGateway, budgetGateway);
    }

    @Test
    void shouldNotPersistWhenInventoryPriceHasIncompatiblePrecision() {
        when(serviceOrderGateway.findByServiceOrderId(orderId)).thenReturn(context(orderId, "IN_DIAGNOSIS"));
        when(inventoryGateway.findByInventoryItemIds(Set.of(partId)))
                .thenReturn(List.of(new InventoryItemPrice(partId, new BigDecimal("20.001"), "BRL")));

        assertThatThrownBy(() -> useCase.execute(orderId, null)).isInstanceOf(InvalidBudgetDataException.class);
        verifyNoInteractions(budgetGateway);
    }

    private void stubPersistence() {
        when(budgetGateway.createDraft(eq(orderId), any(BigDecimal.class), any(BudgetComposition.class)))
                .thenAnswer(invocation -> Budget.createDraft(invocation.getArgument(0), 1,
                        invocation.getArgument(1), invocation.getArgument(2)));
    }

    private ServiceOrderQuotationContext context(UUID id, String status) {
        var item = new ServiceOrderQuotationContext.Item(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("150.00"),
                "PENDING", List.of(new ServiceOrderQuotationContext.Supply(partId, 2),
                new ServiceOrderQuotationContext.Supply(partId, 1)));
        return new ServiceOrderQuotationContext(id, status, List.of(item));
    }
}
