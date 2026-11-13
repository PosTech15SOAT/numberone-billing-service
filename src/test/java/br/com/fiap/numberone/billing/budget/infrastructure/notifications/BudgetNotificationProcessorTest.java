package br.com.fiap.numberone.billing.budget.infrastructure.notifications;

import br.com.fiap.numberone.billing.infrastructure.observability.support.FailingLogAppender;
import org.junit.jupiter.params.provider.ValueSource;
import br.com.fiap.numberone.billing.budget.infrastructure.config.BudgetNotificationProperties;
import br.com.fiap.numberone.billing.budget.support.EcsLogCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BudgetNotificationProcessorTest {
    private final BudgetNotificationRepository repository = mock(BudgetNotificationRepository.class);
    private final ApprovalTokenProtection protection = mock(ApprovalTokenProtection.class);
    private final SmtpBudgetEmailSender sender = mock(SmtpBudgetEmailSender.class);
    private final BudgetNotificationProperties properties = new BudgetNotificationProperties("", "",
            Duration.ofHours(48), Duration.ofSeconds(30), 1, 3, Duration.ofMinutes(2), Duration.ofSeconds(5),
            Duration.ofSeconds(30), Duration.ofMinutes(30), "");
    private final BudgetNotificationProcessor processor = new BudgetNotificationProcessor(repository, protection, sender, properties);
    private final ClaimedBudgetNotification notification = new ClaimedBudgetNotification(UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), 1, new byte[] {1, 2, 3});

    @Test
    void shouldLogSmtpAcceptanceOnceOnlyAfterLocalConfirmationInRealEcsJson() throws Exception {
        prepare(notification);
        when(repository.markSent(notification)).thenReturn(true);
        try (var logs = new EcsLogCapture(BudgetNotificationProcessor.class)) {
            doAnswer(invocation -> {
                assertThat(logs.events()).isEmpty();
                return true;
            }).when(repository).markSent(notification);
            processor.processPending();
            assertThat(logs.events()).hasSize(1);
            var json = logs.events().getFirst();
            assertThat(json.path("ecs").path("version").asText()).isEqualTo("8.11");
            assertThat(json.path("service").path("name").asText()).isEqualTo("numberone-billing-service");
            assertThat(json.path("log").path("level").asText()).isEqualTo("INFO");
            assertThat(json.path("event").path("code").asText()).isEqualTo("BUDGET_NOTIFICATION_SENT");
            assertThat(json.path("notification").path("id").asText()).isEqualTo(notification.notificationId().toString());
            assertThat(json.path("attempt").path("number").asInt()).isEqualTo(1);
            assertThat(json.has("event.code")).isFalse();
            assertThat(json.toString()).doesNotContain("private@example.test", "secret-token", "secret-key", "protected_content");
        }
        verify(sender).send(any());
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "true,true"})
    void shouldNotReportSuccessWhenLeaseIsLostOrConfirmationFails(boolean prepared, boolean confirmationFails) throws Exception {
        prepare(notification);
        when(repository.prepareDelivery(notification)).thenReturn(prepared);
        if (confirmationFails) when(repository.markSent(notification)).thenThrow(new IllegalStateException("secret-token"));
        try (var logs = new EcsLogCapture(BudgetNotificationProcessor.class)) {
            processor.processPending();
            assertThat(logs.events()).noneSatisfy(event ->
                    assertThat(event.path("event").path("code").asText()).isEqualTo("BUDGET_NOTIFICATION_SENT"));
            assertThat(logs.events().toString()).doesNotContain("secret-token");
        }
        if (!prepared) verifyNoInteractions(sender, protection);
    }

    @ParameterizedTest
    @CsvSource({"false,1,true,WARN", "false,3,false,ERROR", "true,1,false,ERROR"})
    void shouldLogFailureClassificationRetryAndSafeCauseChain(boolean authentication, int attempt,
                                                              boolean retry, String level) throws Exception {
        var claimed = new ClaimedBudgetNotification(notification.notificationId(), notification.authorizationId(),
                notification.leaseToken(), attempt, notification.protectedContent());
        prepare(claimed);
        String secret = "private@example.test secret-token secret-password secret-key";
        var cause = new IllegalArgumentException(secret);
        RuntimeException failure = authentication ? new MailAuthenticationException(secret, cause)
                : new MailSendException(secret, cause);
        doThrow(failure).when(sender).send(any());
        when(repository.recordFailure(eq(claimed), anyString(), eq(authentication), eq(3), any())).thenReturn(true);
        try (var logs = new EcsLogCapture(BudgetNotificationProcessor.class)) {
            processor.processPending();
            assertThat(logs.events()).hasSize(1);
            var json = logs.events().getFirst();
            assertThat(json.path("event").path("code").asText()).isEqualTo("BUDGET_NOTIFICATION_SEND_FAILED");
            assertThat(json.path("log").path("level").asText()).isEqualTo(level);
            assertThat(json.path("error").path("code").asText())
                    .isEqualTo(authentication ? "SMTP_AUTHENTICATION_FAILED" : "SMTP_SEND_FAILED");
            assertThat(json.path("retry").path("scheduled").asBoolean()).isEqualTo(retry);
            assertThat(json.path("attempt").path("number").asInt()).isEqualTo(attempt);
            assertThat(json.path("error").path("stack_trace").asText())
                    .contains(failure.getClass().getName(), IllegalArgumentException.class.getName(), "Caused by:");
            assertThat(json.toString()).doesNotContain("private@example.test", "secret-token", "secret-password", "secret-key");
        }
        verify(repository, never()).markSent(any());
    }

    private void prepare(ClaimedBudgetNotification claimed) {
        when(repository.claimAvailable(1, 3, Duration.ofMinutes(2))).thenReturn(List.of(claimed));
        when(repository.prepareDelivery(claimed)).thenReturn(true);
        when(protection.unprotect(claimed.authorizationId(), claimed.protectedContent())).thenReturn(
                new BudgetApprovalEmail("private@example.test", UUID.randomUUID(), BigDecimal.TEN,
                        "https://billing.test/decision?token=secret-token", Instant.now().plusSeconds(60)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SENT", "CONFIRMATION_LOST", "CONFIRMATION_FAILED", "SEND_FAILED", "FAILURE_RECORD_FAILED", "LEASE_LOST"})
    void shouldKeepBatchAndDeliveryDecisionsWhenLoggingFails(String scenario) {
        var next = new ClaimedBudgetNotification(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, new byte[] {4});
        prepare(notification);
        prepare(next);
        when(repository.claimAvailable(1, 3, Duration.ofMinutes(2))).thenReturn(List.of(notification, next));
        when(repository.markSent(notification)).thenReturn(true);
        when(repository.markSent(next)).thenReturn(true);
        String expectedCode = switch (scenario) {
            case "CONFIRMATION_LOST" -> {
                when(repository.markSent(notification)).thenReturn(false);
                yield "BUDGET_NOTIFICATION_CONFIRMATION_LOST";
            }
            case "CONFIRMATION_FAILED" -> {
                when(repository.markSent(notification)).thenThrow(new IllegalStateException("Falha de confirmação"));
                yield "BUDGET_NOTIFICATION_PERSISTENCE_FAILED";
            }
            case "SEND_FAILED", "FAILURE_RECORD_FAILED" -> {
                var email = protection.unprotect(notification.authorizationId(), notification.protectedContent());
                doThrow(new MailSendException("Falha SMTP")).when(sender).send(email);
                if (scenario.equals("FAILURE_RECORD_FAILED")) {
                    when(repository.recordFailure(eq(notification), anyString(), anyBoolean(), anyInt(), any()))
                            .thenThrow(new IllegalStateException("Falha ao registrar retry"));
                } else {
                    when(repository.recordFailure(eq(notification), anyString(), anyBoolean(), anyInt(), any())).thenReturn(true);
                }
                yield scenario.equals("SEND_FAILED") ? "BUDGET_NOTIFICATION_SEND_FAILED" : "BUDGET_NOTIFICATION_PERSISTENCE_FAILED";
            }
            case "LEASE_LOST" -> {
                when(repository.prepareDelivery(notification)).thenReturn(false);
                yield null;
            }
            default -> "BUDGET_NOTIFICATION_SENT";
        };
        try (var logging = new FailingLogAppender(BudgetNotificationProcessor.class)) {
            processor.processPending();
            assertThat(logging.eventCodes()).containsExactlyElementsOf(expectedCode == null
                    ? List.of("BUDGET_NOTIFICATION_SENT") : List.of(expectedCode, "BUDGET_NOTIFICATION_SENT"));
        }
        verify(repository).claimAvailable(1, 3, Duration.ofMinutes(2));
        verify(repository).prepareDelivery(notification);
        verify(repository).prepareDelivery(next);
        verify(repository).markSent(next);
        if (scenario.equals("SEND_FAILED") || scenario.equals("FAILURE_RECORD_FAILED")) {
            verify(repository).recordFailure(notification, "SMTP_SEND_FAILED", false, 3, properties.retryDelay(1));
            verify(repository, never()).markSent(notification);
        } else if (!scenario.equals("LEASE_LOST")) {
            verify(repository).markSent(notification);
        }
        verify(sender, times(scenario.equals("LEASE_LOST") ? 1 : 2)).send(any());
        verifyNoMoreInteractions(repository);
    }

    @ParameterizedTest
    @CsvSource({"false,1,true", "false,3,true", "true,1,true", "false,1,false"})
    void shouldKeepRetryAndPermanentFailureDecisionsWhenLoggingFails(boolean permanent, int attempt, boolean recorded) {
        var claimed = new ClaimedBudgetNotification(notification.notificationId(), notification.authorizationId(),
                notification.leaseToken(), attempt, notification.protectedContent());
        prepare(claimed);
        RuntimeException failure = permanent ? new MailAuthenticationException("Falha de autenticação") : new MailSendException("Falha SMTP");
        doThrow(failure).when(sender).send(any());
        String category = permanent ? "SMTP_AUTHENTICATION_FAILED" : "SMTP_SEND_FAILED";
        when(repository.recordFailure(eq(claimed), anyString(), anyBoolean(), anyInt(), any())).thenReturn(recorded);
        try (var logging = new FailingLogAppender(BudgetNotificationProcessor.class)) {
            processor.processPending();
            assertThat(logging.eventCodes()).containsExactly("BUDGET_NOTIFICATION_SEND_FAILED");
        }
        verify(repository).recordFailure(claimed, category, permanent, 3, properties.retryDelay(attempt));
        verify(repository, never()).markSent(any());
        verify(sender).send(any());
    }

    @Test
    void shouldContinueOnNextCycleAfterClaimAndLoggingFailures() {
        when(repository.claimAvailable(1, 3, Duration.ofMinutes(2)))
                .thenThrow(new IllegalStateException("Falha ao obter lote")).thenReturn(List.of());
        try (var logging = new FailingLogAppender(BudgetNotificationProcessor.class)) {
            processor.processPending();
            processor.processPending();
            assertThat(logging.eventCodes()).containsExactly("BUDGET_NOTIFICATION_CYCLE_FAILED");
        }
        verify(repository, times(2)).claimAvailable(1, 3, Duration.ofMinutes(2));
        verifyNoInteractions(sender, protection);
    }

}
