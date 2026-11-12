package br.com.fiap.numberone.billing.budget.infrastructure.notifications;

import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetNotificationUnavailableException;
import br.com.fiap.numberone.billing.budget.domain.valueobjects.BudgetApprovalAuthorization;
import br.com.fiap.numberone.billing.budget.infrastructure.config.BudgetNotificationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class ApprovalTokenProtectionTest {
    private final BudgetNotificationProperties properties = new BudgetNotificationProperties(
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "https://billing.test/decision", Duration.ofHours(48),
            Duration.ofSeconds(30), 1, 3, Duration.ofMinutes(2), Duration.ofSeconds(5), Duration.ofSeconds(30),
            Duration.ofMinutes(30), "billing@example.test");
    private final ApprovalTokenProtection protection = new ApprovalTokenProtection(properties, JsonMapper.builder().build());

    @Test
    void shouldGenerateUnpredictableUrlSafeTokensAndHashOnlyForValidation() {
        var hashes = new HashSet<String>();
        for (int i = 0; i < 500; i++) {
            String token = protection.generateToken();
            assertThat(token).matches("[A-Za-z0-9_-]{43}");
            assertThat(protection.hash(token)).matches("[a-f0-9]{64}").isNotEqualTo(token);
            hashes.add(protection.hash(token));
        }
        assertThat(hashes).hasSize(500);
    }

    @Test
    void shouldEncryptDeliveryContentWithRandomNonceAndBindItToAuthorization() {
        UUID authorizationId = UUID.randomUUID();
        String token = protection.generateToken();
        var email = new BudgetApprovalEmail("customer@example.test", UUID.randomUUID(), new BigDecimal("123.4567"),
                "https://billing.test/decision?token=" + token, Instant.now().plusSeconds(172800));
        byte[] first = protection.protect(authorizationId, email);
        assertThat(first).isNotEqualTo(protection.protect(authorizationId, email));
        assertThat(new String(first, StandardCharsets.ISO_8859_1)).doesNotContain(token, email.recipient(), email.approvalUrl());
        assertThat(protection.unprotect(authorizationId, first)).isEqualTo(email);
        assertThatThrownBy(() -> protection.unprotect(UUID.randomUUID(), first))
                .isInstanceOf(BudgetNotificationUnavailableException.class).hasMessageNotContaining(token);
        first[first.length - 1] ^= 1;
        assertThatThrownBy(() -> protection.unprotect(authorizationId, first)).isInstanceOf(BudgetNotificationUnavailableException.class);
        assertThat(email.toString()).doesNotContain(token, email.recipient());
        assertThat(properties.toString()).doesNotContain(properties.encryptionKey());
    }

    @Test
    void shouldRejectMissingOrInvalidKeyWithoutLeakingContent() {
        var invalid = new BudgetNotificationProperties("", properties.approvalBaseUrl(), properties.authorizationTtl(),
                properties.resendInterval(), 1, 3, properties.leaseDuration(), properties.pollInterval(),
                properties.initialBackoff(), properties.maxBackoff(), properties.from());
        var unavailable = new ApprovalTokenProtection(invalid, JsonMapper.builder().build());
        assertThatThrownBy(() -> unavailable.protect(UUID.randomUUID(),
                new BudgetApprovalEmail("test@example.test", UUID.randomUUID(), BigDecimal.TEN, "https://test/?token=secret", Instant.now())))
                .isInstanceOf(BudgetNotificationUnavailableException.class).hasMessageNotContaining("secret").hasMessageNotContaining("test@example.test");
    }

    @ParameterizedTest
    @CsvSource({"'',Chave AES da notificação ausente", "'%%%',Chave AES da notificação não está em Base64 válido",
            "YQ==,Chave AES da notificação deve conter 32 bytes"})
    void shouldDistinguishKeyConfigurationFailures(String key, String diagnostic) {
        var configuration = new BudgetNotificationProperties(key, properties.approvalBaseUrl(), properties.authorizationTtl(),
                properties.resendInterval(), 1, 3, properties.leaseDuration(), properties.pollInterval(),
                properties.initialBackoff(), properties.maxBackoff(), properties.from());
        var invalid = new ApprovalTokenProtection(configuration, JsonMapper.builder().build());
        assertThatThrownBy(invalid::validateConfiguration)
                .isInstanceOfSatisfying(BudgetNotificationUnavailableException.class, failure -> {
                    assertThat(failure).hasMessage(diagnostic);
                    if ("%%%".equals(key)) assertThat(failure).hasCauseInstanceOf(IllegalArgumentException.class);
                    else assertThat(failure.getCause()).isNull();
                });
    }

    @Test
    void shouldPreserveSerializationCauseWithoutIncludingEmailOrTokenInDiagnostic() {
        var mapper = org.mockito.Mockito.mock(JsonMapper.class);
        var cause = new IllegalStateException("sensitive-email-and-token");
        var email = new BudgetApprovalEmail("private@example.test", UUID.randomUUID(), BigDecimal.TEN,
                "https://billing.test/?token=secret", Instant.now());
        org.mockito.Mockito.when(mapper.writeValueAsBytes(email)).thenThrow(cause);
        assertThatThrownBy(() -> new ApprovalTokenProtection(properties, mapper).protect(UUID.randomUUID(), email))
                .isInstanceOf(BudgetNotificationUnavailableException.class)
                .hasMessage("Falha ao criptografar o conteúdo da notificação").hasCause(cause);
    }

    @Test
    void shouldDistinguishInvalidProtectedFormatFromAuthenticationFailure() {
        assertThatThrownBy(() -> protection.unprotect(UUID.randomUUID(), new byte[0]))
                .hasMessage("Formato do conteúdo protegido da notificação inválido").hasNoCause();
        UUID id = UUID.randomUUID();
        byte[] content = protection.protect(id, new BudgetApprovalEmail("private@example.test", UUID.randomUUID(),
                BigDecimal.TEN, "https://billing.test/?token=secret", Instant.now()));
        content[content.length - 1] ^= 1;
        assertThatThrownBy(() -> protection.unprotect(id, content))
                .hasMessage("Falha ao descriptografar ou ler o conteúdo da notificação")
                .hasCauseInstanceOf(javax.crypto.AEADBadTagException.class);
    }

    @Test
    void shouldCheckExactExpiryAndRevocationWithoutChangingCommercialState() {
        Instant now = Instant.now();
        var authorization = BudgetApprovalAuthorization.create(UUID.randomUUID(), UUID.randomUUID(), protection.hash("token"),
                now, now.plusSeconds(172800));
        assertThat(authorization.isActiveAt(now)).isTrue();
        assertThat(authorization.isActiveAt(authorization.expiresAt())).isFalse();
        assertThat(authorization.isActiveAt(now.minusSeconds(1))).isFalse();
        var revoked = BudgetApprovalAuthorization.restore(authorization.authorizationId(), authorization.budgetId(), authorization.tokenHash(),
                authorization.createdAt(), authorization.expiresAt(), now.plusSeconds(1));
        assertThat(revoked.isActiveAt(now.plusSeconds(2))).isFalse();
        var consumed = BudgetApprovalAuthorization.restore(authorization.authorizationId(), authorization.budgetId(), authorization.tokenHash(),
                authorization.createdAt(), authorization.expiresAt(), null, now.plusSeconds(1));
        assertThat(consumed.isActiveAt(now.plusSeconds(2))).isFalse();
    }

    @Test
    void shouldApplyBoundedBackoff() {
        assertThat(properties.retryDelay(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.retryDelay(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.retryDelay(100)).isEqualTo(Duration.ofMinutes(30));
    }
}
