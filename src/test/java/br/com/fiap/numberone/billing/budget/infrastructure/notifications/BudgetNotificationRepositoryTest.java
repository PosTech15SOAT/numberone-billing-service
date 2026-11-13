package br.com.fiap.numberone.billing.budget.infrastructure.notifications;

import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetNotificationUnavailableException;
import br.com.fiap.numberone.billing.budget.infrastructure.config.BudgetNotificationProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BudgetNotificationRepositoryTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ApprovalTokenProtection protection = mock(ApprovalTokenProtection.class);

    @ParameterizedTest
    @CsvSource({"'',URL pública de aprovação ausente", "'https://[invalid',URL pública de aprovação malformada",
            "'http://billing.test/?token=secret',URL pública de aprovação não atende aos requisitos de segurança"})
    void shouldDistinguishUrlFailuresWithoutIncludingConfiguredValue(String url, String diagnostic) {
        assertThatThrownBy(() -> repository(url).validateConfiguration())
                .isInstanceOfSatisfying(BudgetNotificationUnavailableException.class, failure -> {
                    assertThat(failure).hasMessage(diagnostic);
                    if (url.equals("https://[invalid")) assertThat(failure).hasCauseInstanceOf(IllegalArgumentException.class);
                });
        verifyNoInteractions(jdbc);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "private@example.test\r\nBcc: secret"})
    void shouldRejectRecipientBeforeAccessingDatabaseWithoutIncludingItsValue(String recipient) {
        assertThatThrownBy(() -> repository("https://billing.test/decision").schedule(null, recipient))
                .isInstanceOf(BudgetNotificationUnavailableException.class)
                .hasMessage("Destinatário da notificação ausente ou inválido");
        verifyNoInteractions(jdbc, protection);
    }

    private BudgetNotificationRepository repository(String url) {
        var properties = new BudgetNotificationProperties("", url, Duration.ofHours(48), Duration.ofSeconds(30),
                1, 3, Duration.ofMinutes(2), Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(30), "");
        return new BudgetNotificationRepository(jdbc, protection, properties);
    }
}
