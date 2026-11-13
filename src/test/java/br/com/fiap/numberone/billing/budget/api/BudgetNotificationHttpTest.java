package br.com.fiap.numberone.billing.budget.api;

import br.com.fiap.numberone.billing.budget.application.usecases.RegisterBudgetApprovalUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.RejectBudgetUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.ApproveBudgetUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.SendBudgetForApprovalUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.CreateBudgetUseCase;
import br.com.fiap.numberone.billing.budget.api.controllers.BudgetAdminController;
import br.com.fiap.numberone.billing.budget.api.exceptions.BudgetExceptionHandler;
import br.com.fiap.numberone.billing.budget.api.mappers.BudgetApiMapper;
import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetNotificationUnavailableException;
import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetResendTooSoonException;
import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.application.gateways.BudgetNotificationGateway;
import br.com.fiap.numberone.billing.budget.application.gateways.ServiceOrderGateway;
import br.com.fiap.numberone.billing.budget.application.results.BudgetNotificationSummary;
import br.com.fiap.numberone.billing.budget.application.usecases.GetBudgetUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.ResendBudgetApprovalUseCase;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.enums.BudgetStatus;
import br.com.fiap.numberone.billing.budget.domain.exceptions.ObsoleteBudgetRevisionException;
import br.com.fiap.numberone.billing.budget.domain.references.ServiceOrderQuotationContext;
import br.com.fiap.numberone.billing.budget.domain.valueobjects.BudgetComposition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BudgetNotificationHttpTest {
    private final BudgetGateway budgets = mock(BudgetGateway.class);
    private final BudgetNotificationGateway notifications = mock(BudgetNotificationGateway.class);
    private final ServiceOrderGateway serviceOrderGateway = mock(ServiceOrderGateway.class);
    private final Budget budget = Budget.restore(UUID.randomUUID(), UUID.randomUUID(), 2, new BigDecimal("123.4567"), null,
            BudgetStatus.DRAFT, null, null, LocalDateTime.of(2026, 11, 1, 10, 0), null, new BudgetComposition(List.of()));
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var json = JsonMapper.builder().disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        mvc = MockMvcBuilders.standaloneSetup(
                new BudgetAdminController(mock(CreateBudgetUseCase.class),
                        new GetBudgetUseCase(budgets, notifications),
                        new SendBudgetForApprovalUseCase(budgets, serviceOrderGateway),
                        new ResendBudgetApprovalUseCase(budgets, serviceOrderGateway),
                        mock(ApproveBudgetUseCase.class),
                        mock(RejectBudgetUseCase.class),
                        mock(RegisterBudgetApprovalUseCase.class),
                        new BudgetApiMapper()))
                .setControllerAdvice(new BudgetExceptionHandler()).setMessageConverters(new JacksonJsonHttpMessageConverter(json)).build();
        when(budgets.findById(budget.getBudgetId())).thenReturn(Optional.of(budget));
        when(serviceOrderGateway.findByServiceOrderId(budget.getServiceOrderId())).thenReturn(
                new ServiceOrderQuotationContext(budget.getServiceOrderId(), "WAITING_APPROVAL", List.of(), "customer@example.test"));
    }

    @Test
    void shouldResendWithoutChangingLegacyResponseOrAcceptingTokenFromJson() throws Exception {
        budget.markAsSent();
        when(budgets.resendForApproval(budget.getBudgetId(), "customer@example.test")).thenReturn(budget);
        mvc.perform(post("/api/admin/orcamentos-ordem-servico/{id}/reenviar-aprovacao", budget.getBudgetId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(9))
                .andExpect(jsonPath("$.status").value("ENVIADO"))
                .andExpect(jsonPath("$.valorProposto").value(123.4567))
                .andExpect(jsonPath("$.token").doesNotExist()).andExpect(jsonPath("$.notificacao").doesNotExist());
        verify(budgets).resendForApproval(budget.getBudgetId(), "customer@example.test");
        verify(budgets, never()).sendForApproval(any(), any());
    }

    @ParameterizedTest
    @CsvSource({"PENDING,PENDENTE", "SENT,ENVIADA", "FAILED,FALHA", "CANCELLED,CANCELADA"})
    void shouldExtendOnlyIndividualQueryWithSafePortugueseNotificationSummary(String stored, String external) throws Exception {
        budget.markAsSent();
        when(budgets.isCurrentSentRevision(budget.getBudgetId())).thenReturn(true);
        when(notifications.findLatest(budget.getBudgetId())).thenReturn(Optional.of(new BudgetNotificationSummary(
                BudgetNotificationSummary.Status.valueOf(stored), 3, OffsetDateTime.parse("2026-11-06T15:00:00Z"), null, true)));
        mvc.perform(get("/api/admin/orcamentos-ordem-servico/{id}", budget.getBudgetId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$.status").value("ENVIADO"))
                .andExpect(jsonPath("$.notificacao.length()").value(5))
                .andExpect(jsonPath("$.notificacao.status").value(external))
                .andExpect(jsonPath("$.notificacao.tentativas").value(3))
                .andExpect(jsonPath("$.notificacao.ultimaTentativaEm").isString())
                .andExpect(jsonPath("$.notificacao.enviadoEm").value(nullValue()))
                .andExpect(jsonPath("$.notificacao.podeReenviar").value(true))
                .andExpect(jsonPath("$.token").doesNotExist()).andExpect(jsonPath("$.last_error").doesNotExist())
                .andExpect(jsonPath("$.notificacao.protected_content").doesNotExist());
        verify(notifications).findLatest(budget.getBudgetId());
        verifyNoInteractions(serviceOrderGateway);
    }

    @Test
    void shouldAllowBudgetsWithoutAnyNotificationAndKeepDecisionSeparate() throws Exception {
        budget.registerApproval(null, false, "validated-subject");
        when(notifications.findLatest(budget.getBudgetId())).thenReturn(Optional.empty());
        mvc.perform(get("/api/admin/orcamentos-ordem-servico/{id}", budget.getBudgetId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APROVADO"))
                .andExpect(jsonPath("$.notificacao").value(nullValue()));
        verify(budgets, never()).isCurrentSentRevision(any());
    }

    @ParameterizedTest
    @CsvSource({"false,true", "true,false", "false,false"})
    void shouldTreatResendFlagAsAdvisoryAndCheckCurrentRevisionAndInterval(boolean current, boolean elapsed) throws Exception {
        budget.markAsSent();
        when(budgets.isCurrentSentRevision(budget.getBudgetId())).thenReturn(current);
        when(notifications.findLatest(budget.getBudgetId())).thenReturn(Optional.of(new BudgetNotificationSummary(
                BudgetNotificationSummary.Status.FAILED, 5, null, null, elapsed)));
        mvc.perform(get("/api/admin/orcamentos-ordem-servico/{id}", budget.getBudgetId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.notificacao.podeReenviar").value(false));
    }

    @Test
    void shouldMapResendFailuresUsingExistingErrorEnvelope() throws Exception {
        budget.markAsSent();
        doThrow(new BudgetResendTooSoonException()).when(budgets).resendForApproval(budget.getBudgetId(), "customer@example.test");
        expectResendError(409);
        doThrow(new ObsoleteBudgetRevisionException()).when(budgets).resendForApproval(budget.getBudgetId(), "customer@example.test");
        expectResendError(409);
        doThrow(new BudgetNotificationUnavailableException()).when(budgets).resendForApproval(budget.getBudgetId(), "customer@example.test");
        expectResendError(503);
    }

    @Test
    void shouldKeepSending503ContractAndLogOnlySafeDiagnosticWithoutSensitiveCause() throws Exception {
        try (var logs = new br.com.fiap.numberone.billing.budget.support.EcsLogCapture(BudgetExceptionHandler.class)) {
            when(serviceOrderGateway.findByServiceOrderId(budget.getServiceOrderId())).thenReturn(
                    new ServiceOrderQuotationContext(budget.getServiceOrderId(), "IN_DIAGNOSIS", List.of(), "customer@example.test"));
            var cause = new IllegalArgumentException("private-key-secret-token-password");
            doThrow(new BudgetNotificationUnavailableException("Chave AES da notificação não está em Base64 válido", cause))
                    .when(budgets).sendForApproval(budget.getBudgetId(), "customer@example.test");
            mvc.perform(patch("/api/admin/orcamentos-ordem-servico/{id}/solicitar-aprovacao", budget.getBudgetId()))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.length()").value(3))
                    .andExpect(jsonPath("$.status").value(503))
                    .andExpect(jsonPath("$.message").value("Não foi possível preparar a notificação de aprovação do orçamento"))
                    .andExpect(jsonPath("$.errors").isEmpty());
            assertThat(logs.events()).hasSize(1);
            var json = logs.events().getFirst();
            assertThat(json.path("event").path("code").asText()).isEqualTo("BUDGET_NOTIFICATION_PREPARATION_FAILED");
            assertThat(json.path("error").path("message").asText()).contains("Base64");
            assertThat(json.path("error").path("stack_trace").asText()).contains("IllegalArgumentException");
            assertThat(json.toString()).doesNotContain("private-key", "secret-token", "password");
        }
    }

    @Test
    void shouldRejectWrongStateMissingBudgetMissingEmailAndInvalidIdentifier() throws Exception {
        expectResendError(422);
        verifyNoInteractions(serviceOrderGateway);
        budget.markAsSent();
        when(serviceOrderGateway.findByServiceOrderId(budget.getServiceOrderId())).thenReturn(
                new ServiceOrderQuotationContext(budget.getServiceOrderId(), "WAITING_APPROVAL", List.of(), null));
        expectResendError(422);
        when(budgets.findById(budget.getBudgetId())).thenReturn(Optional.empty());
        expectResendError(404);
        mvc.perform(post("/api/admin/orcamentos-ordem-servico/invalido/reenviar-aprovacao"))
                .andExpect(status().isBadRequest());
        verify(budgets, never()).resendForApproval(any(), any());
        assertThatThrownBy(() -> new ResendBudgetApprovalUseCase(budgets, serviceOrderGateway).execute(null))
                .isInstanceOf(br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetDataException.class);
    }

    private void expectResendError(int code) throws Exception {
        mvc.perform(post("/api/admin/orcamentos-ordem-servico/{id}/reenviar-aprovacao", budget.getBudgetId()))
                .andExpect(status().is(code)).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.status").value(code)).andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.errors").isArray());
    }
}
