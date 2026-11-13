package br.com.fiap.numberone.billing.budget.api;

import br.com.fiap.numberone.billing.budget.application.usecases.RegisterBudgetApprovalUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.RejectBudgetUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.ApproveBudgetUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.ResendBudgetApprovalUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.SendBudgetForApprovalUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.CreateBudgetUseCase;
import br.com.fiap.numberone.billing.budget.api.controllers.BudgetIntegrationController;
import br.com.fiap.numberone.billing.budget.api.controllers.BudgetAdminController;
import br.com.fiap.numberone.billing.budget.api.exceptions.BudgetExceptionHandler;
import br.com.fiap.numberone.billing.budget.api.mappers.BudgetApiMapper;
import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.application.usecases.GetBudgetUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.GetBudgetHistoryUseCase;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.valueobjects.BudgetComposition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BudgetQueryHttpTest {

    private final BudgetGateway gateway = mock(BudgetGateway.class);
    private final UUID orderId = UUID.randomUUID();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var mapper = JsonMapper.builder().disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        mvc = MockMvcBuilders.standaloneSetup(new BudgetAdminController(mock(CreateBudgetUseCase.class),
                        new GetBudgetUseCase(gateway, id -> Optional.empty()),
                        mock(SendBudgetForApprovalUseCase.class),
                        mock(ResendBudgetApprovalUseCase.class),
                        mock(ApproveBudgetUseCase.class),
                        mock(RejectBudgetUseCase.class),
                        mock(RegisterBudgetApprovalUseCase.class),
                        new BudgetApiMapper()),
                new BudgetIntegrationController(new GetBudgetHistoryUseCase(gateway), new BudgetApiMapper()))
                .setControllerAdvice(new BudgetExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper)).build();
    }

    @Test
    void shouldReadLocationResourceUsingAllLegacyFieldsAndNullableNotification() throws Exception {
        Budget budget = draft(1);
        budget.markAsSent();
        budget.approve();
        when(gateway.findById(budget.getBudgetId())).thenReturn(Optional.of(budget));
        mvc.perform(get("/api/admin/orcamentos-ordem-servico/{id}", budget.getBudgetId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$.id").value(budget.getBudgetId().toString()))
                .andExpect(jsonPath("$.idOrdemServico").value(orderId.toString()))
                .andExpect(jsonPath("$.valorProposto").value(125.50))
                .andExpect(jsonPath("$.valorAprovado").value(125.50))
                .andExpect(jsonPath("$.status").value("APROVADO"))
                .andExpect(jsonPath("$.enviadoEm").isString())
                .andExpect(jsonPath("$.aprovadoEm").isString())
                .andExpect(jsonPath("$.created_at").isString())
                .andExpect(jsonPath("$.updated_at").isString());
        verify(gateway).findById(budget.getBudgetId());
        verifyNoMoreInteractions(gateway);
    }

    @Test
    void shouldReturn404WithLegacyErrorEnvelope() throws Exception {
        UUID missing = UUID.randomUUID();
        when(gateway.findById(missing)).thenReturn(Optional.empty());
        mvc.perform(get("/api/admin/orcamentos-ordem-servico/{id}", missing))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Orçamento não encontrado"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/admin/orcamentos-ordem-servico/invalido",
            "/internal/ordens-servico/invalido/orcamentos"})
    void shouldRejectInvalidUuid(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400)).andExpect(jsonPath("$.errors").isArray());
        verifyNoInteractions(gateway);
    }

    @Test
    void shouldReturnEmptyHistoryAndNullReference() throws Exception {
        when(gateway.findAllByServiceOrderId(orderId)).thenReturn(List.of());
        mvc.perform(get("/internal/ordens-servico/{id}/orcamentos", orderId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.idOrdemServico").value(orderId.toString()))
                .andExpect(jsonPath("$.orcamentos").isEmpty())
                .andExpect(jsonPath("$.idOrcamentoReferencia").value(nullValue()));
    }

    @Test
    void shouldReturnOneDraftWithNoReference() throws Exception {
        Budget draft = draft(1);
        when(gateway.findAllByServiceOrderId(orderId)).thenReturn(List.of(draft));
        mvc.perform(get("/internal/ordens-servico/{id}/orcamentos", orderId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orcamentos.length()").value(1))
                .andExpect(jsonPath("$.orcamentos[0].status").value("RASCUNHO"))
                .andExpect(jsonPath("$.idOrcamentoReferencia").value(nullValue()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SENT", "APPROVED", "REJECTED"})
    void shouldExposeOrderedCommercialVersionsAndReferenceWithPortugueseFields(String decision) throws Exception {
        Budget older = draft(1);
        older.markAsSent();
        Budget reference = draft(2);
        reference.markAsSent();
        if (decision.equals("APPROVED")) reference.approve();
        if (decision.equals("REJECTED")) reference.reject();
        Budget newer = draft(3);
        when(gateway.findAllByServiceOrderId(orderId)).thenReturn(List.of(older, reference, newer));
        String publicStatus = switch (decision) {
            case "APPROVED" -> "APROVADO";
            case "REJECTED" -> "REJEITADO";
            default -> "ENVIADO";
        };
        var result = mvc.perform(get("/internal/ordens-servico/{id}/orcamentos", orderId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orcamentos.length()").value(3))
                .andExpect(jsonPath("$.idOrcamentoReferencia").value(reference.getBudgetId().toString()))
                .andExpect(jsonPath("$.orcamentos[0].versao").value(1))
                .andExpect(jsonPath("$.orcamentos[1].versao").value(2))
                .andExpect(jsonPath("$.orcamentos[2].versao").value(3))
                .andExpect(jsonPath("$.orcamentos[1].length()").value(7))
                .andExpect(jsonPath("$.orcamentos[1].id").value(reference.getBudgetId().toString()))
                .andExpect(jsonPath("$.orcamentos[1].valorProposto").value(125.50))
                .andExpect(jsonPath("$.orcamentos[1].status").value(publicStatus))
                .andExpect(jsonPath("$.orcamentos[1].enviadoEm").isString());
        if (decision.equals("APPROVED")) {
            result.andExpect(jsonPath("$.orcamentos[1].valorAprovado").value(125.50))
                    .andExpect(jsonPath("$.orcamentos[1].aprovadoEm").isString());
        } else {
            result.andExpect(jsonPath("$.orcamentos[1].valorAprovado").value(nullValue()))
                    .andExpect(jsonPath("$.orcamentos[1].aprovadoEm").value(nullValue()));
        }
        verify(gateway).findAllByServiceOrderId(orderId);
        verifyNoMoreInteractions(gateway);
    }

    private Budget draft(int version) {
        return Budget.createDraft(orderId, version, new BigDecimal("125.50"), new BudgetComposition(List.of()));
    }
}
