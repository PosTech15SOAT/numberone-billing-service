package br.com.fiap.numberone.billing.budget.infrastructure.notifications;

import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetNotificationUnavailableException;
import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetResendTooSoonException;
import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetAuthorizationException;
import br.com.fiap.numberone.billing.budget.application.gateways.BudgetNotificationGateway;
import br.com.fiap.numberone.billing.budget.application.results.BudgetNotificationSummary;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.valueobjects.BudgetApprovalAuthorization;
import br.com.fiap.numberone.billing.budget.infrastructure.config.BudgetNotificationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class BudgetNotificationRepository implements BudgetNotificationGateway {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ApprovalTokenProtection protection;
    private final BudgetNotificationProperties properties;

    public BudgetNotificationRepository(JdbcTemplate jdbc, ApprovalTokenProtection protection, BudgetNotificationProperties properties) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.protection = protection;
        this.properties = properties;
    }

    public void validateConfiguration() {
        protection.validateConfiguration();
        approvalUrl("");
    }

    public BudgetApprovalAuthorization findAuthorization(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new BudgetAuthorizationException(BudgetAuthorizationException.Reason.INVALID);
        }
        return jdbc.query("""
                SELECT * FROM budget_approval_authorization WHERE token_hash = ?
                """, (row, index) -> BudgetApprovalAuthorization.restore(row.getObject("authorization_id", UUID.class),
                row.getObject("budget_id", UUID.class), row.getString("token_hash"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(), row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                row.getObject("revoked_at", OffsetDateTime.class) == null ? null : row.getObject("revoked_at", OffsetDateTime.class).toInstant(),
                row.getObject("consumed_at", OffsetDateTime.class) == null ? null : row.getObject("consumed_at", OffsetDateTime.class).toInstant()),
                protection.hash(token)).stream().findFirst()
                .orElseThrow(() -> new BudgetAuthorizationException(BudgetAuthorizationException.Reason.INVALID));
    }

    // Consulta informativa; decisões relêem a autorização e consomem o token sob o lock da OS.
    public boolean isAuthorizationUsable(String tokenHash) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM budget_approval_authorization a JOIN budget b ON b.budget_id = a.budget_id
                    WHERE a.token_hash = ? AND a.revoked_at IS NULL AND a.consumed_at IS NULL AND a.expires_at > statement_timestamp()
                      AND b.status = 'SENT' AND b.budget_version = (
                          SELECT MAX(budget_version) FROM budget WHERE service_order_id = b.service_order_id
                              AND status IN ('SENT', 'APPROVED', 'REJECTED')))
                """, Boolean.class, tokenHash));
    }

    public Instant currentTime() {
        return jdbc.queryForObject("SELECT statement_timestamp()", OffsetDateTime.class).toInstant();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void consumeAuthorization(UUID authorizationId) {
        int consumed = jdbc.update("""
                UPDATE budget_approval_authorization SET consumed_at = statement_timestamp()
                WHERE authorization_id = ? AND consumed_at IS NULL AND revoked_at IS NULL
                    AND created_at <= statement_timestamp() AND expires_at > statement_timestamp()
                """, authorizationId);
        if (consumed != 1) throw new BudgetAuthorizationException(BudgetAuthorizationException.Reason.EXPIRED);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeForServiceOrder(UUID serviceOrderId) {
        jdbc.update("""
                UPDATE budget_approval_authorization SET revoked_at = statement_timestamp()
                WHERE revoked_at IS NULL AND consumed_at IS NULL
                    AND budget_id IN (SELECT budget_id FROM budget WHERE service_order_id = ?)
                """, serviceOrderId);
        jdbc.update("""
                UPDATE budget_email_notification SET status = 'CANCELLED', protected_content = NULL,
                    lease_token = NULL, lease_until = NULL
                WHERE status = 'PENDING' AND budget_id IN (SELECT budget_id FROM budget WHERE service_order_id = ?)
                """, serviceOrderId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void schedule(Budget budget, String recipient) {
        if (recipient == null || recipient.isBlank() || recipient.contains("\r") || recipient.contains("\n")) {
            throw new BudgetNotificationUnavailableException("Destinatário da notificação ausente ou inválido");
        }
        UUID authorizationId = UUID.randomUUID();
        String token = protection.generateToken();
        OffsetDateTime now = jdbc.queryForObject("SELECT statement_timestamp()", OffsetDateTime.class);
        var authorization = BudgetApprovalAuthorization.create(authorizationId, budget.getBudgetId(), protection.hash(token),
                now.toInstant(), now.toInstant().plus(properties.authorizationTtl()));
        byte[] protectedContent = protection.protect(authorizationId, new BudgetApprovalEmail(recipient,
                budget.getBudgetId(), budget.getQuotedAmount(), approvalUrl(token), authorization.expiresAt()));
        revokeForServiceOrder(budget.getServiceOrderId());
        jdbc.update("""
                INSERT INTO budget_approval_authorization (authorization_id, budget_id, token_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, authorizationId, budget.getBudgetId(), authorization.tokenHash(), now,
                authorization.expiresAt().atOffset(ZoneOffset.UTC));
        jdbc.update("""
                INSERT INTO budget_email_notification
                    (notification_id, authorization_id, budget_id, protected_content, created_at, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), authorizationId, budget.getBudgetId(), protectedContent, now, now);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void validateResendInterval(UUID budgetId) {
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM budget_email_notification WHERE budget_id = ?
                    AND created_at > statement_timestamp() - ? * INTERVAL '1 millisecond')
                """, Boolean.class, budgetId, properties.resendInterval().toMillis()))) {
            throw new BudgetResendTooSoonException();
        }
    }

    @Override
    public Optional<BudgetNotificationSummary> findLatest(UUID budgetId) {
        return jdbc.query("""
                SELECT status, attempt_count, last_attempt_at, sent_at,
                    created_at <= statement_timestamp() - ? * INTERVAL '1 millisecond' AS interval_elapsed
                FROM budget_email_notification WHERE budget_id = ?
                ORDER BY created_at DESC, notification_id DESC LIMIT 1
                """, (row, index) -> new BudgetNotificationSummary(BudgetNotificationSummary.Status.valueOf(row.getString("status")),
                row.getInt("attempt_count"), row.getObject("last_attempt_at", OffsetDateTime.class),
                row.getObject("sent_at", OffsetDateTime.class), row.getBoolean("interval_elapsed")),
                properties.resendInterval().toMillis(), budgetId).stream().findFirst();
    }

    @Transactional
    public List<ClaimedBudgetNotification> claimAvailable(int limit, int maxAttempts, Duration leaseDuration) {
        return named.query("""
                WITH available AS (
                    SELECT n.notification_id,
                        a.revoked_at IS NULL AND a.consumed_at IS NULL AND a.expires_at > statement_timestamp() AND b.status = 'SENT'
                            AND b.budget_version = (SELECT MAX(budget_version) FROM budget
                                WHERE service_order_id = b.service_order_id AND status IN ('SENT', 'APPROVED', 'REJECTED')) AS valid
                    FROM budget_email_notification n
                    JOIN budget_approval_authorization a ON a.authorization_id = n.authorization_id
                    JOIN budget b ON b.budget_id = n.budget_id
                    WHERE n.status = 'PENDING' AND n.next_attempt_at <= statement_timestamp()
                        AND (n.lease_until IS NULL OR n.lease_until <= statement_timestamp())
                    ORDER BY n.next_attempt_at, n.created_at, n.notification_id
                    LIMIT :limit FOR UPDATE OF n SKIP LOCKED
                ), claimed AS (
                    UPDATE budget_email_notification n
                    SET status = CASE WHEN NOT available.valid THEN 'CANCELLED'
                                      WHEN attempt_count >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
                        protected_content = CASE WHEN NOT available.valid OR attempt_count >= :maxAttempts THEN NULL ELSE protected_content END,
                        lease_token = CASE WHEN NOT available.valid OR attempt_count >= :maxAttempts THEN NULL ELSE gen_random_uuid() END,
                        lease_until = CASE WHEN NOT available.valid OR attempt_count >= :maxAttempts THEN NULL
                            ELSE statement_timestamp() + :leaseMillis * INTERVAL '1 millisecond' END,
                        last_error = CASE WHEN available.valid AND attempt_count >= :maxAttempts
                            THEN 'ATTEMPTS_EXHAUSTED' ELSE last_error END,
                        last_attempt_at = CASE WHEN available.valid AND attempt_count < :maxAttempts
                            THEN statement_timestamp() ELSE last_attempt_at END,
                        attempt_count = CASE WHEN available.valid AND attempt_count < :maxAttempts THEN attempt_count + 1 ELSE attempt_count END
                    FROM available WHERE n.notification_id = available.notification_id RETURNING n.*
                )
                SELECT * FROM claimed WHERE status = 'PENDING'
                """, Map.of("limit", limit, "maxAttempts", maxAttempts, "leaseMillis", leaseDuration.toMillis()),
                (row, index) -> new ClaimedBudgetNotification(row.getObject("notification_id", UUID.class),
                        row.getObject("authorization_id", UUID.class), row.getObject("lease_token", UUID.class),
                        row.getInt("attempt_count"), row.getBytes("protected_content")));
    }

    @Transactional
    public boolean prepareDelivery(ClaimedBudgetNotification notification) {
        // O lock é curto e termina antes do SMTP; reenvio posterior ainda pode revogar o link durante a chamada.
        if (!lockPendingDelivery(notification)) return false;
        boolean authorizationUsable = isDeliveryAuthorizationUsable(notification.authorizationId());
        if (!authorizationUsable) cancelNotification(notification.notificationId());
        return authorizationUsable;
    }

    private boolean lockPendingDelivery(ClaimedBudgetNotification notification) {
        var rows = jdbc.query("""
                SELECT n.notification_id FROM budget_email_notification n
                WHERE n.notification_id = ? AND n.status = 'PENDING' AND n.lease_token = ?
                    AND n.lease_until > statement_timestamp() FOR UPDATE
                """, (row, index) -> row.getObject(1, UUID.class), notification.notificationId(), notification.leaseToken());
        return !rows.isEmpty();
    }

    private boolean isDeliveryAuthorizationUsable(UUID authorizationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM budget_approval_authorization a JOIN budget b ON b.budget_id = a.budget_id
                    WHERE a.authorization_id = ? AND a.revoked_at IS NULL AND a.consumed_at IS NULL AND a.expires_at > statement_timestamp()
                        AND b.status = 'SENT' AND b.budget_version = (SELECT MAX(budget_version) FROM budget
                            WHERE service_order_id = b.service_order_id AND status IN ('SENT', 'APPROVED', 'REJECTED')))
                """, Boolean.class, authorizationId));
    }

    private void cancelNotification(UUID notificationId) {
        jdbc.update("""
                UPDATE budget_email_notification SET status = 'CANCELLED', protected_content = NULL,
                    lease_token = NULL, lease_until = NULL WHERE notification_id = ?
                """, notificationId);
    }

    @Transactional
    public boolean markSent(ClaimedBudgetNotification notification) {
        return jdbc.update("""
                UPDATE budget_email_notification SET status = 'SENT', sent_at = statement_timestamp(),
                    protected_content = NULL, last_error = NULL, lease_token = NULL, lease_until = NULL
                WHERE notification_id = ? AND status = 'PENDING' AND lease_token = ? AND lease_until > statement_timestamp()
                """, notification.notificationId(), notification.leaseToken()) == 1;
    }

    @Transactional
    public boolean recordFailure(ClaimedBudgetNotification notification, String category, boolean permanent,
                                 int maxAttempts, Duration delay) {
        return named.update("""
                UPDATE budget_email_notification
                SET status = CASE WHEN :permanent OR attempt_count >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
                    protected_content = CASE WHEN :permanent OR attempt_count >= :maxAttempts THEN NULL ELSE protected_content END,
                    next_attempt_at = statement_timestamp() + :delayMillis * INTERVAL '1 millisecond',
                    last_error = :category, lease_token = NULL, lease_until = NULL
                WHERE notification_id = :id AND status = 'PENDING' AND lease_token = :leaseToken
                    AND lease_until > statement_timestamp()
                """, Map.of("permanent", permanent, "maxAttempts", maxAttempts, "delayMillis", delay.toMillis(),
                "category", category, "id", notification.notificationId(), "leaseToken", notification.leaseToken())) == 1;
    }

    private String approvalUrl(String token) {
        String configured = properties.approvalBaseUrl();
        if (configured == null || configured.isBlank()) {
            throw new BudgetNotificationUnavailableException("URL pública de aprovação ausente");
        }
        URI uri;
        try {
            uri = URI.create(configured);
        } catch (IllegalArgumentException exception) {
            throw new BudgetNotificationUnavailableException("URL pública de aprovação malformada", exception);
        }
        boolean local = "http".equals(uri.getScheme()) && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
        if ((!"https".equals(uri.getScheme()) && !local) || uri.getHost() == null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getUserInfo() != null) {
            throw new BudgetNotificationUnavailableException("URL pública de aprovação não atende aos requisitos de segurança");
        }
        return uri.toASCIIString() + "?token=" + token;
    }
}
