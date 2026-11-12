package br.com.fiap.numberone.billing.budget.api;

import br.com.fiap.numberone.billing.budget.api.mappers.BudgetApiMapper;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.enums.BudgetStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetApiMapperTest {

    @ParameterizedTest
    @CsvSource({"DRAFT,RASCUNHO", "SENT,ENVIADO", "APPROVED,APROVADO", "REJECTED,REJEITADO", "CANCELLED,CANCELADO"})
    void shouldPreserveLegacyPublicStatus(BudgetStatus status, String expected) {
        Budget budget = Budget.restore(UUID.randomUUID(), UUID.randomUUID(), 2, BigDecimal.TEN, null,
                status, null, null, LocalDateTime.of(2026, 1, 1, 0, 0), null);

        assertThat(new BudgetApiMapper().toResponse(budget).status().name()).isEqualTo(expected);
    }
}
