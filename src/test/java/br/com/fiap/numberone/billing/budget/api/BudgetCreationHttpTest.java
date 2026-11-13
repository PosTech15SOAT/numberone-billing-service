package br.com.fiap.numberone.billing.budget.api;

import br.com.fiap.numberone.billing.infrastructure.observability.support.FailingLogAppender;
import br.com.fiap.numberone.billing.budget.application.usecases.RegisterBudgetApprovalUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.RejectBudgetUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.ApproveBudgetUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.ResendBudgetApprovalUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.SendBudgetForApprovalUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.GetBudgetUseCase;
import br.com.fiap.numberone.billing.budget.api.controllers.BudgetAdminController;
import br.com.fiap.numberone.billing.budget.api.exceptions.BudgetExceptionHandler;
import br.com.fiap.numberone.billing.budget.api.mappers.BudgetApiMapper;
import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.application.usecases.CreateBudgetUseCase;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.valueobjects.BudgetComposition;
import br.com.fiap.numberone.billing.budget.infrastructure.rest.InventoryRestGateway;
import br.com.fiap.numberone.billing.budget.infrastructure.rest.ServiceOrderRestGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BudgetCreationHttpTest {

    private final UUID orderId = UUID.randomUUID();
    private final UUID partId = UUID.randomUUID();
    private final JsonMapper jsonMapper = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private MockRestServiceServer osServer;
    private MockRestServiceServer inventoryServer;
    private BudgetGateway budgetGateway;
    private MockMvc mockMvc;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        RestClient.Builder osBuilder = clientBuilder("http://os.test");
        RestClient.Builder inventoryBuilder = clientBuilder("http://inventory.test");
        osServer = MockRestServiceServer.bindTo(osBuilder).build();
        inventoryServer = MockRestServiceServer.bindTo(inventoryBuilder).build();
        budgetGateway = mock(BudgetGateway.class);
        CreateBudgetUseCase useCase = new CreateBudgetUseCase(
                new ServiceOrderRestGateway(osBuilder.build()),
                new InventoryRestGateway(inventoryBuilder.build()), budgetGateway);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new BudgetAdminController(useCase,
                        mock(GetBudgetUseCase.class),
                        mock(SendBudgetForApprovalUseCase.class),
                        mock(ResendBudgetApprovalUseCase.class),
                        mock(ApproveBudgetUseCase.class),
                        mock(RejectBudgetUseCase.class),
                        mock(RegisterBudgetApprovalUseCase.class),
                        new BudgetApiMapper()))
                .setControllerAdvice(new BudgetExceptionHandler()).setValidator(validator)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(jsonMapper)).build();
    }

    @AfterEach
    void verifyServers() {
        osServer.verify();
        inventoryServer.verify();
        validator.close();
    }

    @Test
    void shouldPreserveLegacyCreationRouteStatusFieldsAndLocationForManualBudget() throws Exception {
        expectContext("IN_DIAGNOSIS", List.of(item(null, partId)));
        stubPersistence();

        var result = create("{\"valorProposto\":250.00}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(9))
                .andExpect(jsonPath("$.idOrdemServico").value(orderId.toString()))
                .andExpect(jsonPath("$.valorProposto").value(250.00))
                .andExpect(jsonPath("$.valorAprovado").value(nullValue()))
                .andExpect(jsonPath("$.status").value("RASCUNHO"))
                .andExpect(jsonPath("$.enviadoEm").value(nullValue()))
                .andExpect(jsonPath("$.aprovadoEm").value(nullValue()))
                .andExpect(jsonPath("$.created_at").isString())
                .andExpect(jsonPath("$.updated_at").isString())
                .andExpect(jsonPath("$.budgetVersion").doesNotExist())
                .andExpect(jsonPath("$.commercial_snapshot").doesNotExist()).andReturn();

        String id = jsonMapper.readTree(result.getResponse().getContentAsString()).get("id").asString();
        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("http://localhost/api/admin/orcamentos-ordem-servico/" + id);
    }

    @Test
    void shouldCalculateUsingCt03AndTheApprovedPostCt04() throws Exception {
        expectContext("IN_DIAGNOSIS", List.of(item("PENDING", partId)));
        expectPrices(withSuccess(pricesBody("200.00"), MediaType.APPLICATION_JSON));
        stubPersistence();

        create("{}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.valorProposto").value(550.00));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.001", "1.234", "1.2301"})
    void shouldRejectManualPrecisionBeforeConsultingSourcesOrPersisting(String amount) throws Exception {
        expectError(create("{\"valorProposto\":" + amount + "}"), 400);
        verifyNoInteractions(budgetGateway);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0", "10.00", "10.000", "1.23000"})
    void shouldAcceptManualExactCentValuesWithoutChangingTheirScale(String amount) throws Exception {
        expectContext("IN_DIAGNOSIS", List.of(item(null, partId)));
        stubPersistence();
        var response = create("{\"valorProposto\":" + amount + "}").andExpect(status().isCreated()).andReturn().getResponse();
        assertThat(response.getContentAsString()).contains("\"valorProposto\":" + amount + ",");
        verify(budgetGateway).createDraft(eq(orderId), eq(new BigDecimal(amount)), any(BudgetComposition.class));
    }

    @Test
    void shouldRejectIncompatibleInventoryPrecisionBeforePersisting() throws Exception {
        expectContext("IN_DIAGNOSIS", List.of(item("PENDING", partId)));
        expectPrices(withSuccess(pricesBody("20.001"), MediaType.APPLICATION_JSON));
        expectError(create("{}"), 422);
        verifyNoInteractions(budgetGateway);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", ""})
    void shouldRejectUnknownAndMissingItemStatusBeforeConsultingPrices(String itemStatus) throws Exception {
        expectContext("IN_DIAGNOSIS", List.of(item("PENDING", partId),
                item(itemStatus.isEmpty() ? null : itemStatus, UUID.randomUUID())));

        expectError(create("{}"), 422);
        verifyNoInteractions(budgetGateway);
    }

    @Test
    void shouldExcludeOnlyExplicitlyCancelledItems() throws Exception {
        expectContext("IN_DIAGNOSIS", List.of(item("PENDING", partId), item("CANCELLED", UUID.randomUUID())));
        expectPrices(withSuccess(pricesBody("200.00"), MediaType.APPLICATION_JSON));
        stubPersistence();

        create("{}").andExpect(status().isCreated()).andExpect(jsonPath("$.valorProposto").value(550.00));
    }

    @Test
    void shouldKeepManualAmountWhenItemStatusIsUnknown() throws Exception {
        expectContext("IN_DIAGNOSIS", List.of(item("UNKNOWN", partId)));
        stubPersistence();

        create("{\"valorProposto\":250}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.valorProposto").value(250));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"valorProposto\":0}", "{\"valorProposto\":-1}", "{broken",
            "{\"valorProposto\":\"inválido\"}", "null"})
    void shouldRejectInvalidRequestWithoutConsultingServices(String body) throws Exception {
        expectError(create(body), 400);
        verifyNoInteractions(budgetGateway);
    }

    @Test
    void shouldRejectInvalidOrderIdentifier() throws Exception {
        expectError(mockMvc.perform(post("/api/admin/ordens-servico/invalid/orcamentos")
                .contentType(MediaType.APPLICATION_JSON).content("{}")), 400);
        verifyNoInteractions(budgetGateway);
    }

    @Test
    void shouldRejectMissingRequestBody() throws Exception {
        expectError(mockMvc.perform(post("/api/admin/ordens-servico/" + orderId + "/orcamentos")
                .contentType(MediaType.APPLICATION_JSON)), 400);
        verifyNoInteractions(budgetGateway);
    }

    @ParameterizedTest
    @CsvSource({"404,404", "409,409", "500,503", "503,503", "401,502"})
    void shouldTranslateOsHttpErrors(int upstreamStatus, int billingStatus) throws Exception {
        expectOs(withStatus(HttpStatus.valueOf(upstreamStatus)).body("external-secret"));

        expectError(create("{}"), billingStatus);
        verifyNoInteractions(budgetGateway);
    }

    @Test
    void shouldRejectIneligibleOrderReturnedByOs() throws Exception {
        expectContext("COMPLETED", List.of(item("COMPLETED", partId)));

        expectError(create("{\"valorProposto\":100}"), 409);
        verifyNoInteractions(budgetGateway);
    }

    @ParameterizedTest
    @CsvSource({"404,422", "500,503", "503,503", "400,502"})
    void shouldTranslateInventoryHttpErrors(int upstreamStatus, int billingStatus) throws Exception {
        expectContext("IN_DIAGNOSIS", List.of(item("PENDING", partId)));
        expectPrices(withStatus(HttpStatus.valueOf(upstreamStatus)).body("external-secret"));

        expectError(create("{}"), billingStatus);
        verifyNoInteractions(budgetGateway);
    }

    @Test
    void shouldReportOsTimeout() throws Exception {
        expectOs(withException(new SocketTimeoutException("external-secret")));
        expectError(create("{}"), 504);
        verifyNoInteractions(budgetGateway);
    }

    @Test
    void shouldReportInventoryTimeout() throws Exception {
        expectContext("IN_DIAGNOSIS", List.of(item("PENDING", partId)));
        expectPrices(withException(new SocketTimeoutException("external-secret")));
        expectError(create("{}"), 504);
        verifyNoInteractions(budgetGateway);
    }

    @Test
    void shouldReportOsConnectionFailure() throws Exception {
        expectOs(withException(new ConnectException("external-secret")));
        expectError(create("{}"), 503);
        verifyNoInteractions(budgetGateway);
    }

    @Test
    void shouldReportInventoryConnectionFailure() throws Exception {
        expectContext("IN_DIAGNOSIS", List.of(item("PENDING", partId)));
        expectPrices(withException(new ConnectException("external-secret")));
        expectError(create("{}"), 503);
        verifyNoInteractions(budgetGateway);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "not-json", "[]"})
    void shouldRejectMalformedOsResponse(String body) throws Exception {
        expectOs(withSuccess(body, MediaType.APPLICATION_JSON));
        expectError(create("{}"), 502);
        verifyNoInteractions(budgetGateway);
    }

    @Test
    void shouldRejectEmptyOsResponse() throws Exception {
        expectOs(withNoContent());
        expectError(create("{}"), 502);
        verifyNoInteractions(budgetGateway);
    }

    @Test
    void shouldRejectOsResponseWithAnotherIdentifier() throws Exception {
        expectOs(withSuccess(contextBody(UUID.randomUUID(), "IN_DIAGNOSIS", List.of(item("PENDING", partId))),
                MediaType.APPLICATION_JSON));
        expectError(create("{}"), 502);
        verifyNoInteractions(budgetGateway);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"items\":[]}", "{\"items\":[{\"inventoryItemId\":\"%s\",\"salePrice\":null}]}"})
    void shouldRejectMissingOrPartialPrices(String body) throws Exception {
        expectContext("IN_DIAGNOSIS", List.of(item("PENDING", partId)));
        expectPrices(withSuccess(body.formatted(partId), MediaType.APPLICATION_JSON));
        expectError(create("{}"), 422);
        verifyNoInteractions(budgetGateway);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "not-json", "{\"items\":[{\"inventoryItemId\":\"invalid\",\"salePrice\":\"x\"}]}"})
    void shouldRejectInvalidInventoryResponse(String body) throws Exception {
        expectContext("IN_DIAGNOSIS", List.of(item("PENDING", partId)));
        expectPrices(withSuccess(body, MediaType.APPLICATION_JSON));
        expectError(create("{}"), 502);
        verifyNoInteractions(budgetGateway);
    }

    @Test
    void shouldReturn409WhenApprovedBudgetPreventsNewDraft() throws Exception {
        expectContext("IN_DIAGNOSIS", List.of(item(null, partId)));
        when(budgetGateway.createDraft(eq(orderId), any(), any())).thenThrow(
                new br.com.fiap.numberone.billing.budget.domain.exceptions.ApprovedBudgetExistsException());
        expectError(create("{\"valorProposto\":100}"), 409);
    }

    @Test
    void shouldReturnGenericErrorWhenPersistenceFails() throws Exception {
        expectContext("IN_DIAGNOSIS", List.of(item(null, partId)));
        when(budgetGateway.createDraft(eq(orderId), any(), any())).thenThrow(new IllegalStateException("external-secret"));

        expectError(create("{\"valorProposto\":100}"), 500);
    }

    private RestClient.Builder clientBuilder(String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl)
                .messageConverters(converters -> converters.addFirst(new JacksonJsonHttpMessageConverter(jsonMapper)));
    }

    private void stubPersistence() {
        when(budgetGateway.createDraft(eq(orderId), any(BigDecimal.class), any(BudgetComposition.class)))
                .thenAnswer(invocation -> Budget.createDraft(invocation.getArgument(0), 1,
                        invocation.getArgument(1), invocation.getArgument(2)));
    }

    private ResultActions create(String body) throws Exception {
        return mockMvc.perform(post("/api/admin/ordens-servico/" + orderId + "/orcamentos")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void expectError(ResultActions result, int status) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(not(containsString("external-secret"))));
    }

    private void expectOs(ResponseCreator response) {
        osServer.expect(requestTo("http://os.test/internal/service-orders/" + orderId + "/quotation-context"))
                .andExpect(method(HttpMethod.GET)).andRespond(response);
    }

    private void expectContext(String status, List<Map<String, Object>> items) {
        expectOs(withSuccess(contextBody(orderId, status, items), MediaType.APPLICATION_JSON));
    }

    private String contextBody(UUID id, String status, List<Map<String, Object>> items) {
        return jsonMapper.writeValueAsString(Map.of("serviceOrderId", id, "status", status, "items", items));
    }

    private Map<String, Object> item(String itemStatus, UUID inventoryItemId) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("serviceOrderItemId", UUID.randomUUID());
        item.put("automotiveServiceId", UUID.randomUUID());
        item.put("serviceAmount", "150.00");
        if (itemStatus != null) {
            item.put("status", itemStatus);
        }
        item.put("supplies", List.of(Map.of("inventoryItemId", inventoryItemId, "quantity", 2)));
        return item;
    }

    private String pricesBody(String amount) {
        return jsonMapper.writeValueAsString(Map.of("items", List.of(Map.of("inventoryItemId", partId,
                "salePrice", amount, "currency", "BRL", "available", false))));
    }

    private void expectPrices(ResponseCreator response) {
        inventoryServer.expect(requestTo("http://inventory.test/internal/inventory/consultar-precos"))
                .andExpect(method(HttpMethod.POST)).andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"inventoryItemIds\":[\"" + partId + "\"]}"))
                .andRespond(response);
    }
    @Test
    void shouldPreserveHttpErrorAndOriginalExceptionWhenLoggingFails() throws Exception {
        expectContext("IN_DIAGNOSIS", List.of(item(null, partId)));
        var failure = new IllegalStateException("external-secret", new IllegalArgumentException("Causa original"));
        when(budgetGateway.createDraft(eq(orderId), any(), any())).thenThrow(failure);
        try (var logging = new FailingLogAppender(BudgetExceptionHandler.class)) {
            var result = create("{\"valorProposto\":100}");
            expectError(result, 500);
            result.andExpect(jsonPath("$.message").value("Erro interno, tente novamente mais tarde"));
            assertThat(result.andReturn().getResolvedException()).isSameAs(failure);
            assertThat(logging.eventCodes()).containsExactly("BUDGET_REQUEST_FAILED");
        }
        verify(budgetGateway).createDraft(eq(orderId), any(), any());
    }

}
