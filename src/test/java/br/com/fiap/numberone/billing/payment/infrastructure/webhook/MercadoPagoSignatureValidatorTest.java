package br.com.fiap.numberone.billing.payment.infrastructure.webhook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import com.mercadopago.webhook.WebhookSignatureValidator;
import com.mercadopago.exceptions.MPInvalidWebhookSignatureException;
import com.mercadopago.exceptions.SignatureFailureReason;
import static br.com.fiap.numberone.billing.payment.support.MercadoPagoWebhookFixtures.SECRET;
import static br.com.fiap.numberone.billing.payment.support.MercadoPagoWebhookFixtures.signature;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MercadoPagoSignatureValidatorTest {
    private final MercadoPagoSignatureValidator validator = new MercadoPagoSignatureValidator(SECRET);

    @Test
    void shouldValidateFixedVectorDirectlyWithOfficialSdkWithoutHttpDependencies() throws Exception {
        WebhookSignatureValidator.validate("ts=1742505638683,v1=468a322b4aefdf6d95eb719415321f1f6841c0737f64a5a4e63e43bb595c5b7f",
                "request-123", "ORD01ABC", SECRET);
        assertThatThrownBy(() -> WebhookSignatureValidator.validate(signature("ORDTST01ABC", "request-123", "1"),
                "request-123", "ORDTST01ABC", "incorrect-fictitious-secret"))
                .isInstanceOfSatisfying(MPInvalidWebhookSignatureException.class,
                        failure -> assertThat(failure.getReason()).isEqualTo(SignatureFailureReason.SIGNATURE_MISMATCH));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ORDTST01M28P44G5FG8RJPM579EH56FV", "ordtst01m28p44g5fg8rjpm579eh56fv", "OrDtSt01M28p44G5fG8rJpM579eH56fV", "123456789"})
    void shouldPreserveQueryIdCaseAsCurrentOfficialSdkDoes(String orderId) {
        assertThat(validator.validate(signature(orderId, "request-123", "1742505638683"), "request-123", orderId))
                .isEqualTo(MercadoPagoSignatureValidator.Strategy.ORIGINAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ORDTST01ABC", "OrDtSt01AbC"})
    void shouldAcceptOnlyAuthenticLowercaseManifestForUppercaseOrMixedTestOrder(String id) {
        assertThat(validator.validate(signature(id.toLowerCase(java.util.Locale.ROOT), "request-123", "1742505638683"),
                "request-123", id)).isEqualTo(MercadoPagoSignatureValidator.Strategy.LOWERCASE_COMPATIBILITY);
    }

    @Test
    void shouldReportMismatchForDifferentLoadedSecretEvenWithUnchangedManifest() {
        MercadoPagoSignatureValidator differentSecret = new MercadoPagoSignatureValidator("another-fictitious-secret");
        assertThatThrownBy(() -> differentSecret.validate(
                signature("ORDTST01ABC", "request-123", "1742505638683"), "request-123", "ORDTST01ABC"))
                .isInstanceOfSatisfying(InvalidOrderNotificationException.class,
                        failure -> assertThat(failure.getReason()).isEqualTo(InvalidOrderNotificationException.Reason.SIGNATURE_MISMATCH));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void shouldOmitMissingOrBlankRequestIdForUppercaseTestOrder(String requestId) {
        validator.validate(signature("ORDTST01ABC", null, "1742505638683"), requestId, "ORDTST01ABC");
    }

    @Test
    void shouldValidateOfficialManifestWithMillisecondTimestampAndOriginalIdCase() {
        validator.validate("ts=1742505638683,v1=468a322b4aefdf6d95eb719415321f1f6841c0737f64a5a4e63e43bb595c5b7f", "request-123", "ORD01ABC");
        assertThat(validator.validate(signature("ord01abc", "request-123", "1742505638683"), "request-123", "ORD01ABC"))
                .isEqualTo(MercadoPagoSignatureValidator.Strategy.LOWERCASE_COMPATIBILITY);
    }

    @Test
    void shouldOmitAbsentRequestIdAsOfficialSdkDoes() {
        validator.validate(signature("ORD01ABC", null, "1742505638"), null, "ORD01ABC");
        assertThatThrownBy(() -> validator.validate(signature("ORD01ABC", "request-123", "1742505638"), null, "ORD01ABC"))
                .isInstanceOf(InvalidOrderNotificationException.class);
    }

    @Test
    void shouldAllowWhitespaceAndAdditionalVersionWithoutReplacingV1() {
        validator.validate(" v2=ignored, " + signature("ORD01ABC", "request-123", "1") + " ", " request-123 ", "ORD01ABC");
    }

    @Test
    void shouldAcceptRepeatedOldAuthenticNotificationWithoutInventingTimestampExpiry() {
        String signed = signature("ORD01ABC", "request-123", "1");
        validator.validate(signed, "request-123", "ORD01ABC");
        validator.validate(signed, "request-123", "ORD01ABC");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"bad", "ts=1", "v1=abc", "ts=1,v1=abc", "ts=x,v1=abc", "ts=1,ts=1,v1=abc", "ts=1,v1=abc,v1=abc", "ts=1,v2=abc", "ts=1,v1=abc,", "ts=-1,v1=abc"})
    void shouldRejectMissingMalformedOrAmbiguousSignature(String signature) {
        assertThatThrownBy(() -> validator.validate(signature, "request-123", "ORD01ABC"))
                .isInstanceOfSatisfying(InvalidOrderNotificationException.class, failure -> assertThat(failure.getStatus()).isEqualTo(401));
    }

    @Test
    void shouldRejectChangedRequestIdOrHashOrMissingSignedId() {
        String signed = signature("ORD01ABC", "request-123", "1");
        assertThatThrownBy(() -> validator.validate(signed, "changed", "ORD01ABC")).isInstanceOf(InvalidOrderNotificationException.class);
        assertThatThrownBy(() -> validator.validate(signed, "request-123", null)).isInstanceOf(InvalidOrderNotificationException.class);
        assertThatThrownBy(() -> validator.validate(signed, "request-123", "../ORD01ABC")).isInstanceOf(InvalidOrderNotificationException.class);
        assertThatThrownBy(() -> validator.validate(signed.replaceFirst("v1=.", "v1=z"), "request-123", "ORD01ABC")).isInstanceOf(InvalidOrderNotificationException.class);
    }

    @Test
    void shouldStopAfterOriginalSuccessWithoutInvokingCompatibility() {
        String signed = signature("ORD01ABC", "request-123", "1");
        try (var sdk = mockStatic(WebhookSignatureValidator.class, CALLS_REAL_METHODS)) {
            assertThat(validator.validate(signed, "request-123", "ORD01ABC"))
                    .isEqualTo(MercadoPagoSignatureValidator.Strategy.ORIGINAL);
            sdk.verify(() -> WebhookSignatureValidator.validate(signed, "request-123", "ORD01ABC", SECRET));
            sdk.verify(() -> WebhookSignatureValidator.validate(signed, "request-123", "ord01abc", SECRET), never());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING_SIGNATURE_HEADER", "MALFORMED_SIGNATURE_HEADER", "TIMESTAMP_OUT_OF_TOLERANCE"})
    void shouldNeverTryCompatibilityAfterOtherSdkFailures(String reason) {
        String signed = signature("ORD01ABC", "request-123", "1");
        try (var sdk = mockStatic(WebhookSignatureValidator.class)) {
            sdk.when(() -> WebhookSignatureValidator.validate(signed, "request-123", "ORD01ABC", SECRET))
                    .thenThrow(new MPInvalidWebhookSignatureException(SignatureFailureReason.valueOf(reason), "request-123", "1"));
            assertThatThrownBy(() -> validator.validate(signed, "request-123", "ORD01ABC"))
                    .isInstanceOf(InvalidOrderNotificationException.class);
            sdk.verify(() -> WebhookSignatureValidator.validate(signed, "request-123", "ORD01ABC", SECRET));
            sdk.verifyNoMoreInteractions();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ord01abc", "123456789", "ORD-01ABC", "ORD_01ABC"})
    void shouldNotRetryUnchangedOrNonAlphanumericIdentifiers(String id) {
        String signed = "ts=1,v1=" + "0".repeat(64);
        try (var sdk = mockStatic(WebhookSignatureValidator.class)) {
            sdk.when(() -> WebhookSignatureValidator.validate(signed, "request-123", id, SECRET))
                    .thenThrow(new MPInvalidWebhookSignatureException(SignatureFailureReason.SIGNATURE_MISMATCH, "request-123", "1"));
            assertThatThrownBy(() -> validator.validate(signed, "request-123", id))
                    .isInstanceOf(InvalidOrderNotificationException.class);
            sdk.verify(() -> WebhookSignatureValidator.validate(signed, "request-123", id, SECRET));
            sdk.verifyNoMoreInteractions();
        }
    }
}
