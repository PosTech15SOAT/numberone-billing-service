package br.com.fiap.numberone.billing.budget.infrastructure.outbox;

import br.com.fiap.numberone.billing.infrastructure.observability.ObservationLog;
import br.com.fiap.numberone.billing.budget.application.exceptions.IntegrationEventPublicationException;
import br.com.fiap.numberone.billing.budget.application.gateways.IntegrationEventPublicationGateway;
import br.com.fiap.numberone.billing.budget.infrastructure.config.OutboxPublisherProperties;
import br.com.fiap.numberone.billing.budget.infrastructure.logging.SafeExceptionDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.scheduling.annotation.Scheduled;

public class OutboxPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxPublisher.class);

    private final IntegrationEventOutboxRepository repository;
    private final IntegrationEventPublicationGateway gateway;
    private final OutboxPublisherProperties properties;

    public OutboxPublisher(IntegrationEventOutboxRepository repository, IntegrationEventPublicationGateway gateway,
                           OutboxPublisherProperties properties) {
        this.repository = repository;
        this.gateway = gateway;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${billing.outbox.poll-interval:1s}")
    public void publishPendingEvents() {
        try {
            var events = repository.claimAvailable(properties.batchSize(), properties.maxAttempts(), properties.leaseDuration());
            for (var event : events) {
                publish(event);
            }
        } catch (RuntimeException exception) {
            ObservationLog.safely(() -> LOGGER.atError().addKeyValue("event.code", "BUDGET_OUTBOX_CYCLE_FAILED")
                    .addKeyValue("error.type", exception.getClass().getName())
                    .addKeyValue("error.stack_trace", SafeExceptionDetails.stackTrace(exception))
                    .log("Falha no ciclo de publicação da Outbox"));
        }
    }

    private void publish(ClaimedIntegrationEvent event) {
        try {
            if (!repository.hasValidLease(event)) return;
            if (!tryPublishToQueue(event)) return;
            confirmPublication(event);
        } catch (RuntimeException exception) {
            // Uma confirmação local incerta mantém a posse até expirar; não repetir imediatamente o envio externo.
            ObservationLog.safely(() -> LOGGER.atError().addKeyValue("event.code", "BUDGET_OUTBOX_PERSISTENCE_FAILED")
                    .addKeyValue("event.id", event.eventId())
                    .addKeyValue("attempt.number", event.attemptCount())
                    .addKeyValue("error.type", exception.getClass().getName())
                    .addKeyValue("error.stack_trace", SafeExceptionDetails.stackTrace(exception))
                    .log("Falha de persistência no processamento do evento da Outbox"));
        }
    }

    private boolean tryPublishToQueue(ClaimedIntegrationEvent event) {
        try {
            gateway.publish(event.envelope());
            return true;
        } catch (RuntimeException exception) {
            recordPublicationFailure(event, exception);
            return false;
        }
    }

    private void recordPublicationFailure(ClaimedIntegrationEvent event, RuntimeException exception) {
        boolean requiresReview = exception instanceof IntegrationEventPublicationException failure && failure.requiresReview();
        String diagnostic = exception instanceof IntegrationEventPublicationException ? exception.getMessage() : "PUBLICATION_FAILED";
        boolean recorded = repository.recordFailure(event, diagnostic, requiresReview,
                properties.maxAttempts(), properties.retryDelay(event.attemptCount()));
        boolean exhausted = event.attemptCount() >= properties.maxAttempts();
        ObservationLog.safely(() -> LOGGER.atLevel(requiresReview || exhausted ? Level.ERROR : Level.WARN)
                .addKeyValue("event.code", "BUDGET_EVENT_PUBLICATION_FAILED")
                .addKeyValue("event.id", event.eventId())
                .addKeyValue("attempt.number", event.attemptCount())
                .addKeyValue("error.code", diagnostic)
                .addKeyValue("error.type", exception.getClass().getName())
                .addKeyValue("error.stack_trace", SafeExceptionDetails.stackTrace(exception))
                .addKeyValue("outbox.failure_recorded", recorded)
                .addKeyValue("retry.scheduled", recorded && !requiresReview && !exhausted)
                .log("Falha ao publicar evento de integração na SQS"));
    }

    private void confirmPublication(ClaimedIntegrationEvent event) {
        if (!repository.markPublished(event)) {
            ObservationLog.safely(() -> LOGGER.atWarn().addKeyValue("event.code", "BUDGET_EVENT_CONFIRMATION_LOST")
                    .addKeyValue("event.id", event.eventId())
                    .addKeyValue("attempt.number", event.attemptCount())
                    .log("Publicação aceita sem confirmação local de posse do evento"));
            return;
        }
        ObservationLog.safely(() -> LOGGER.atInfo().addKeyValue("event.code", "BUDGET_EVENT_PUBLISHED")
                .addKeyValue("event.id", event.eventId())
                .addKeyValue("attempt.number", event.attemptCount())
                .log("Evento de integração publicado na SQS e confirmado na Outbox"));
    }

}
