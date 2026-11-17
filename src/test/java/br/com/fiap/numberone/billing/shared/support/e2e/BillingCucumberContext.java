package br.com.fiap.numberone.billing.shared.support.e2e;

import br.com.fiap.numberone.billing.BillingApplication;
import br.com.fiap.numberone.billing.payment.application.gateways.CheckoutGateway;
import br.com.fiap.numberone.billing.payment.application.gateways.OrderLookupGateway;
import br.com.fiap.numberone.billing.shared.support.e2e.BillingBddInfrastructure;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@CucumberContextConfiguration
@SpringBootTest(classes = BillingApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.import=", "billing.outbox.enabled=false", "billing.notifications.enabled=false",
                "billing.notifications.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "billing.notifications.approval-base-url=https://billing.test/api/public/orcamentos-ordem-servico/decisao",
                "billing.payment.mercado-pago.enabled=false", "billing.payment.mercado-pago-webhook.enabled=true",
                "billing.payment.mercado-pago-webhook.environment=TEST",
                "billing.payment.mercado-pago-webhook.secret=fictitious-webhook-secret"
        })
@ActiveProfiles("local")
@Import(BillingBddInfrastructure.class)
public class BillingCucumberContext {
    @MockitoBean private CheckoutGateway checkout;
    @MockitoBean private OrderLookupGateway orders;
}
