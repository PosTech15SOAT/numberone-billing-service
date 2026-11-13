package br.com.fiap.numberone.billing.payment.infrastructure.webhook;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public class OrderNotificationMapper {
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public void validate(byte[] body, String signedOrderId) {
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isObject() || !root.path("type").isString() || !root.path("type").asText().equals("order")
                    || !root.path("live_mode").isBoolean() || root.path("live_mode").asBoolean()
                    || !root.path("data").isObject() || !root.path("data").path("id").isString()
                    || !signedOrderId.equals(root.path("data").path("id").asText())
                    || (root.has("api_version") && !root.path("api_version").asText().equals("v1"))
                    || (root.has("action") && (!root.path("action").isString() || !root.path("action").asText().matches("order\\.[a-z_]+")))) {
                throw new InvalidOrderNotificationException(400);
            }
            // Status/valores do corpo não são evidência financeira e não saem desta fronteira.
        } catch (JacksonException exception) {
            throw new InvalidOrderNotificationException(400);
        }
    }
}
