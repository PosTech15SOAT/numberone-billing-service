package br.com.fiap.numberone.billing.payment.infrastructure.webhook;

import com.mercadopago.exceptions.MPInvalidWebhookSignatureException;
import com.mercadopago.exceptions.SignatureFailureReason;
import com.mercadopago.webhook.WebhookSignatureValidator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MercadoPagoSignatureValidator {
    public enum Strategy { ORIGINAL, LOWERCASE_COMPATIBILITY }

    private final String secret;

    public MercadoPagoSignatureValidator(String secret) {
        this.secret = secret;
    }

    public Strategy validate(String signature, String requestId, String dataId) {
        validateInputs(signature, requestId, dataId);
        try {
            WebhookSignatureValidator.validate(signature, requestId, dataId, secret);
            return Strategy.ORIGINAL;
        } catch (MPInvalidWebhookSignatureException failure) {
            return validateLowercaseCompatibility(signature, requestId, dataId, failure);
        }
    }

    private void validateInputs(String signature, String requestId, String dataId) {
        if (signature == null || signature.length() > 2048) throw invalid();
        if (dataId == null || !dataId.matches("[A-Za-z0-9_-]{1,255}"))
            throw new InvalidOrderNotificationException(401, InvalidOrderNotificationException.Reason.INVALID_ORDER_ID);
        String normalizedRequest = requestId == null || requestId.isBlank() ? null : requestId.strip();
        if (normalizedRequest != null && !normalizedRequest.matches("[A-Za-z0-9_-]{1,255}"))
            throw new InvalidOrderNotificationException(401, InvalidOrderNotificationException.Reason.INVALID_REQUEST_ID);
        validateSignatureFormat(signature);
    }

    private void validateSignatureFormat(String signature) {
        Map<String, String> parts = new HashMap<>();
        for (String component : signature.split(",", -1)) {
            String[] pair = component.strip().split("=", 2);
            if (pair.length != 2) throw invalid();
            String key = pair[0].strip().toLowerCase(Locale.ROOT);
            if (!key.equals("ts") && !key.matches("v[0-9]+")) throw invalid();
            if (parts.putIfAbsent(key, pair[1].strip()) != null) throw invalid();
        }
        String timestamp = parts.get("ts");
        String hash = parts.get("v1");
        if (timestamp == null || !timestamp.matches("[0-9]{1,20}") || hash == null || !hash.matches("[0-9a-f]{64}")) throw invalid();
    }

    private Strategy validateLowercaseCompatibility(String signature, String requestId, String dataId,
                                                    MPInvalidWebhookSignatureException failure) {
        String lowercaseId = dataId.toLowerCase(Locale.ROOT);
        if (failure.getReason() != SignatureFailureReason.SIGNATURE_MISMATCH
                || !dataId.matches("[A-Za-z0-9]+") || dataId.equals(lowercaseId)) {
            throw rejected(failure);
        }
        try {
            WebhookSignatureValidator.validate(signature, requestId, lowercaseId, secret);
            return Strategy.LOWERCASE_COMPATIBILITY;
        } catch (MPInvalidWebhookSignatureException alternativeFailure) {
            throw rejected(alternativeFailure);
        }
    }

    private InvalidOrderNotificationException rejected(MPInvalidWebhookSignatureException failure) {
        return new InvalidOrderNotificationException(401,
                failure.getReason() == SignatureFailureReason.SIGNATURE_MISMATCH
                        ? InvalidOrderNotificationException.Reason.SIGNATURE_MISMATCH
                        : InvalidOrderNotificationException.Reason.INVALID_SIGNATURE_FORMAT);
    }

    private InvalidOrderNotificationException invalid() {
        return new InvalidOrderNotificationException(401);
    }
}
