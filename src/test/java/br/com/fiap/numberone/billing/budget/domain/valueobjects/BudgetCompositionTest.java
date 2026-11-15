package br.com.fiap.numberone.billing.budget.domain.valueobjects;

import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.enums.BudgetStatus;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetDataException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.BudgetCalculationException;
import br.com.fiap.numberone.billing.budget.domain.references.InventoryItemPrice;
import br.com.fiap.numberone.billing.budget.domain.references.ServiceOrderQuotationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BudgetCompositionTest {

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "WAITING_FOR_PARTS_AND_SUPPLIES", "IN_PROGRESS", "COMPLETED"})
    void shouldIncludeEachKnownEligibleStatus(String status) {
        var context = context(List.of(new ServiceOrderQuotationContext.Item(UUID.randomUUID(), UUID.randomUUID(),
                BigDecimal.TEN, status, List.of())));
        assertThat(BudgetComposition.from(context, List.of()).resolveQuotedAmount(null))
                .isEqualByComparingTo(BigDecimal.TEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "", " "})
    void shouldFailAutomaticCalculationForUndeterminedItemStatus(String status) {
        var context = context(List.of(item(BigDecimal.TEN, status.isEmpty() ? null : status,
                UUID.randomUUID(), 1)));
        var composition = BudgetComposition.from(context, List.of());

        assertThatThrownBy(context::requiredInventoryItemIds)
                .isInstanceOf(BudgetCalculationException.class);
        assertThatThrownBy(() -> composition.resolveQuotedAmount(null))
                .isInstanceOf(BudgetCalculationException.class);
        assertThat(composition.resolveQuotedAmount(BigDecimal.TEN)).isEqualByComparingTo(BigDecimal.TEN);
    }

    @Test
    void shouldSumServicesAndSuppliesWithoutRoundingAndPreserveCurrency() {
        UUID partId = UUID.randomUUID();
        var context = context(List.of(item(new BigDecimal("150.12"), "PENDING", partId, 3)));
        var composition = BudgetComposition.from(context,
                List.of(new InventoryItemPrice(partId, new BigDecimal("20.01"), "BRL")));

        assertThat(composition.resolveQuotedAmount(null)).isEqualByComparingTo("210.15");
        assertThat(composition.items().getFirst().supplies().getFirst().currency()).isEqualTo("BRL");
    }

    @Test
    void shouldCalculateExactCentsWithTrailingZerosWithoutNormalizingComponentsOrTotal() {
        UUID partId = UUID.randomUUID();
        BigDecimal serviceAmount = new BigDecimal("150.1200");
        BigDecimal price = new BigDecimal("20.01000");
        var composition = BudgetComposition.from(context(List.of(item(serviceAmount, "PENDING", partId, 3))),
                List.of(new InventoryItemPrice(partId, price, "BRL")));

        assertThat(composition.resolveQuotedAmount(null)).isEqualTo(new BigDecimal("210.15000"));
        assertThat(composition.items().getFirst().serviceAmount()).isEqualTo(serviceAmount);
        assertThat(composition.items().getFirst().supplies().getFirst().salePrice()).isEqualTo(price);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.001", "1.234", "1.2301", "10.0001"})
    void shouldRejectIncompatibleManualPrecisionWithoutRounding(String value) {
        var composition = new BudgetComposition(List.of());
        assertThatThrownBy(() -> composition.resolveQuotedAmount(new BigDecimal(value)))
                .isInstanceOf(InvalidBudgetDataException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "1", "1.2", "1.23", "1E+3", "10.0", "10.00", "10.000", "1.23000", "0.0100"})
    void shouldPreserveValidManualAmountAndScale(String value) {
        BigDecimal amount = new BigDecimal(value);
        assertThat(new BudgetComposition(List.of()).resolveQuotedAmount(amount)).isEqualTo(amount);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.001", "1.234", "1.2301"})
    void shouldRejectIncompatibleServicePrecision(String value) {
        var context = context(List.of(new ServiceOrderQuotationContext.Item(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal(value), "PENDING", List.of())));
        assertThatThrownBy(() -> BudgetComposition.from(context, List.of()).resolveQuotedAmount(null))
                .isInstanceOf(BudgetCalculationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.001", "1.234", "1.2301"})
    void shouldRejectIncompatibleSupplyPriceEvenWhenMultiplicationWouldYieldWholeCents(String value) {
        UUID partId = UUID.randomUUID();
        var composition = BudgetComposition.from(context(List.of(item(BigDecimal.TEN, "PENDING", partId, 1000))),
                List.of(new InventoryItemPrice(partId, new BigDecimal(value), "BRL")));
        assertThatThrownBy(() -> composition.resolveQuotedAmount(null)).isInstanceOf(BudgetCalculationException.class);
    }

    @Test
    void shouldPreserveTechnicalDataAndAbsentPricesForManualAmount() {
        UUID partId = UUID.randomUUID();
        var context = context(List.of(item(null, "PENDING", partId, null)));
        var composition = BudgetComposition.from(context, List.of());

        assertThat(composition.resolveQuotedAmount(new BigDecimal("99.00"))).isEqualByComparingTo("99.00");
        assertThat(composition.items().getFirst().serviceAmount()).isNull();
        assertThat(composition.items().getFirst().supplies().getFirst().inventoryItemId()).isEqualTo(partId);
        assertThat(composition.items().getFirst().supplies().getFirst().salePrice()).isNull();
        assertThat(composition.items().getFirst().supplies().getFirst().currency()).isNull();
        assertThat(composition.items().getFirst().supplies().getFirst().quantity()).isNull();
    }

    @Test
    void shouldPreferManualAmountEvenWhenAllPricesAreKnown() {
        UUID partId = UUID.randomUUID();
        var composition = BudgetComposition.from(context(List.of(item(BigDecimal.TEN, "PENDING", partId, 2))),
                List.of(new InventoryItemPrice(partId, BigDecimal.TEN, "BRL")));

        assertThat(composition.resolveQuotedAmount(new BigDecimal("5.00"))).isEqualByComparingTo("5.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-0.01"})
    void shouldRejectNonPositiveManualAmount(String value) {
        assertThatThrownBy(() -> new BudgetComposition(List.of()).resolveQuotedAmount(new BigDecimal(value)))
                .isInstanceOf(InvalidBudgetDataException.class);
    }

    @Test
    void shouldFailWhenAutomaticSupplyPriceIsMissing() {
        UUID partId = UUID.randomUUID();
        var composition = BudgetComposition.from(context(List.of(item(BigDecimal.TEN, "PENDING", partId, 1))), List.of());

        assertThatThrownBy(() -> composition.resolveQuotedAmount(null))
                .isInstanceOf(InvalidBudgetDataException.class);
    }

    @Test
    void shouldFailWhenAutomaticQuantityIsMissing() {
        UUID partId = UUID.randomUUID();
        var composition = BudgetComposition.from(context(List.of(item(BigDecimal.TEN, "PENDING", partId, null))),
                List.of(new InventoryItemPrice(partId, BigDecimal.ONE, "BRL")));

        assertThatThrownBy(() -> composition.resolveQuotedAmount(null))
                .isInstanceOf(InvalidBudgetDataException.class);
    }

    @Test
    void shouldFailWhenAutomaticServiceAmountIsMissing() {
        var composition = BudgetComposition.from(context(List.of(
                new ServiceOrderQuotationContext.Item(UUID.randomUUID(), UUID.randomUUID(), null, "PENDING", List.of()))), List.of());

        assertThatThrownBy(() -> composition.resolveQuotedAmount(null))
                .isInstanceOf(InvalidBudgetDataException.class);
    }

    @Test
    void shouldKeepCancelledTechnicalItemsWithoutIncludingThemInCalculation() {
        var context = context(List.of(item(null, "CANCELLED", UUID.randomUUID(), null),
                new ServiceOrderQuotationContext.Item(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN,
                        "PENDING", List.of())));
        var composition = BudgetComposition.from(context, List.of());

        assertThat(context.requiredInventoryItemIds()).isEmpty();
        assertThat(composition.items()).hasSize(2);
        assertThat(composition.resolveQuotedAmount(null)).isEqualByComparingTo("10");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "0.000"})
    void shouldAcceptExplicitZeroPriceWithoutTreatingMissingPriceAsZero(String value) {
        UUID partId = UUID.randomUUID();
        BigDecimal zero = new BigDecimal(value);
        var composition = BudgetComposition.from(context(List.of(item(zero, "PENDING", partId, 1))),
                List.of(new InventoryItemPrice(partId, zero, "BRL")));
        assertThat(composition.resolveQuotedAmount(null)).isEqualTo(zero);
    }

    @Test
    void shouldCopyCollectionsAndExposeImmutableValueSemantics() {
        var supplies = new ArrayList<>(List.of(new BudgetComposition.Supply(UUID.randomUUID(), 2, null, null)));
        var item = new BudgetComposition.ServiceItem(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, null, supplies);
        var items = new ArrayList<>(List.of(item));
        var composition = new BudgetComposition(items);
        supplies.clear();
        items.clear();

        assertThat(composition.items()).containsExactly(item);
        assertThat(item.supplies()).hasSize(1);
        assertThatThrownBy(() -> composition.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> item.supplies().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(composition).isEqualTo(new BudgetComposition(List.of(item)));
    }

    @Test
    void shouldRejectDuplicatePricesRatherThanSelectAnArbitraryOne() {
        UUID partId = UUID.randomUUID();
        assertThatThrownBy(() -> BudgetComposition.from(context(List.of()), List.of(
                new InventoryItemPrice(partId, BigDecimal.TEN, "BRL"),
                new InventoryItemPrice(partId, BigDecimal.ONE, "BRL"))))
                .isInstanceOf(InvalidBudgetDataException.class);
    }

    @Test
    void shouldKeepQuotedAmountAndCompositionDuringExistingTransitions() {
        var composition = new BudgetComposition(List.of());
        Budget budget = Budget.createDraft(UUID.randomUUID(), 1, new BigDecimal("123.00"), composition);

        budget.markAsSent();
        budget.approve();

        assertThat(budget.getQuotedAmount()).isEqualByComparingTo("123.00");
        assertThat(budget.getApprovedAmount()).isEqualByComparingTo("123.00");
        assertThat(budget.getComposition()).isSameAs(composition);
    }

    @Test
    void shouldRequireCompositionForNewBudgetsButAllowAbsentHistoricalSnapshot() {
        assertThatThrownBy(() -> Budget.createDraft(UUID.randomUUID(), 1, BigDecimal.TEN, null))
                .isInstanceOf(InvalidBudgetDataException.class);
        Budget restored = Budget.restore(UUID.randomUUID(), UUID.randomUUID(), 3, BigDecimal.TEN, null,
                BudgetStatus.REJECTED, null, null, LocalDateTime.of(2026, 1, 1, 0, 0), null);
        assertThat(restored.getComposition()).isNull();
    }

    private ServiceOrderQuotationContext context(List<ServiceOrderQuotationContext.Item> items) {
        return new ServiceOrderQuotationContext(UUID.randomUUID(), "IN_DIAGNOSIS", items);
    }

    private ServiceOrderQuotationContext.Item item(BigDecimal amount, String status, UUID partId, Integer quantity) {
        return new ServiceOrderQuotationContext.Item(UUID.randomUUID(), UUID.randomUUID(), amount, status,
                List.of(new ServiceOrderQuotationContext.Supply(partId, quantity)));
    }
}
