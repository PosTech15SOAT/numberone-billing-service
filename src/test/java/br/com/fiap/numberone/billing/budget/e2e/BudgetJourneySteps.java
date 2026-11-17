package br.com.fiap.numberone.billing.budget.e2e;

import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.domain.enums.BudgetApprovalOrigin;
import br.com.fiap.numberone.billing.budget.domain.enums.BudgetStatus;
import br.com.fiap.numberone.billing.budget.infrastructure.notifications.ApprovalTokenProtection;
import br.com.fiap.numberone.billing.shared.support.e2e.BillingExternalServices;
import br.com.fiap.numberone.billing.shared.support.e2e.BillingHttp;
import io.cucumber.java.pt.Dado;
import io.cucumber.java.pt.Entao;
import io.cucumber.java.pt.Quando;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class BudgetJourneySteps {
    @LocalServerPort private int port;
    @Autowired private JsonMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BudgetGateway budgets;
    @Autowired private ApprovalTokenProtection protection;
    private UUID budgetId;
    private String token;

    @Dado("que uma OS em diagnóstico possui um serviço de R$ 150,00 e duas unidades de material de R$ 25,00")
    public void validOrderWithServicesAndMaterials() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM budget", Integer.class)).isZero();
    }

    @Quando("o atendente cria o orçamento sem informar um valor manual")
    public void createCalculatedBudget() throws Exception {
        var response = BillingHttp.send(port, "POST", "/api/admin/ordens-servico/" + BillingExternalServices.ORDER_ID + "/orcamentos",
                "{}", Map.of("Content-Type", "application/json"));
        assertThat(response.statusCode()).isEqualTo(201);
        var document = json.readTree(response.body());
        budgetId = UUID.fromString(document.path("id").asText());
        assertThat(document.path("status").asText()).isEqualTo("RASCUNHO");
        assertThat(new BigDecimal(document.path("valorProposto").asText())).isEqualByComparingTo("200.00");
        assertThat(response.headers().firstValue("Location")).hasValueSatisfying(location ->
                assertThat(location).endsWith("/api/admin/orcamentos-ordem-servico/" + budgetId));
        var budget = budgets.findById(budgetId).orElseThrow();
        assertThat(budget.getQuotedAmount()).isEqualByComparingTo("200.00");
        assertThat(budget.getComposition().items()).singleElement().satisfies(item -> {
            assertThat(item.serviceAmount()).isEqualByComparingTo("150.00");
            assertThat(item.supplies()).singleElement().satisfies(supply -> {
                assertThat(supply.quantity()).isEqualTo(2);
                assertThat(supply.salePrice()).isEqualByComparingTo("25.00");
            });
        });
    }

    @Quando("o atendente solicita a aprovação do orçamento")
    public void sendBudgetForApproval() throws Exception {
        var response = BillingHttp.send(port, "PATCH", "/api/admin/orcamentos-ordem-servico/" + budgetId + "/solicitar-aprovacao",
                "", Map.of());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("status").asText()).isEqualTo("ENVIADO");
        token = jdbc.query("SELECT authorization_id, protected_content FROM budget_email_notification WHERE budget_id=?",
                (row, index) -> protection.unprotect(row.getObject(1, UUID.class), row.getBytes(2))
                        .approvalUrl().split("\\?token=")[1], budgetId).getFirst();
        assertThat(token).isNotBlank();
    }

    @Quando("o cliente abre o link público e confirma a aprovação")
    public void approveUsingPublicLink() throws Exception {
        var page = BillingHttp.send(port, "GET", "/api/public/orcamentos-ordem-servico/decisao?token=" + token, "", Map.of());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("200,00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM budget_approval_authorization WHERE consumed_at IS NOT NULL", Integer.class)).isZero();
        var response = BillingHttp.send(port, "POST", "/api/public/orcamentos-ordem-servico/aprovar",
                "token=" + token + "&confirmacao=sim", Map.of("Content-Type", "application/x-www-form-urlencoded"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Orçamento aprovado");
    }

    @Entao("o orçamento de R$ 200,00 fica aprovado remotamente e a autorização é consumida")
    public void verifyPersistedApproval() {
        var budget = budgets.findById(budgetId).orElseThrow();
        assertThat(budget.getStatus()).isEqualTo(BudgetStatus.APPROVED);
        assertThat(budget.getApprovedAmount()).isEqualByComparingTo("200.00");
        assertThat(budget.getQuotedAmount()).isEqualByComparingTo("200.00");
        assertThat(budget.getApprovalOrigin()).isEqualTo(BudgetApprovalOrigin.REMOTE);
        assertThat(budget.getApprovedAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM budget_approval_authorization WHERE consumed_at IS NOT NULL", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM budget_email_notification WHERE budget_id=?", String.class, budgetId)).isEqualTo("CANCELLED");
    }

    @Entao("a outbox registra o envio e a aprovação com o valor e a identidade do orçamento")
    public void verifyBudgetEvents() {
        assertThat(jdbc.queryForList("SELECT event_type FROM integration_event_outbox", String.class))
                .containsExactlyInAnyOrder("OrcamentoEnviado", "OrcamentoAprovado");
        assertThat(jdbc.queryForObject("SELECT envelope->'payload'->>'approvedAmount' FROM integration_event_outbox WHERE event_type='OrcamentoAprovado'", String.class))
                .isEqualTo("200.00");
        assertThat(jdbc.queryForObject("SELECT envelope->'payload'->>'budgetId' FROM integration_event_outbox WHERE event_type='OrcamentoAprovado'", String.class))
                .isEqualTo(budgetId.toString());
    }
}
