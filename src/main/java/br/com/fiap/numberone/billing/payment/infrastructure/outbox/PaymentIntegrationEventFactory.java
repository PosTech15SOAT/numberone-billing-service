package br.com.fiap.numberone.billing.payment.infrastructure.outbox;

import br.com.fiap.numberone.billing.infrastructure.outbox.IntegrationEventOutboxEntry;
import br.com.fiap.numberone.billing.payment.domain.entities.Charge;
import br.com.fiap.numberone.billing.payment.domain.enums.ChargeStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

@Component
public class PaymentIntegrationEventFactory {
    private final JsonMapper json;
    public PaymentIntegrationEventFactory(JsonMapper json) { this.json = json; }

    public IntegrationEventOutboxEntry create(Charge charge) {
        if (charge.getStatus() != ChargeStatus.PAID) throw new IllegalArgumentException("Evento exige cobrança quitada");
        return create(charge, "PagamentoConfirmado", 1);
    }

    public IntegrationEventOutboxEntry refunded(Charge charge) {
        if (charge.getStatus() != ChargeStatus.REFUNDED) throw new IllegalArgumentException("Evento exige estorno integral");
        return create(charge, "PagamentoAtualizado", 2);
    }

    private IntegrationEventOutboxEntry create(Charge charge, String eventType, int version) {
        UUID eventId = UUID.randomUUID();
        ObjectNode envelope = json.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("schemaVersion", 1);
        envelope.put("occurredAt", charge.getUpdatedAt().toString());
        // Correlação local estável da cobrança; não representa trace distribuído ou ID do webhook.
        envelope.put("correlationId", charge.getChargeId().toString());
        ObjectNode payload = envelope.putObject("payload");
        payload.put("serviceOrderId", charge.getServiceOrderId().toString());
        payload.put("paymentId", charge.getChargeId().toString());
        payload.put("paymentStatus", charge.getStatus().name());
        payload.put("paymentVersion", version);
        return new IntegrationEventOutboxEntry(eventId, eventType, 1, charge.getServiceOrderId(),
                null, null, charge.getChargeId(), charge.getUpdatedAt(), json.writeValueAsString(envelope));
    }
}
