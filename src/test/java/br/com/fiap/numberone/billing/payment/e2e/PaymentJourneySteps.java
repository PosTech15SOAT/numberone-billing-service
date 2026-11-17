package br.com.fiap.numberone.billing.payment.e2e;

import br.com.fiap.numberone.billing.payment.application.gateways.ChargeGateway;
import br.com.fiap.numberone.billing.payment.application.gateways.CheckoutGateway;
import br.com.fiap.numberone.billing.payment.application.gateways.OrderLookupGateway;
import br.com.fiap.numberone.billing.payment.application.results.CheckoutOrder;
import br.com.fiap.numberone.billing.payment.application.results.ProviderOrderSnapshot;
import br.com.fiap.numberone.billing.payment.application.results.ProviderPaymentSnapshot;
import br.com.fiap.numberone.billing.payment.domain.enums.ChargeStatus;
import br.com.fiap.numberone.billing.shared.support.e2e.BillingExternalServices;
import br.com.fiap.numberone.billing.shared.support.e2e.BillingHttp;
import io.cucumber.java.pt.Entao;
import io.cucumber.java.pt.Quando;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static br.com.fiap.numberone.billing.payment.support.MercadoPagoWebhookFixtures.signature;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class PaymentJourneySteps {
    private static final String PROVIDER_ORDER = "BDD_ORDER_001";
    @LocalServerPort private int port;
    @Autowired private BillingExternalServices services;
    @Autowired private CheckoutGateway checkout;
    @Autowired private OrderLookupGateway orders;
    @Autowired private ChargeGateway charges;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    private UUID chargeId;
    private UUID attemptId;

    @Quando("a OS é concluída e o atendente cria o checkout")
    public void createCheckoutForCompletedOrder() throws Exception {
        services.completeOrder();
        var validated = mock(CheckoutGateway.ValidatedCheckout.class);
        when(checkout.validateSeller()).thenReturn(validated);
        when(validated.createCheckout(any(), any())).thenReturn(new CheckoutOrder(PROVIDER_ORDER,
                URI.create("https://www.mercadopago.com.br/checkout/v1/redirect?pref_id=bdd"), "created"));
        var response = BillingHttp.send(port, "POST", "/api/admin/ordens-servico/" + BillingExternalServices.ORDER_ID + "/pagamentos/checkout",
                "", Map.of("Idempotency-Key", "50000000-0000-0000-0000-000000000001"));
        assertThat(response.statusCode()).isEqualTo(200);
        var document = json.readTree(response.body());
        chargeId = UUID.fromString(document.path("idCobranca").asText());
        attemptId = UUID.fromString(document.path("idTentativa").asText());
        assertThat(document.path("idOrdemProvedor").asText()).isEqualTo(PROVIDER_ORDER);
        assertThat(charges.findByProviderOrderId(PROVIDER_ORDER).orElseThrow().getStatus()).isEqualTo(ChargeStatus.OPEN);
        verify(validated).createCheckout(any(), any());
    }

    @Quando("o provedor confirma os R$ 200,00 e entrega o mesmo webhook assinado duas vezes")
    public void confirmThroughRepeatedSignedWebhook() throws Exception {
        var amount = new BigDecimal("200.00");
        when(orders.findById(PROVIDER_ORDER)).thenReturn(new ProviderOrderSnapshot(PROVIDER_ORDER, "processed", "accredited",
                amount, amount, "BRL", attemptId.toString(),
                List.of(new ProviderPaymentSnapshot("BDD_PAYMENT_001", "processed", "accredited", amount, amount)), false));
        String requestId = "bdd-webhook-001";
        String timestamp = "1700000000";
        for (int delivery = 0; delivery < 2; delivery++) {
            var response = BillingHttp.send(port, "POST", "/api/public/pagamentos/notificacoes/mercado-pago?data.id=" + PROVIDER_ORDER,
                    "{\"type\":\"order\",\"live_mode\":false,\"data\":{\"id\":\"" + PROVIDER_ORDER + "\"}}",
                    Map.of("Content-Type", "application/json", "x-request-id", requestId,
                            "x-signature", signature(PROVIDER_ORDER, requestId, timestamp)));
            assertThat(response.statusCode()).isEqualTo(200);
        }
    }

    @Entao("a cobrança fica quitada e somente um evento PagamentoConfirmado é persistido")
    public void verifyPaidChargeAndSingleEvent() {
        var charge = charges.findByProviderOrderId(PROVIDER_ORDER).orElseThrow();
        assertThat(charge.getChargeId()).isEqualTo(chargeId);
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.PAID);
        assertThat(charge.getAmount()).isEqualByComparingTo("200.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM integration_event_outbox WHERE event_type='PagamentoConfirmado'", Integer.class)).isEqualTo(1);
        verify(orders, times(2)).findById(PROVIDER_ORDER);
    }
}
