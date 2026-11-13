package br.com.fiap.numberone.billing.budget.api;

import br.com.fiap.numberone.billing.budget.application.usecases.RegisterBudgetApprovalUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.RejectBudgetUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.ApproveBudgetUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.ResendBudgetApprovalUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.GetBudgetUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.CreateBudgetUseCase;
import br.com.fiap.numberone.billing.budget.api.controllers.BudgetAdminController;
import br.com.fiap.numberone.billing.budget.api.exceptions.BudgetExceptionHandler;
import br.com.fiap.numberone.billing.budget.api.mappers.BudgetApiMapper;
import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.application.usecases.SendBudgetForApprovalUseCase;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.enums.BudgetStatus;
import br.com.fiap.numberone.billing.budget.domain.valueobjects.BudgetComposition;
import br.com.fiap.numberone.billing.budget.infrastructure.rest.ServiceOrderRestGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BudgetSendingHttpTest {

    private final BudgetGateway budgets = mock(BudgetGateway.class);
    private final Budget budget = Budget.createDraft(UUID.randomUUID(), 4, new BigDecimal("150.00"),
            new BudgetComposition(List.of()));
    private final JsonMapper jsonMapper = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private MockRestServiceServer osServer;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://os.test")
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new JacksonJsonHttpMessageConverter(jsonMapper));
                });
        osServer = MockRestServiceServer.bindTo(builder).build();
        var useCase = new SendBudgetForApprovalUseCase(budgets,
                new ServiceOrderRestGateway(builder.build()));
        mvc = MockMvcBuilders.standaloneSetup(new BudgetAdminController(mock(CreateBudgetUseCase.class),
                        mock(GetBudgetUseCase.class),
                        useCase,
                        mock(ResendBudgetApprovalUseCase.class),
                        mock(ApproveBudgetUseCase.class),
                        mock(RejectBudgetUseCase.class),
                        mock(RegisterBudgetApprovalUseCase.class),
                        new BudgetApiMapper()))
                .setControllerAdvice(new BudgetExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(jsonMapper)).build();
        when(budgets.findById(budget.getBudgetId())).thenReturn(Optional.of(budget));
        when(budgets.sendForApproval(budget.getBudgetId(), "cliente@example.com")).thenAnswer(invocation -> {
            budget.markAsSent();
            return budget;
        });
    }

    @AfterEach
    void verifyServer() {
        osServer.verify();
    }

    @Test
    void shouldPreserveLegacyPatchAndResponseWithoutBody() throws Exception {
        expectContext("cliente@example.com");

        send().andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(9))
                .andExpect(jsonPath("$.id").value(budget.getBudgetId().toString()))
                .andExpect(jsonPath("$.idOrdemServico").value(budget.getServiceOrderId().toString()))
                .andExpect(jsonPath("$.valorProposto").value(150.00))
                .andExpect(jsonPath("$.valorAprovado").value(nullValue()))
                .andExpect(jsonPath("$.status").value("ENVIADO"))
                .andExpect(jsonPath("$.enviadoEm").isString())
                .andExpect(jsonPath("$.aprovadoEm").value(nullValue()))
                .andExpect(jsonPath("$.created_at").isString())
                .andExpect(jsonPath("$.updated_at").isString())
                .andExpect(jsonPath("$.budgetVersion").doesNotExist())
                .andExpect(jsonPath("$.customerEmail").doesNotExist());
        verify(budgets).sendForApproval(budget.getBudgetId(), "cliente@example.com");
    }

    @ParameterizedTest
    @EnumSource(value = BudgetStatus.class, names = {"SENT", "APPROVED", "REJECTED"})
    void shouldRejectInvalidBudgetState(BudgetStatus status) throws Exception {
        budget.markAsSent();
        if (status == BudgetStatus.APPROVED) budget.approve();
        if (status == BudgetStatus.REJECTED) budget.reject();
        expectContext("cliente@example.com");

        expectError(send(), 422);
        verify(budgets).sendForApproval(budget.getBudgetId(), "cliente@example.com");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void shouldRequireCustomerEmailFromCt03(String email) throws Exception {
        expectContext(email);

        expectError(send(), 422).andExpect(jsonPath("$.message").value(
                "O e-mail do cliente é obrigatório para solicitar aprovação do orçamento"));
        assertThat(budget.getStatus()).isEqualTo(BudgetStatus.DRAFT);
        assertThat(budget.getSentAt()).isNull();
        verify(budgets, never()).sendForApproval(any(), any());
    }

    @Test
    void shouldRejectEmailFieldAbsentFromCt03() throws Exception {
        expectOs().andRespond(withSuccess(jsonMapper.writeValueAsString(Map.of(
                "serviceOrderId", budget.getServiceOrderId(), "status", "IN_DIAGNOSIS", "items", List.of())),
                MediaType.APPLICATION_JSON));
        expectError(send(), 422);
        verify(budgets, never()).sendForApproval(any(), any());
    }

    @Test
    void shouldPreserveLegacy422ForAnIneligibleOrderState() throws Exception {
        expectOs().andRespond(withSuccess(jsonMapper.writeValueAsString(Map.of(
                "serviceOrderId", budget.getServiceOrderId(), "status", "COMPLETED",
                "items", List.of(), "customerEmail", "cliente@example.com")), MediaType.APPLICATION_JSON));
        expectError(send(), 422);
        assertThat(budget.getStatus()).isEqualTo(BudgetStatus.DRAFT);
        verify(budgets, never()).sendForApproval(any(), any());
    }

    @Test
    void shouldReturn404ForMissingBudget() throws Exception {
        when(budgets.findById(budget.getBudgetId())).thenReturn(Optional.empty());
        expectError(send(), 404);
        verify(budgets, never()).sendForApproval(any(), any());
    }

    @ParameterizedTest
    @CsvSource({"404,404", "409,409", "503,503", "401,502"})
    void shouldHandleOsFailures(int upstream, int expected) throws Exception {
        expectOs().andRespond(withStatus(HttpStatus.valueOf(upstream)));
        expectError(send(), expected);
        verify(budgets, never()).sendForApproval(any(), any());
    }

    @Test
    void shouldHandleOsTimeout() throws Exception {
        expectOs().andRespond(withException(new SocketTimeoutException("timeout")));
        expectError(send(), 504);
        verify(budgets, never()).sendForApproval(any(), any());
    }

    @Test
    void shouldReturn409ForLateDraftSending() throws Exception {
        expectContext("cliente@example.com");
        doThrow(new br.com.fiap.numberone.billing.budget.domain.exceptions.ObsoleteBudgetRevisionException())
                .when(budgets).sendForApproval(budget.getBudgetId(), "cliente@example.com");
        expectError(send(), 409);
        assertThat(budget.getStatus()).isEqualTo(BudgetStatus.DRAFT);
    }

    @Test
    void shouldReturn409WhenSendingAfterAnotherRevisionWasApproved() throws Exception {
        expectContext("cliente@example.com");
        doThrow(new br.com.fiap.numberone.billing.budget.domain.exceptions.ApprovedBudgetExistsException())
                .when(budgets).sendForApproval(budget.getBudgetId(), "cliente@example.com");
        expectError(send(), 409);
        assertThat(budget.getStatus()).isEqualTo(BudgetStatus.DRAFT);
    }

    @Test
    void shouldReturn409ForConcurrentSending() throws Exception {
        expectContext("cliente@example.com");
        doThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(Budget.class, budget.getBudgetId()))
                .when(budgets).sendForApproval(budget.getBudgetId(), "cliente@example.com");
        expectError(send(), 409);
    }

    @Test
    void shouldRejectMalformedIdentifier() throws Exception {
        expectError(mvc.perform(patch("/api/admin/orcamentos-ordem-servico/invalido/solicitar-aprovacao")), 400);
        verifyNoInteractions(budgets);
    }

    private void expectContext(String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serviceOrderId", budget.getServiceOrderId());
        body.put("status", "IN_DIAGNOSIS");
        body.put("items", List.of());
        body.put("customerEmail", email);
        expectOs().andRespond(withSuccess(jsonMapper.writeValueAsString(body), MediaType.APPLICATION_JSON));
    }

    private org.springframework.test.web.client.ResponseActions expectOs() {
        return osServer.expect(requestTo("http://os.test/internal/service-orders/" + budget.getServiceOrderId()
                + "/quotation-context")).andExpect(method(org.springframework.http.HttpMethod.GET));
    }

    private ResultActions send() throws Exception {
        return mvc.perform(patch("/api/admin/orcamentos-ordem-servico/" + budget.getBudgetId() + "/solicitar-aprovacao"));
    }

    private ResultActions expectError(ResultActions result, int code) throws Exception {
        return result.andExpect(status().is(code)).andExpect(jsonPath("$.status").value(code))
                .andExpect(jsonPath("$.message").isString()).andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.length()").value(3));
    }
}
