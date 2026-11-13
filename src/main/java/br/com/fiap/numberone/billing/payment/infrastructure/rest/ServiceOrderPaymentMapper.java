package br.com.fiap.numberone.billing.payment.infrastructure.rest;

import br.com.fiap.numberone.billing.payment.domain.references.ServiceOrderPaymentContext;
import br.com.fiap.numberone.billing.payment.infrastructure.rest.dtos.ServiceOrderPaymentResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

public class ServiceOrderPaymentMapper {
    private final JsonMapper json = new JsonMapper();
    private final boolean requireCancellationFlag;

    public ServiceOrderPaymentMapper() { this(false); }

    public ServiceOrderPaymentMapper(boolean requireCancellationFlag) {
        this.requireCancellationFlag = requireCancellationFlag;
    }

    public ServiceOrderPaymentContext toContext(UUID requestedId, byte[] body) {
        try {
            JsonNode document = json.readTree(body);
            if (document == null || !document.isObject() || !document.path("serviceOrderId").isString()
                    || !document.path("status").isString()
                    || requireCancellationFlag && !document.path("cancellationInProgress").isBoolean()) {
                throw new IllegalArgumentException("Resposta CT-02A exige identidade e estado textuais");
            }
            ServiceOrderPaymentResponse response = json.treeToValue(document, ServiceOrderPaymentResponse.class);
            if (!requestedId.toString().equalsIgnoreCase(response.serviceOrderId())
                    || !response.status().matches("[A-Z][A-Z_]{0,63}")) {
                throw new IllegalArgumentException("Identidade ou estado inválido na resposta CT-02A");
            }
            return new ServiceOrderPaymentContext(requestedId, response.status(),
                    document.path("cancellationInProgress").asBoolean(false));
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("JSON CT-02A inválido", exception);
        }
    }
}
