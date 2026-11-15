package br.com.fiap.numberone.billing.budget.infrastructure.outbox;

import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.enums.BudgetStatus;
import br.com.fiap.numberone.billing.budget.domain.valueobjects.BudgetComposition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class BudgetIntegrationEventFactoryTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final BudgetIntegrationEventFactory factory = new BudgetIntegrationEventFactory(jsonMapper);

    @ParameterizedTest
    @CsvSource({"SENT,OrcamentoEnviado", "APPROVED,OrcamentoAprovado", "REJECTED,OrcamentoRejeitado"})
    void shouldCreateOnlyTheContractFieldsWithStableIdentityAndExactAmount(BudgetStatus status, String type) {
        Budget budget = draft();
        budget.markAsSent();
        if (status == BudgetStatus.APPROVED) budget.approve();
        if (status == BudgetStatus.REJECTED) budget.reject();
        UUID correlationId = UUID.randomUUID();
        Instant before = Instant.now().minusSeconds(1);

        BudgetIntegrationEvent event = factory.create(budget, correlationId);
        var envelope = jsonMapper.readTree(event.envelope());
        var payload = envelope.get("payload");

        assertThat(envelope.size()).isEqualTo(6);
        assertThat(UUID.fromString(envelope.get("eventId").asString())).isEqualTo(event.eventId());
        assertThat(envelope.get("eventType").asString()).isEqualTo(type);
        assertThat(envelope.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(UUID.fromString(envelope.get("correlationId").asString())).isEqualTo(correlationId);
        assertThat(envelope.get("occurredAt").asString()).endsWith("Z");
        assertThat(Instant.parse(envelope.get("occurredAt").asString()))
                .isEqualTo(event.occurredAt()).isBetween(before, Instant.now());
        assertThat(payload.get("serviceOrderId").asString()).isEqualTo(budget.getServiceOrderId().toString());
        assertThat(payload.get("budgetId").asString()).isEqualTo(budget.getBudgetId().toString());
        assertThat(payload.get("budgetVersion").asInt()).isEqualTo(7);
        assertThat(payload.size()).isEqualTo(status == BudgetStatus.APPROVED ? 4 : 3);
        if (status == BudgetStatus.APPROVED) {
            assertThat(payload.get("approvedAmount").isString()).isTrue();
            assertThat(payload.get("approvedAmount").asString()).isEqualTo("550.246800");
        }
        assertThat(event.eventType()).isEqualTo(type);
        assertThat(event.serviceOrderId()).isEqualTo(budget.getServiceOrderId());
        assertThat(event.budgetId()).isEqualTo(budget.getBudgetId());
        assertThat(event.budgetVersion()).isEqualTo(7);
        assertThat(event.schemaVersion()).isEqualTo(1);
    }

    @Test
    void shouldPublishCompositionAndCanonicalMoneyForSaga() {
        var budget = Budget.createDraft(UUID.randomUUID(), 3, new BigDecimal("550"),
                new BudgetComposition(List.of(), 12));
        budget.markAsSent();
        budget.approve();
        var payload = jsonMapper.readTree(factory.create(budget, UUID.randomUUID()).envelope()).path("payload");
        assertThat(payload.path("compositionVersion").asInt()).isEqualTo(12);
        assertThat(payload.path("currency").asText()).isEqualTo("BRL");
        assertThat(payload.path("approvedAmount").asText()).isEqualTo("550.00");
    }

    @Test
    void shouldRejectStatesWithoutAnIntegrationFactAndMissingCorrelation() {
        Budget draft = draft();
        assertThatThrownBy(() -> factory.create(draft, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        draft.markAsSent();
        assertThatThrownBy(() -> factory.create(draft, null)).isInstanceOf(NullPointerException.class);
    }

    private Budget draft() {
        return Budget.restore(UUID.randomUUID(), UUID.randomUUID(), 7, new BigDecimal("550.246800"), null,
            BudgetStatus.DRAFT, null, null, LocalDateTime.of(2026, 11, 1, 10, 0), null, new BudgetComposition(List.of()));
    }
}
