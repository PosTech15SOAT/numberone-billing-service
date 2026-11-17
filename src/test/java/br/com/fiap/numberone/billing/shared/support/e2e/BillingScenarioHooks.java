package br.com.fiap.numberone.billing.shared.support.e2e;

import br.com.fiap.numberone.billing.payment.application.gateways.CheckoutGateway;
import br.com.fiap.numberone.billing.payment.application.gateways.OrderLookupGateway;
import br.com.fiap.numberone.billing.shared.support.e2e.BillingExternalServices;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.reset;

public class BillingScenarioHooks {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BillingExternalServices services;
    @Autowired private CheckoutGateway checkout;
    @Autowired private OrderLookupGateway orders;

    @Before
    public void prepareScenario() {
        cleanDatabase();
        services.reset();
        reset(checkout, orders);
    }

    @After
    public void cleanDatabase() {
        jdbc.execute("TRUNCATE payment_attempt, charge, budget_email_notification, budget_approval_authorization, " +
                "integration_event_outbox, budget, budget_revision_counter");
    }
}
