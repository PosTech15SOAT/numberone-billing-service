package br.com.fiap.numberone.billing.budget.domain.entities;

import br.com.fiap.numberone.billing.budget.domain.enums.BudgetStatus;
import br.com.fiap.numberone.billing.budget.domain.enums.BudgetApprovalOrigin;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetDataException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.ObsoleteBudgetRevisionException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.ApprovedBudgetExistsException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetStatusException;
import br.com.fiap.numberone.billing.budget.domain.valueobjects.BudgetComposition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BudgetTest {

    private static final BigDecimal QUOTED_AMOUNT = new BigDecimal("350.12");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 11, 1, 10, 0);

    @ParameterizedTest
    @ValueSource(strings = {"0.001", "1.234", "1.2301", "10.0001"})
    void shouldRejectNewDraftWithIncompatiblePrecision(String value) {
        assertThatThrownBy(() -> createDraft(UUID.randomUUID(), 1, new BigDecimal(value)))
                .isInstanceOf(InvalidBudgetDataException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "0.000", "0.01", "1", "1.2", "1.23", "1E+3", "10.0", "10.00", "10.000", "1.23000"})
    void shouldPreserveValidDraftAmountIncludingAutomaticZero(String value) {
        BigDecimal amount = new BigDecimal(value);
        assertThat(createDraft(UUID.randomUUID(), 1, amount).getQuotedAmount()).isEqualTo(amount);
    }

    @Test
    void shouldRestoreHistoricalPrecisionWithoutRoundingOrChangingApproval() {
        BigDecimal historicalAmount = new BigDecimal("350.123400");
        Budget restored = Budget.restore(UUID.randomUUID(), UUID.randomUUID(), 1, historicalAmount, historicalAmount,
                BudgetStatus.APPROVED, CREATED_AT, CREATED_AT, CREATED_AT, CREATED_AT);
        assertThat(restored.getQuotedAmount()).isEqualTo(historicalAmount);
        assertThat(restored.getApprovedAmount()).isEqualTo(historicalAmount);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRegisterInPersonApprovalAndKeepCommercialData(boolean alreadySent) {
        Budget budget = createDraft(UUID.randomUUID(), 2, QUOTED_AMOUNT);
        if (alreadySent) budget.markAsSent();
        var sentAt = budget.getSentAt();
        var composition = budget.getComposition();
        budget.registerApproval(alreadySent ? 2 : 1, false, "validated-employee-subject");
        assertThat(budget.getStatus()).isEqualTo(BudgetStatus.APPROVED);
        assertThat(budget.getApprovalOrigin()).isEqualTo(BudgetApprovalOrigin.IN_PERSON);
        assertThat(budget.getApprovedByEmployeeId()).isEqualTo("validated-employee-subject");
        assertThat(budget.getApprovedAmount()).isEqualTo(QUOTED_AMOUNT);
        assertThat(budget.getQuotedAmount()).isEqualTo(QUOTED_AMOUNT);
        assertThat(budget.getBudgetVersion()).isEqualTo(2);
        assertThat(budget.getComposition()).isSameAs(composition);
        assertThat(budget.getSentAt()).isNotNull();
        if (alreadySent) assertThat(budget.getSentAt()).isEqualTo(sentAt);
        assertThat(budget.getApprovedAt()).isNotNull().isEqualTo(budget.getUpdatedAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void shouldNotStartTransitionsWithoutEmployeeIdentity(String employeeId) {
        Budget budget = createDraft(UUID.randomUUID(), 1, QUOTED_AMOUNT);
        assertThatThrownBy(() -> budget.registerApproval(null, false, employeeId)).isInstanceOf(InvalidBudgetDataException.class);
        assertThat(budget.getStatus()).isEqualTo(BudgetStatus.DRAFT);
        assertThat(budget.getSentAt()).isNull();
        assertThat(budget.getApprovalOrigin()).isNull();
    }

    @Test
    void shouldRestoreApprovalAuditWithoutExecutingTransitions() {
        UUID id = UUID.randomUUID();
        Budget budget = Budget.restore(id, UUID.randomUUID(), 3, QUOTED_AMOUNT, QUOTED_AMOUNT, BudgetStatus.APPROVED,
                CREATED_AT, CREATED_AT, CREATED_AT, CREATED_AT, null, BudgetApprovalOrigin.IN_PERSON, "validated-subject");
        assertThat(budget.getBudgetId()).isEqualTo(id);
        assertThat(budget.getApprovalOrigin()).isEqualTo(BudgetApprovalOrigin.IN_PERSON);
        assertThat(budget.getApprovedByEmployeeId()).isEqualTo("validated-subject");
        assertThat(budget.getSentAt()).isEqualTo(CREATED_AT);
        assertThat(budget.getApprovedAt()).isEqualTo(CREATED_AT);
        assertThat(budget.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void shouldIncludeDecidedRevisionsInCommercialReference() {
        assertThat(Budget.sentRevisionStatuses())
                .containsExactlyInAnyOrder(BudgetStatus.SENT, BudgetStatus.APPROVED, BudgetStatus.REJECTED);
    }

    @Test
    void shouldBlockRevisionCreationAndSendingAfterApproval() {
        Budget draft = createDraft(UUID.randomUUID(), 2, QUOTED_AMOUNT);
        assertThatThrownBy(() -> Budget.validateRevisionCreation(true)).isInstanceOf(ApprovedBudgetExistsException.class);
        assertThatThrownBy(() -> draft.validateSendingRevision(1, true)).isInstanceOf(ApprovedBudgetExistsException.class);
        assertThat(draft.getStatus()).isEqualTo(BudgetStatus.DRAFT);
        assertThat(draft.getSentAt()).isNull();
    }

    @Test
    void shouldRejectObsoleteDraftWithoutChangingIt() {
        Budget draft = createDraft(UUID.randomUUID(), 1, QUOTED_AMOUNT);
        assertThatThrownBy(() -> draft.validateSendingRevision(2, false)).isInstanceOf(ObsoleteBudgetRevisionException.class);
        assertThat(draft.getStatus()).isEqualTo(BudgetStatus.DRAFT);
        assertThat(draft.getBudgetVersion()).isEqualTo(1);
    }

    @Test
    void shouldRejectDecisionOnObsoleteSentRevision() {
        Budget budget = createDraft(UUID.randomUUID(), 1, QUOTED_AMOUNT);
        budget.markAsSent();
        assertThatThrownBy(() -> budget.validateCurrentRevision(2)).isInstanceOf(ObsoleteBudgetRevisionException.class);
        assertThat(budget.getStatus()).isEqualTo(BudgetStatus.SENT);
        assertThat(budget.getApprovedAt()).isNull();
    }

    @Test
    void shouldAllowFirstSendingAndANewerRevisionWithoutApproval() {
        Budget first = createDraft(UUID.randomUUID(), 1, QUOTED_AMOUNT);
        first.validateSendingRevision(null, false);
        first.markAsSent();
        first.validateCurrentRevision(1);
        Budget newer = createDraft(first.getServiceOrderId(), 2, QUOTED_AMOUNT);
        newer.validateSendingRevision(1, false);
        newer.markAsSent();
        newer.validateCurrentRevision(2);
        assertThat(newer.getBudgetVersion()).isEqualTo(2);
    }

    @Test
    void shouldCreateDraftUsingInternallySuppliedVersion() {
        UUID serviceOrderId = UUID.randomUUID();

        Budget budget = createDraft(serviceOrderId, 7, QUOTED_AMOUNT);

        assertThat(budget.getBudgetId()).isNotNull();
        assertThat(budget.getServiceOrderId()).isEqualTo(serviceOrderId);
        assertThat(budget.getBudgetVersion()).isEqualTo(7);
        assertThat(budget.getQuotedAmount()).isEqualTo(QUOTED_AMOUNT);
        assertThat(budget.getStatus()).isEqualTo(BudgetStatus.DRAFT);
        assertThat(budget.getApprovedAmount()).isNull();
        assertThat(budget.getSentAt()).isNull();
        assertThat(budget.getApprovedAt()).isNull();
        assertThat(budget.getCreatedAt()).isNotNull();
        assertThat(budget.getUpdatedAt()).isEqualTo(budget.getCreatedAt());
    }

    @Test
    void shouldGenerateDistinctIdentifiersForRevisions() {
        UUID serviceOrderId = UUID.randomUUID();
        Budget first = createDraft(serviceOrderId, 1, QUOTED_AMOUNT);
        Budget second = createDraft(serviceOrderId, 2, QUOTED_AMOUNT);

        assertThat(first.getBudgetId()).isNotEqualTo(second.getBudgetId());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void shouldRejectNonPositiveVersion(int version) {
        assertThatThrownBy(() -> createDraft(UUID.randomUUID(), version, QUOTED_AMOUNT))
                .isInstanceOf(InvalidBudgetDataException.class)
                .hasMessage("A versão do orçamento deve ser positiva");
    }

    @Test
    void shouldRejectMissingServiceOrderIdentifier() {
        assertThatThrownBy(() -> createDraft(null, 1, QUOTED_AMOUNT))
                .isInstanceOf(InvalidBudgetDataException.class);
    }

    @Test
    void shouldRejectMissingBudgetIdentifierWhenRestoring() {
        assertThatThrownBy(() -> Budget.restore(null, UUID.randomUUID(), 1, QUOTED_AMOUNT,
                null, BudgetStatus.DRAFT, null, null, CREATED_AT, null))
                .isInstanceOf(InvalidBudgetDataException.class);
    }

    @Test
    void shouldRequireQuotedAmountWithoutAddingMinimumAmountPolicy() {
        assertThatThrownBy(() -> createDraft(UUID.randomUUID(), 1, null))
                .isInstanceOf(InvalidBudgetDataException.class);
        assertThat(createDraft(UUID.randomUUID(), 1, BigDecimal.ZERO).getQuotedAmount()).isZero();
    }

    @Test
    void shouldSendDraftAndPreserveRevisionIdentityAndAmount() {
        Budget budget = createDraft(UUID.randomUUID(), 3, QUOTED_AMOUNT);
        UUID budgetId = budget.getBudgetId();
        UUID serviceOrderId = budget.getServiceOrderId();

        budget.markAsSent();

        assertThat(budget.getStatus()).isEqualTo(BudgetStatus.SENT);
        assertThat(budget.getSentAt()).isNotNull();
        assertThat(budget.getUpdatedAt()).isEqualTo(budget.getSentAt());
        assertThat(budget.getBudgetId()).isEqualTo(budgetId);
        assertThat(budget.getServiceOrderId()).isEqualTo(serviceOrderId);
        assertThat(budget.getBudgetVersion()).isEqualTo(3);
        assertThat(budget.getQuotedAmount()).isEqualTo(QUOTED_AMOUNT);
        assertThat(budget.getApprovedAmount()).isNull();
    }

    @Test
    void shouldApproveEntireSentBudgetWithoutChangingRevision() {
        Budget budget = createDraft(UUID.randomUUID(), 4, QUOTED_AMOUNT);
        UUID budgetId = budget.getBudgetId();
        budget.markAsSent();
        LocalDateTime sentAt = budget.getSentAt();

        budget.approve();

        assertThat(budget.getStatus()).isEqualTo(BudgetStatus.APPROVED);
        assertThat(budget.getApprovedAmount()).isEqualTo(QUOTED_AMOUNT);
        assertThat(budget.getApprovedAt()).isNotNull();
        assertThat(budget.getUpdatedAt()).isEqualTo(budget.getApprovedAt());
        assertThat(budget.getSentAt()).isEqualTo(sentAt);
        assertThat(budget.getBudgetId()).isEqualTo(budgetId);
        assertThat(budget.getBudgetVersion()).isEqualTo(4);
    }

    @Test
    void shouldRejectSentBudgetWithoutApprovingOrChangingRevision() {
        Budget budget = createDraft(UUID.randomUUID(), 5, QUOTED_AMOUNT);
        UUID budgetId = budget.getBudgetId();
        budget.markAsSent();

        budget.reject();

        assertThat(budget.getStatus()).isEqualTo(BudgetStatus.REJECTED);
        assertThat(budget.getApprovedAmount()).isNull();
        assertThat(budget.getApprovedAt()).isNull();
        assertThat(budget.getBudgetId()).isEqualTo(budgetId);
        assertThat(budget.getBudgetVersion()).isEqualTo(5);
        assertThat(budget.getQuotedAmount()).isEqualTo(QUOTED_AMOUNT);
    }

    @ParameterizedTest
    @EnumSource(BudgetStatus.class)
    void shouldRestoreHistoricalStateWithoutExecutingTransitions(BudgetStatus status) {
        UUID budgetId = UUID.randomUUID();
        UUID serviceOrderId = UUID.randomUUID();
        LocalDateTime sentAt = CREATED_AT.plusDays(1);
        LocalDateTime approvedAt = CREATED_AT.plusDays(2);
        LocalDateTime updatedAt = CREATED_AT.plusDays(3);
        BigDecimal historicalApprovedAmount = new BigDecimal("300.00");

        Budget restored = Budget.restore(budgetId, serviceOrderId, 8, QUOTED_AMOUNT,
                historicalApprovedAmount, status, sentAt, approvedAt, CREATED_AT, updatedAt);

        assertThat(restored.getBudgetId()).isEqualTo(budgetId);
        assertThat(restored.getServiceOrderId()).isEqualTo(serviceOrderId);
        assertThat(restored.getBudgetVersion()).isEqualTo(8);
        assertThat(restored.getStatus()).isEqualTo(status);
        assertThat(restored.getQuotedAmount()).isEqualTo(QUOTED_AMOUNT);
        assertThat(restored.getApprovedAmount()).isEqualTo(historicalApprovedAmount);
        assertThat(restored.getSentAt()).isEqualTo(sentAt);
        assertThat(restored.getApprovedAt()).isEqualTo(approvedAt);
        assertThat(restored.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(restored.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @ParameterizedTest
    @EnumSource(value = BudgetStatus.class, names = "DRAFT", mode = EnumSource.Mode.EXCLUDE)
    void shouldRefuseSendingFromOtherStates(BudgetStatus status) {
        Budget budget = historicalBudget(status);
        assertThatThrownBy(budget::markAsSent)
                .isInstanceOf(InvalidBudgetStatusException.class)
                .hasMessage("Não é possível enviar o orçamento no estado " + status);
        assertThat(budget.getStatus()).isEqualTo(status);
        assertThat(budget.getUpdatedAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = BudgetStatus.class, names = "SENT", mode = EnumSource.Mode.EXCLUDE)
    void shouldRefuseApprovingFromOtherStates(BudgetStatus status) {
        Budget budget = historicalBudget(status);
        assertThatThrownBy(budget::approve).isInstanceOf(InvalidBudgetStatusException.class);
        assertThat(budget.getStatus()).isEqualTo(status);
        assertThat(budget.getApprovedAmount()).isNull();
        assertThat(budget.getApprovedAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = BudgetStatus.class, names = "SENT", mode = EnumSource.Mode.EXCLUDE)
    void shouldRefuseRejectingFromOtherStates(BudgetStatus status) {
        Budget budget = historicalBudget(status);
        assertThatThrownBy(budget::reject).isInstanceOf(InvalidBudgetStatusException.class);
        assertThat(budget.getStatus()).isEqualTo(status);
        assertThat(budget.getUpdatedAt()).isNull();
    }

    private Budget historicalBudget(BudgetStatus status) {
        return Budget.restore(UUID.randomUUID(), UUID.randomUUID(), 1, QUOTED_AMOUNT,
                null, status, null, null, CREATED_AT, null);
    }
    private Budget createDraft(UUID serviceOrderId, int version, BigDecimal amount) {
        return Budget.createDraft(serviceOrderId, version, amount, new BudgetComposition(List.of()));
    }
}
