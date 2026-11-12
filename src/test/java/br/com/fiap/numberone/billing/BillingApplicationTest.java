package br.com.fiap.numberone.billing;

import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.payment.application.gateways.ChargeGateway;
import br.com.fiap.numberone.billing.budget.application.gateways.InventoryGateway;
import br.com.fiap.numberone.billing.budget.application.gateways.ServiceOrderGateway;
import br.com.fiap.numberone.billing.budget.api.publicview.PublicBudgetRequestFilter;
import br.com.fiap.numberone.billing.budget.api.controllers.BudgetAdminController;
import br.com.fiap.numberone.billing.budget.api.controllers.BudgetIntegrationController;
import br.com.fiap.numberone.billing.budget.api.controllers.BudgetPublicController;
import br.com.fiap.numberone.billing.budget.infrastructure.rest.InventoryRestGateway;
import br.com.fiap.numberone.billing.budget.infrastructure.rest.ServiceOrderRestGateway;
import br.com.fiap.numberone.billing.budget.infrastructure.outbox.IntegrationEventOutboxRepository;
import br.com.fiap.numberone.billing.budget.infrastructure.notifications.BudgetNotificationRepository;
import br.com.fiap.numberone.billing.budget.infrastructure.persistence.repositories.BudgetRevisionCounterRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.autoconfigure.exclude=" +
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration," +
        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration," +
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "billing.notifications.enabled=false",
        "billing.integrations.os-base-url=http://os.test",
        "billing.integrations.inventory-base-url=http://inventory.test"
})
class BillingApplicationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ServiceOrderGateway serviceOrderGateway;

    @Autowired
    private InventoryGateway inventoryGateway;

    @MockitoBean
    private BudgetGateway budgetGateway;

    @MockitoBean
    private ChargeGateway chargeGateway;

    @MockitoBean
    private BudgetRevisionCounterRepository counterRepository;

    @MockitoBean
    private IntegrationEventOutboxRepository outboxRepository;

    @MockitoBean
    private BudgetNotificationRepository notificationRepository;

    @MockitoBean(name = "dbHealthIndicator")
    private HealthIndicator databaseHealth;

    @BeforeEach
    void prepareDatabaseHealth() {
        when(databaseHealth.health()).thenReturn(Health.up().build());
        when(databaseHealth.health(anyBoolean())).thenCallRealMethod();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    void shouldExposeHealthWithoutInfrastructureDetails(String path) throws Exception {
        MockMvcBuilders.webAppContextSetup(context).build().perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/env", "/actuator/beans", "/actuator/metrics",
            "/actuator/configprops", "/actuator/heapdump", "/actuator/loggers", "/actuator/mappings",
            "/actuator/info", "/actuator/threaddump", "/actuator/flyway", "/actuator/shutdown"})
    void shouldNotExposeAdministrativeActuatorEndpoints(String path) throws Exception {
        MockMvcBuilders.webAppContextSetup(context).build().perform(get(path)).andExpect(status().isNotFound());
    }

    @Test
    void shouldKeepProbesIndependentFromDatabaseAvailability() throws Exception {
        when(databaseHealth.health()).thenReturn(Health.down().build());
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(get("/actuator/health")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void shouldReflectApplicationAvailabilityInProbes() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        try {
            AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
            mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable());
            mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
            AvailabilityChangeEvent.publish(context, LivenessState.BROKEN);
            mvc.perform(get("/actuator/health/liveness")).andExpect(status().isServiceUnavailable());
        } finally {
            AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
        }
    }

    @Test
    void contextLoads() {
        assertThat(serviceOrderGateway).isInstanceOf(ServiceOrderRestGateway.class);
        assertThat(inventoryGateway).isInstanceOf(InventoryRestGateway.class);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shouldWriteNativeEcsJsonToConsole(CapturedOutput output) {
        new br.com.fiap.numberone.billing.budget.api.exceptions.BudgetExceptionHandler()
                .unexpected(new IllegalStateException("private@example.test private-token"));
        String line = output.getOut().lines()
                .filter(value -> value.startsWith("{") && value.contains("BUDGET_REQUEST_FAILED"))
                .findFirst().orElseThrow();
        var json = new tools.jackson.databind.json.JsonMapper().readTree(line);
        assertThat(json.path("ecs").path("version").asText()).isEqualTo("8.11");
        assertThat(json.path("service").path("name").asText()).isEqualTo("numberone-billing-service");
        assertThat(json.path("event").path("code").asText()).isEqualTo("BUDGET_REQUEST_FAILED");
        assertThat(json.path("log").path("level").asText()).isEqualTo("ERROR");
        assertThat(json.has("event.code")).isFalse();
        assertThat(line).doesNotContain("private@example.test", "private-token");
    }

    @Test
    void shouldServeLocalBudgetStylesheetWithoutAuthorization() throws Exception {
        MockMvcBuilders.webAppContextSetup(context).addFilters(new PublicBudgetRequestFilter()).build()
                .perform(get("/css/budget.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"))
                .andExpect(content().string(containsString("@media(max-width:600px)")))
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void shouldPreserveAllBudgetRoutesAfterControllerConsolidation() {
        var controllers = Set.of(BudgetAdminController.class, BudgetPublicController.class, BudgetIntegrationController.class);
        var mappings = context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        var routes = mappings.getHandlerMethods().entrySet().stream()
                .filter(entry -> controllers.contains(entry.getValue().getBeanType()))
                .flatMap(entry -> entry.getKey().getMethodsCondition().getMethods().stream()
                        .flatMap(method -> entry.getKey().getPatternValues().stream().map(path -> method + " " + path)))
                .collect(Collectors.toSet());
        assertThat(routes).containsExactlyInAnyOrder(
                "POST /api/admin/ordens-servico/{serviceOrderId}/orcamentos",
                "GET /api/admin/orcamentos-ordem-servico/{id}",
                "PATCH /api/admin/orcamentos-ordem-servico/{id}/solicitar-aprovacao",
                "POST /api/admin/orcamentos-ordem-servico/{id}/reenviar-aprovacao",
                "PATCH /api/admin/orcamentos-ordem-servico/{id}/aprovar",
                "PATCH /api/admin/orcamentos-ordem-servico/{id}/rejeitar",
                "POST /api/admin/orcamentos-ordem-servico/{id}/registrar-aprovacao",
                "GET /api/public/orcamentos-ordem-servico/decisao",
                "POST /api/public/orcamentos-ordem-servico/aprovar",
                "POST /api/public/orcamentos-ordem-servico/rejeitar",
                "GET /internal/ordens-servico/{serviceOrderId}/orcamentos");
    }
}
