package br.com.fiap.numberone.billing.budget.infrastructure.persistence.repositories;

import br.com.fiap.numberone.billing.budget.domain.enums.BudgetStatus;
import br.com.fiap.numberone.billing.budget.infrastructure.persistence.entities.BudgetEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BudgetRepository extends JpaRepository<BudgetEntity, UUID> {

    @Query("SELECT budget.serviceOrderId FROM BudgetEntity budget WHERE budget.budgetId = :budgetId")
    Optional<UUID> findServiceOrderIdByBudgetId(@Param("budgetId") UUID budgetId);

    @Query("""
            SELECT MAX(budget.budgetVersion) FROM BudgetEntity budget
            WHERE budget.serviceOrderId = :serviceOrderId AND budget.status IN :statuses
            """)
    Integer findLatestSentVersion(@Param("serviceOrderId") UUID serviceOrderId,
                                  @Param("statuses") Collection<BudgetStatus> statuses);

    boolean existsByServiceOrderIdAndStatus(UUID serviceOrderId, BudgetStatus status);

    Optional<BudgetEntity> findByServiceOrderIdAndBudgetVersion(UUID serviceOrderId, int budgetVersion);

    List<BudgetEntity> findAllByServiceOrderIdOrderByBudgetVersionAsc(UUID serviceOrderId);
}
