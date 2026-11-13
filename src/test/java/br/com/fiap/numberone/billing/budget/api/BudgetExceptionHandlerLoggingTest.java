package br.com.fiap.numberone.billing.budget.api;

import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetNotificationUnavailableException;
import br.com.fiap.numberone.billing.infrastructure.observability.support.FailingLogAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.CsvSource;
import br.com.fiap.numberone.billing.budget.api.exceptions.BudgetExceptionHandler;
import br.com.fiap.numberone.billing.budget.application.exceptions.ExternalServiceException;
import br.com.fiap.numberone.billing.budget.support.EcsLogCapture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetExceptionHandlerLoggingTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldKeepFailureTypesAndFramesWithoutLoggingExternalOrUnexpectedMessages(boolean external) throws Exception {
        String secrets = "private@example.test token-private password-private AES-private";
        var cause = new IllegalArgumentException(secrets);
        try (var logs = new EcsLogCapture(BudgetExceptionHandler.class)) {
            var handler = new BudgetExceptionHandler();
            var response = external
                    ? handler.externalService(new ExternalServiceException(secrets, ExternalServiceException.Failure.TIMEOUT, cause))
                    : handler.unexpected(new IllegalStateException(secrets, cause));
            assertThat(response.getStatusCode().value()).isEqualTo(external ? 504 : 500);
            assertThat(logs.events()).hasSize(1);
            var json = logs.events().getFirst();
            assertThat(json.path("event").path("code").asText())
                    .isEqualTo(external ? "BUDGET_EXTERNAL_SERVICE_FAILED" : "BUDGET_REQUEST_FAILED");
            assertThat(json.path("error").path("stack_trace").asText()).contains("Caused by:", "IllegalArgumentException");
            assertThat(json.toString()).doesNotContain("private@example.test", "token-private", "password-private", "AES-private");
        }
    }

    @ParameterizedTest
    @CsvSource({"TIMEOUT,504", "UNAVAILABLE,503", "INVALID_RESPONSE,502"})
    void shouldPreserveExternalErrorResponseWhenLoggingFails(ExternalServiceException.Failure kind, int status) {
        var cause = new IllegalStateException("Falha externa");
        var failure = new ExternalServiceException("Integração indisponível", kind, cause);
        var handler = new BudgetExceptionHandler();
        var expected = handler.externalService(failure);
        try (var logging = new FailingLogAppender(BudgetExceptionHandler.class)) {
            var response = handler.externalService(failure);
            assertThat(response).isEqualTo(expected);
            assertThat(response.getStatusCode().value()).isEqualTo(status);
            assertThat(failure.getCause()).isSameAs(cause);
            assertThat(logging.eventCodes()).containsExactly("BUDGET_EXTERNAL_SERVICE_FAILED");
        }
    }

    @Test
    void shouldPreserveNotificationUnavailableResponseWhenLoggingFails() {
        var failure = new BudgetNotificationUnavailableException();
        var handler = new BudgetExceptionHandler();
        var expected = handler.notificationUnavailable(failure);
        try (var logging = new FailingLogAppender(BudgetExceptionHandler.class)) {
            assertThat(handler.notificationUnavailable(failure)).isEqualTo(expected);
            assertThat(expected.getStatusCode().value()).isEqualTo(503);
            assertThat(logging.eventCodes()).containsExactly("BUDGET_NOTIFICATION_PREPARATION_FAILED");
        }
    }

    @Test
    void shouldPreserveUnexpectedErrorResponseWhenLoggingFails() {
        var failure = new IllegalStateException("Falha funcional");
        var handler = new BudgetExceptionHandler();
        var expected = handler.unexpected(failure);
        try (var logging = new FailingLogAppender(BudgetExceptionHandler.class)) {
            assertThat(handler.unexpected(failure)).isEqualTo(expected);
            assertThat(expected.getStatusCode().value()).isEqualTo(500);
            assertThat(logging.eventCodes()).containsExactly("BUDGET_REQUEST_FAILED");
        }
    }

}
