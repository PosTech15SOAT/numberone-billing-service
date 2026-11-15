package br.com.fiap.numberone.billing.infrastructure.observability;

import br.com.fiap.numberone.billing.infrastructure.observability.support.FailingLogAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.*;

class ObservationLogTest {
    @Test
    void shouldProveBackendFailureEscapesWithoutProtection() {
        try (var logging = new FailingLogAppender(ObservationLogTest.class)) {
            assertThatThrownBy(() -> LoggerFactory.getLogger(ObservationLogTest.class).atWarn()
                    .addKeyValue("event.code", "UNPROTECTED_PROBE").log("Teste de falha do backend"))
                    .isSameAs(logging.failure());
            assertThat(logging.eventCodes()).containsExactly("UNPROTECTED_PROBE");
        }
    }

    @Test
    void shouldIsolateSameBackendFailureWithExistingProtection() {
        try (var logging = new FailingLogAppender(ObservationLogTest.class)) {
            assertThatCode(() -> ObservationLog.safely(() -> LoggerFactory.getLogger(ObservationLogTest.class).atWarn()
                    .addKeyValue("event.code", "PROTECTED_PROBE").log("Teste de falha do backend")))
                    .doesNotThrowAnyException();
            assertThat(logging.eventCodes()).containsExactly("PROTECTED_PROBE");
        }
    }
}
