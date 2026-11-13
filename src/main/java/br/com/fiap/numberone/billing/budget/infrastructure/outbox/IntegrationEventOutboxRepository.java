package br.com.fiap.numberone.billing.budget.infrastructure.outbox;

import br.com.fiap.numberone.billing.infrastructure.outbox.IntegrationEventOutboxEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.ZoneOffset;

@Repository
public class IntegrationEventOutboxRepository {

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    public IntegrationEventOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(BudgetIntegrationEvent event) {
        append(new IntegrationEventOutboxEntry(event.eventId(), event.eventType(), event.schemaVersion(),
                event.serviceOrderId(), event.budgetId(), event.budgetVersion(), null, event.occurredAt(), event.envelope()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(IntegrationEventOutboxEntry event) {
        OffsetDateTime createdAt = event.occurredAt().atOffset(ZoneOffset.UTC);
        jdbcTemplate.update("""
                INSERT INTO integration_event_outbox
                    (event_id, event_type, schema_version, service_order_id, budget_id, budget_version, charge_id,
                     envelope, status, created_at, attempt_count, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'PENDING', ?, 0, ?)
                """, event.eventId(), event.eventType(), event.schemaVersion(), event.serviceOrderId(),
                event.budgetId(), event.budgetVersion(), event.chargeId(), event.envelope(), createdAt, createdAt);
    }

    @Transactional
    public List<ClaimedIntegrationEvent> claimAvailable(int limit, int maxAttempts, Duration leaseDuration) {
        return namedJdbcTemplate.query("""
                WITH available AS (
                    SELECT event_id FROM integration_event_outbox
                    WHERE status = 'PENDING' AND next_attempt_at <= statement_timestamp()
                      AND (lease_until IS NULL OR lease_until <= statement_timestamp())
                    ORDER BY next_attempt_at, created_at, event_id
                    LIMIT :limit FOR UPDATE SKIP LOCKED
                ), claimed AS (
                    UPDATE integration_event_outbox AS event
                    SET status = CASE WHEN attempt_count >= :maxAttempts THEN 'NEEDS_REVIEW' ELSE 'PENDING' END,
                        lease_token = CASE WHEN attempt_count >= :maxAttempts THEN NULL ELSE gen_random_uuid() END,
                        lease_until = CASE WHEN attempt_count >= :maxAttempts THEN NULL
                            ELSE statement_timestamp() + :leaseMillis * INTERVAL '1 millisecond' END,
                        last_error = CASE WHEN attempt_count >= :maxAttempts
                            THEN 'ATTEMPTS_EXHAUSTED_AFTER_LEASE_EXPIRY' ELSE last_error END,
                        attempt_count = CASE WHEN attempt_count >= :maxAttempts THEN attempt_count ELSE attempt_count + 1 END
                    FROM available WHERE event.event_id = available.event_id
                    RETURNING event.*
                )
                SELECT event_id, envelope::text, lease_token, attempt_count FROM claimed WHERE status = 'PENDING'
                """, Map.of("limit", limit, "maxAttempts", maxAttempts, "leaseMillis", leaseDuration.toMillis()),
                (row, index) -> new ClaimedIntegrationEvent(row.getObject("event_id", UUID.class),
                        row.getString("envelope"), row.getObject("lease_token", UUID.class), row.getInt("attempt_count")));
    }

    @Transactional(readOnly = true)
    public boolean hasValidLease(ClaimedIntegrationEvent event) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM integration_event_outbox
                    WHERE event_id = ? AND status = 'PENDING' AND lease_token = ? AND lease_until > statement_timestamp())
                """, Boolean.class, event.eventId(), event.leaseToken()));
    }

    @Transactional
    public boolean markPublished(ClaimedIntegrationEvent event) {
        return jdbcTemplate.update("""
                UPDATE integration_event_outbox SET status = 'PUBLISHED', published_at = statement_timestamp(),
                    lease_token = NULL, lease_until = NULL, last_error = NULL
                WHERE event_id = ? AND status = 'PENDING' AND lease_token = ? AND lease_until > statement_timestamp()
                """, event.eventId(), event.leaseToken()) == 1;
    }

    @Transactional
    public boolean recordFailure(ClaimedIntegrationEvent event, String diagnostic, boolean requiresReview,
                                  int maxAttempts, Duration retryDelay) {
        return namedJdbcTemplate.update("""
                UPDATE integration_event_outbox
                SET status = CASE WHEN attempt_count >= :maxAttempts OR :requiresReview THEN 'NEEDS_REVIEW' ELSE 'PENDING' END,
                    next_attempt_at = statement_timestamp() + :delayMillis * INTERVAL '1 millisecond',
                    last_error = :diagnostic, lease_token = NULL, lease_until = NULL
                WHERE event_id = :eventId AND status = 'PENDING' AND lease_token = :leaseToken
                  AND lease_until > statement_timestamp()
                """, Map.of("maxAttempts", maxAttempts, "requiresReview", requiresReview,
                "delayMillis", retryDelay.toMillis(), "diagnostic", diagnostic,
                "eventId", event.eventId(), "leaseToken", event.leaseToken())) == 1;
    }
}
