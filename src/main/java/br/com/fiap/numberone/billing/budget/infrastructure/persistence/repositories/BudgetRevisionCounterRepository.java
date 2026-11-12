package br.com.fiap.numberone.billing.budget.infrastructure.persistence.repositories;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Repository
public class BudgetRevisionCounterRepository {

    private final JdbcTemplate jdbcTemplate;

    public BudgetRevisionCounterRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockByServiceOrderId(UUID serviceOrderId) {
        jdbcTemplate.queryForObject("""
                SELECT last_version FROM budget_revision_counter
                WHERE service_order_id = ? FOR UPDATE
                """, Integer.class, serviceOrderId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int nextVersion(UUID serviceOrderId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO budget_revision_counter (service_order_id, last_version)
                VALUES (?, 1)
                ON CONFLICT (service_order_id)
                DO UPDATE
                SET last_version = budget_revision_counter.last_version + 1
                RETURNING last_version
                """, Integer.class, serviceOrderId);
    }
}
