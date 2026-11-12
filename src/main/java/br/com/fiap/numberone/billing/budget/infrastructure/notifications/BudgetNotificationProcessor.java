package br.com.fiap.numberone.billing.budget.infrastructure.notifications;

import br.com.fiap.numberone.billing.infrastructure.observability.ObservationLog;
import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetNotificationUnavailableException;
import br.com.fiap.numberone.billing.budget.infrastructure.config.BudgetNotificationProperties;
import br.com.fiap.numberone.billing.budget.infrastructure.logging.SafeExceptionDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.scheduling.annotation.Scheduled;

public class BudgetNotificationProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(BudgetNotificationProcessor.class);
    private final BudgetNotificationRepository repository;
    private final ApprovalTokenProtection protection;
    private final SmtpBudgetEmailSender sender;
    private final BudgetNotificationProperties properties;

    public BudgetNotificationProcessor(BudgetNotificationRepository repository, ApprovalTokenProtection protection,
                                       SmtpBudgetEmailSender sender, BudgetNotificationProperties properties) {
        this.repository = repository;
        this.protection = protection;
        this.sender = sender;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${billing.notifications.poll-interval:5s}")
    public void processPending() {
        try {
            var notifications = repository.claimAvailable(properties.batchSize(), properties.maxAttempts(), properties.leaseDuration());
            for (var notification : notifications) deliver(notification);
        } catch (RuntimeException exception) {
            ObservationLog.safely(() -> LOGGER.atError().addKeyValue("event.code", "BUDGET_NOTIFICATION_CYCLE_FAILED")
                    .addKeyValue("error.type", exception.getClass().getName())
                    .addKeyValue("error.stack_trace", SafeExceptionDetails.stackTrace(exception))
                    .log("Falha no ciclo de notificações de orçamento"));
        }
    }

    private void deliver(ClaimedBudgetNotification notification) {
        try {
            if (!repository.prepareDelivery(notification)) return;
            if (!trySendEmail(notification)) return;
            confirmDelivery(notification);
        } catch (RuntimeException exception) {
            // Confirmação incerta: conservar posse e permitir recuperação posterior, sem novo SMTP imediato.
            ObservationLog.safely(() -> LOGGER.atError().addKeyValue("event.code", "BUDGET_NOTIFICATION_PERSISTENCE_FAILED")
                    .addKeyValue("notification.id", notification.notificationId())
                    .addKeyValue("attempt.number", notification.attemptCount())
                    .addKeyValue("error.type", exception.getClass().getName())
                    .addKeyValue("error.stack_trace", SafeExceptionDetails.stackTrace(exception))
                    .log("Falha de persistência no processamento da notificação"));
        }
    }

    private boolean trySendEmail(ClaimedBudgetNotification notification) {
        try {
            sender.send(protection.unprotect(notification.authorizationId(), notification.protectedContent()));
            return true;
        } catch (RuntimeException exception) {
            recordSendFailure(notification, exception);
            return false;
        }
    }

    private void recordSendFailure(ClaimedBudgetNotification notification, RuntimeException exception) {
        boolean permanent = isPermanentFailure(exception);
        String category = failureCategory(exception);
        boolean recorded = repository.recordFailure(notification, category, permanent, properties.maxAttempts(),
                properties.retryDelay(notification.attemptCount()));
        boolean exhausted = notification.attemptCount() >= properties.maxAttempts();
        ObservationLog.safely(() -> LOGGER.atLevel(permanent || exhausted ? Level.ERROR : Level.WARN)
                .addKeyValue("event.code", "BUDGET_NOTIFICATION_SEND_FAILED")
                .addKeyValue("notification.id", notification.notificationId())
                .addKeyValue("attempt.number", notification.attemptCount())
                .addKeyValue("error.code", category)
                .addKeyValue("error.type", exception.getClass().getName())
                .addKeyValue("error.stack_trace", SafeExceptionDetails.stackTrace(exception))
                .addKeyValue("notification.failure_recorded", recorded)
                .addKeyValue("retry.scheduled", recorded && !permanent && !exhausted)
                .log("Falha ao enviar notificação de orçamento"));
    }

    private boolean isPermanentFailure(RuntimeException exception) {
        return exception instanceof BudgetNotificationUnavailableException
                || exception instanceof MailAuthenticationException || exception instanceof MailParseException
                || exception instanceof MailPreparationException;
    }

    private String failureCategory(RuntimeException exception) {
        if (exception instanceof BudgetNotificationUnavailableException) return "CONTENT_UNAVAILABLE";
        if (exception instanceof MailAuthenticationException) return "SMTP_AUTHENTICATION_FAILED";
        return "SMTP_SEND_FAILED";
    }

    private void confirmDelivery(ClaimedBudgetNotification notification) {
        if (!repository.markSent(notification)) {
            ObservationLog.safely(() -> LOGGER.atWarn().addKeyValue("event.code", "BUDGET_NOTIFICATION_CONFIRMATION_LOST")
                    .addKeyValue("notification.id", notification.notificationId())
                    .addKeyValue("attempt.number", notification.attemptCount())
                    .log("SMTP aceitou a notificação sem confirmação local da posse"));
            return;
        }
        ObservationLog.safely(() -> LOGGER.atInfo().addKeyValue("event.code", "BUDGET_NOTIFICATION_SENT")
                .addKeyValue("notification.id", notification.notificationId())
                .addKeyValue("attempt.number", notification.attemptCount())
                .log("Servidor SMTP aceitou a notificação e o estado local foi confirmado"));
    }

}
