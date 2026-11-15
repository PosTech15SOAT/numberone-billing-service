package br.com.fiap.numberone.billing.payment.support;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public final class MercadoPagoWebhookFixtures {
    public static final String SECRET = "fictitious-webhook-secret";
    private MercadoPagoWebhookFixtures() { }

    public static String signature(String orderId, String requestId, String timestamp) {
        try {
            String manifest = "id:" + orderId + ";" + (requestId == null ? "" : "request-id:" + requestId + ";") + "ts:" + timestamp + ";";
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "ts=" + timestamp + ",v1=" + HexFormat.of().formatHex(hmac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException failure) { throw new AssertionError(failure); }
    }

    public static String payment() {
        return "{\"id\":\"PAY01ABC\",\"status\":\"processed\",\"status_detail\":\"accredited\",\"amount\":\"10.00\",\"paid_amount\":\"10.00\"}";
    }

    public static String officialBody(String id, String reference, String status, String detail) {
        return "{\"id\":\"" + id + "\",\"type\":\"online\",\"processing_mode\":\"manual\",\"status\":\"" + status
                + "\",\"status_detail\":\"" + detail + "\",\"total_amount\":\"10.00\",\"total_paid_amount\":\"10.00\",\"currency\":\"BRL\",\"external_reference\":\""
                + reference + "\",\"client_token\":\"private-token\",\"payer\":{\"email\":\"private@example.test\"},\"transactions\":{\"payments\":[" + payment() + "]}}";
    }
}
