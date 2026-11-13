package br.com.fiap.numberone.billing.budget.infrastructure.outbox;

import br.com.fiap.numberone.billing.infrastructure.observability.support.FailingLogAppender;
import org.junit.jupiter.params.provider.ValueSource;
import br.com.fiap.numberone.billing.budget.infrastructure.config.OutboxPublisherProperties;
import br.com.fiap.numberone.billing.budget.application.exceptions.IntegrationEventPublicationException;
import br.com.fiap.numberone.billing.budget.support.EcsLogCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxPublisherTest {

    private final IntegrationEventOutboxRepository repository = mock(IntegrationEventOutboxRepository.class);
    private final OutboxPublisherProperties properties = new OutboxPublisherProperties(2, Duration.ofSeconds(30),
            Duration.ofSeconds(1), 3, Duration.ofSeconds(5), Duration.ofSeconds(12));

    @Test
    void shouldContinueOnNextCycleAfterClaimFailure() {
        when(repository.claimAvailable(2, 3, Duration.ofSeconds(30)))
                .thenThrow(new IllegalStateException("detalhe interno"))
                .thenReturn(List.of());
        var publisher = new OutboxPublisher(repository, body -> fail("Nenhum evento deveria ser enviado"), properties);
        publisher.publishPendingEvents();
        publisher.publishPendingEvents();
        verify(repository, times(2)).claimAvailable(2, 3, Duration.ofSeconds(30));
    }

    @Test
    void shouldSkipExpiredLeaseAndContinueOtherEventsAfterUncertainConfirmation() {
        var expired = event();
        var uncertain = event();
        var next = event();
        when(repository.claimAvailable(2, 3, Duration.ofSeconds(30))).thenReturn(List.of(expired, uncertain, next));
        when(repository.hasValidLease(expired)).thenReturn(false);
        when(repository.hasValidLease(uncertain)).thenReturn(true);
        when(repository.hasValidLease(next)).thenReturn(true);
        when(repository.markPublished(uncertain)).thenThrow(new IllegalStateException("Falha de confirmação"));
        when(repository.markPublished(next)).thenReturn(true);
        AtomicInteger sends = new AtomicInteger();
        new OutboxPublisher(repository, body -> sends.incrementAndGet(), properties).publishPendingEvents();
        assertThat(sends).hasValue(2);
        verify(repository, never()).markPublished(expired);
        verify(repository).markPublished(next);
        verify(repository, never()).recordFailure(any(), anyString(), anyBoolean(), anyInt(), any());
    }

    @Test
    void shouldBoundExponentialBackoffWithoutOverflow() {
        assertThat(properties.retryDelay(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.retryDelay(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.retryDelay(3)).isEqualTo(Duration.ofSeconds(12));
        assertThat(properties.retryDelay(Integer.MAX_VALUE)).isEqualTo(Duration.ofSeconds(12));
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,false", "false,true"})
    void shouldLogPublicationOnceOnlyWhenLocalStateIsConfirmed(boolean confirmed, boolean confirmationFails) throws Exception {
        var event = event();
        when(repository.claimAvailable(2, 3, Duration.ofSeconds(30))).thenReturn(List.of(event));
        when(repository.hasValidLease(event)).thenReturn(true);
        try (var logs = new EcsLogCapture(OutboxPublisher.class)) {
            when(repository.markPublished(event)).thenAnswer(invocation -> {
                assertThat(logs.events()).isEmpty();
                if (confirmationFails) throw new IllegalStateException("payload-private password-private");
                return confirmed;
            });
            new OutboxPublisher(repository, body -> assertThat(logs.events()).isEmpty(), properties).publishPendingEvents();
            assertThat(logs.events()).hasSize(1);
            var json = logs.events().getFirst();
            assertThat(json.path("event").path("code").asText()).isEqualTo(confirmed ? "BUDGET_EVENT_PUBLISHED"
                    : confirmationFails ? "BUDGET_OUTBOX_PERSISTENCE_FAILED" : "BUDGET_EVENT_CONFIRMATION_LOST");
            assertThat(json.path("event").path("id").asText()).isEqualTo(event.eventId().toString());
            assertThat(json.path("attempt").path("number").asInt()).isEqualTo(1);
            assertThat(json.toString()).doesNotContain("payload-private", "password-private");
        }
    }

    @ParameterizedTest
    @CsvSource({"TIMEOUT,1,true,WARN", "UNAVAILABLE,3,false,ERROR", "REJECTED,1,false,ERROR"})
    void shouldLogSafePublicationFailureAndRetryDecision(IntegrationEventPublicationException.Failure failure,
                                                        int attempt, boolean retry, String level) throws Exception {
        var event = new ClaimedIntegrationEvent(UUID.randomUUID(), "payload-private", UUID.randomUUID(), attempt);
        when(repository.claimAvailable(2, 3, Duration.ofSeconds(30))).thenReturn(List.of(event));
        when(repository.hasValidLease(event)).thenReturn(true);
        when(repository.recordFailure(eq(event), anyString(), anyBoolean(), eq(3), any())).thenReturn(true);
        var exception = new IntegrationEventPublicationException(failure, 503,
                new IllegalStateException("password-private email-private@example.test payload-private"));
        try (var logs = new EcsLogCapture(OutboxPublisher.class)) {
            new OutboxPublisher(repository, body -> { throw exception; }, properties).publishPendingEvents();
            assertThat(logs.events()).hasSize(1);
            var json = logs.events().getFirst();
            assertThat(json.path("event").path("code").asText()).isEqualTo("BUDGET_EVENT_PUBLICATION_FAILED");
            assertThat(json.path("log").path("level").asText()).isEqualTo(level);
            assertThat(json.path("error").path("code").asText()).contains(failure.name());
            assertThat(json.path("error").path("stack_trace").asText()).contains("IllegalStateException", "Caused by:");
            assertThat(json.path("retry").path("scheduled").asBoolean()).isEqualTo(retry);
            assertThat(json.toString()).doesNotContain("password-private", "email-private@example.test", "payload-private");
        }
        verify(repository, never()).markPublished(any());
    }

    private ClaimedIntegrationEvent event() {
        return new ClaimedIntegrationEvent(UUID.randomUUID(), "{}", UUID.randomUUID(), 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUBLISHED", "CONFIRMATION_LOST", "CONFIRMATION_FAILED", "PUBLICATION_FAILED", "FAILURE_RECORD_FAILED", "LEASE_LOST"})
    void shouldKeepBatchAndPublicationDecisionsWhenLoggingFails(String scenario) {
        var first = new ClaimedIntegrationEvent(UUID.randomUUID(), "{\"event\":\"first\"}", UUID.randomUUID(), 1);
        var next = event();
        var gateway = mock(br.com.fiap.numberone.billing.budget.application.gateways.IntegrationEventPublicationGateway.class);
        when(repository.claimAvailable(2, 3, Duration.ofSeconds(30))).thenReturn(List.of(first, next));
        when(repository.hasValidLease(any())).thenReturn(true);
        when(repository.markPublished(any())).thenReturn(true);
        String expectedCode = switch (scenario) {
            case "CONFIRMATION_LOST" -> {
                when(repository.markPublished(first)).thenReturn(false);
                yield "BUDGET_EVENT_CONFIRMATION_LOST";
            }
            case "CONFIRMATION_FAILED" -> {
                when(repository.markPublished(first)).thenThrow(new IllegalStateException("Falha de confirmação"));
                yield "BUDGET_OUTBOX_PERSISTENCE_FAILED";
            }
            case "PUBLICATION_FAILED", "FAILURE_RECORD_FAILED" -> {
                doThrow(new IllegalStateException("Falha externa")).when(gateway).publish(first.envelope());
                if (scenario.equals("FAILURE_RECORD_FAILED")) {
                    when(repository.recordFailure(eq(first), anyString(), anyBoolean(), anyInt(), any()))
                            .thenThrow(new IllegalStateException("Falha ao registrar retry"));
                } else {
                    when(repository.recordFailure(eq(first), anyString(), anyBoolean(), anyInt(), any())).thenReturn(true);
                }
                yield scenario.equals("PUBLICATION_FAILED") ? "BUDGET_EVENT_PUBLICATION_FAILED" : "BUDGET_OUTBOX_PERSISTENCE_FAILED";
            }
            case "LEASE_LOST" -> {
                when(repository.hasValidLease(first)).thenReturn(false);
                yield null;
            }
            default -> "BUDGET_EVENT_PUBLISHED";
        };
        try (var logging = new FailingLogAppender(OutboxPublisher.class)) {
            new OutboxPublisher(repository, gateway, properties).publishPendingEvents();
            assertThat(logging.eventCodes()).containsExactlyElementsOf(expectedCode == null
                    ? List.of("BUDGET_EVENT_PUBLISHED") : List.of(expectedCode, "BUDGET_EVENT_PUBLISHED"));
        }
        verify(repository).claimAvailable(2, 3, Duration.ofSeconds(30));
        verify(repository).hasValidLease(first);
        verify(repository).hasValidLease(next);
        verify(repository).markPublished(next);
        if (scenario.equals("PUBLICATION_FAILED") || scenario.equals("FAILURE_RECORD_FAILED")) {
            verify(repository).recordFailure(first, "PUBLICATION_FAILED", false, 3, properties.retryDelay(1));
            verify(repository, never()).markPublished(first);
        } else if (!scenario.equals("LEASE_LOST")) {
            verify(repository).markPublished(first);
        }
        verify(gateway, times(scenario.equals("LEASE_LOST") ? 1 : 2)).publish(anyString());
        verifyNoMoreInteractions(repository);
    }

    @ParameterizedTest
    @CsvSource({"TIMEOUT,1,true", "UNAVAILABLE,3,true", "REJECTED,1,true", "TIMEOUT,1,false"})
    void shouldKeepRetryAndReviewDecisionsWhenLoggingFails(IntegrationEventPublicationException.Failure kind, int attempt, boolean recorded) {
        var event = new ClaimedIntegrationEvent(UUID.randomUUID(), "{}", UUID.randomUUID(), attempt);
        when(repository.claimAvailable(2, 3, Duration.ofSeconds(30))).thenReturn(List.of(event));
        when(repository.hasValidLease(event)).thenReturn(true);
        when(repository.recordFailure(eq(event), anyString(), anyBoolean(), anyInt(), any())).thenReturn(recorded);
        var failure = new IntegrationEventPublicationException(kind, 503, new IllegalStateException("Causa original"));
        AtomicInteger sends = new AtomicInteger();
        try (var logging = new FailingLogAppender(OutboxPublisher.class)) {
            new OutboxPublisher(repository, body -> { sends.incrementAndGet(); throw failure; }, properties).publishPendingEvents();
            assertThat(logging.eventCodes()).containsExactly("BUDGET_EVENT_PUBLICATION_FAILED");
        }
        verify(repository).recordFailure(event, failure.getMessage(), failure.requiresReview(), 3, properties.retryDelay(attempt));
        verify(repository, never()).markPublished(any());
        assertThat(sends).hasValue(1);
    }

    @Test
    void shouldContinueOnNextCycleAfterClaimAndLoggingFailures() {
        when(repository.claimAvailable(2, 3, Duration.ofSeconds(30)))
                .thenThrow(new IllegalStateException("Falha ao obter lote")).thenReturn(List.of());
        var gateway = mock(br.com.fiap.numberone.billing.budget.application.gateways.IntegrationEventPublicationGateway.class);
        var publisher = new OutboxPublisher(repository, gateway, properties);
        try (var logging = new FailingLogAppender(OutboxPublisher.class)) {
            publisher.publishPendingEvents();
            publisher.publishPendingEvents();
            assertThat(logging.eventCodes()).containsExactly("BUDGET_OUTBOX_CYCLE_FAILED");
        }
        verify(repository, times(2)).claimAvailable(2, 3, Duration.ofSeconds(30));
        verifyNoInteractions(gateway);
    }

}
