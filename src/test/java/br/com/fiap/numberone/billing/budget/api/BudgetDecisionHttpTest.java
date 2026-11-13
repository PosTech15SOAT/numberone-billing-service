package br.com.fiap.numberone.billing.budget.api;

import br.com.fiap.numberone.billing.budget.application.usecases.RegisterBudgetApprovalUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.ResendBudgetApprovalUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.SendBudgetForApprovalUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.GetBudgetUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.CreateBudgetUseCase;
import br.com.fiap.numberone.billing.budget.api.controllers.BudgetAdminController;
import br.com.fiap.numberone.billing.budget.api.exceptions.BudgetExceptionHandler;
import br.com.fiap.numberone.billing.budget.api.mappers.BudgetApiMapper;
import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetNotFoundException;
import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.application.usecases.ApproveBudgetUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.RejectBudgetUseCase;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.enums.BudgetStatus;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetDataException;
import br.com.fiap.numberone.billing.budget.domain.valueobjects.BudgetComposition;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BudgetDecisionHttpTest {

    private final BudgetGateway gateway = mock(BudgetGateway.class);
    private final ApproveBudgetUseCase approveUseCase = new ApproveBudgetUseCase(gateway);
    private final RejectBudgetUseCase rejectUseCase = new RejectBudgetUseCase(gateway);
    private Budget budget;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        budget = draft();
        budget.markAsSent();
        JsonMapper jsonMapper = JsonMapper.builder().disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        mvc = MockMvcBuilders.standaloneSetup(new BudgetAdminController(mock(CreateBudgetUseCase.class),
                        mock(GetBudgetUseCase.class),
                        mock(SendBudgetForApprovalUseCase.class),
                        mock(ResendBudgetApprovalUseCase.class),
                        approveUseCase,
                        rejectUseCase,
                        mock(RegisterBudgetApprovalUseCase.class),
                        new BudgetApiMapper()))
                .setControllerAdvice(new BudgetExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(jsonMapper)).build();
        when(gateway.approve(budget.getBudgetId())).thenAnswer(invocation -> {
            budget.approve();
            return budget;
        });
        when(gateway.reject(budget.getBudgetId())).thenAnswer(invocation -> {
            budget.reject();
            return budget;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"aprovar", "rejeitar"})
    void shouldPreserveLegacyRoutesResponseAndAmounts(String operation) throws Exception {
        var composition = budget.getComposition();
        var sentAt = budget.getSentAt();
        var response = decision(operation).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(9))
                .andExpect(jsonPath("$.id").value(budget.getBudgetId().toString()))
                .andExpect(jsonPath("$.idOrdemServico").value(budget.getServiceOrderId().toString()))
                .andExpect(jsonPath("$.valorProposto").value(150.00))
                .andExpect(jsonPath("$.enviadoEm").isString())
                .andExpect(jsonPath("$.created_at").isString())
                .andExpect(jsonPath("$.updated_at").isString())
                .andExpect(jsonPath("$.budgetVersion").doesNotExist())
                .andExpect(jsonPath("$.rowVersion").doesNotExist())
                .andExpect(jsonPath("$.row_version").doesNotExist());
        if (operation.equals("aprovar")) {
            response.andExpect(jsonPath("$.status").value("APROVADO"))
                    .andExpect(jsonPath("$.valorAprovado").value(150.00))
                    .andExpect(jsonPath("$.aprovadoEm").isString());
            verify(gateway).approve(budget.getBudgetId());
        } else {
            response.andExpect(jsonPath("$.status").value("REJEITADO"))
                    .andExpect(jsonPath("$.valorAprovado").value(nullValue()))
                    .andExpect(jsonPath("$.aprovadoEm").value(nullValue()));
            verify(gateway).reject(budget.getBudgetId());
        }
        assertThat(budget.getComposition()).isSameAs(composition);
        assertThat(budget.getSentAt()).isEqualTo(sentAt);
        assertThat(budget.getBudgetVersion()).isEqualTo(3);
        verifyNoMoreInteractions(gateway);
    }

    @ParameterizedTest
    @ValueSource(strings = {"aprovar", "rejeitar"})
    void shouldReturn404WithoutInsertingMissingBudget(String operation) throws Exception {
        if (operation.equals("aprovar")) doThrow(new BudgetNotFoundException()).when(gateway).approve(budget.getBudgetId());
        else doThrow(new BudgetNotFoundException()).when(gateway).reject(budget.getBudgetId());
        expectError(decision(operation), 404);
    }

    @ParameterizedTest
    @CsvSource({"aprovar,DRAFT", "aprovar,APPROVED", "aprovar,REJECTED",
            "rejeitar,DRAFT", "rejeitar,APPROVED", "rejeitar,REJECTED"})
    void shouldPreserve422ForInvalidTransition(String operation, BudgetStatus status) throws Exception {
        if (status == BudgetStatus.DRAFT) {
            budget = Budget.restore(budget.getBudgetId(), budget.getServiceOrderId(), budget.getBudgetVersion(),
                    budget.getQuotedAmount(), null, status, null, null, budget.getCreatedAt(), budget.getUpdatedAt(),
                    budget.getComposition());
        } else if (status == BudgetStatus.APPROVED) budget.approve();
        else budget.reject();
        expectError(decision(operation), 422);
        assertThat(budget.getStatus()).isEqualTo(status);
    }

    @ParameterizedTest
    @ValueSource(strings = {"aprovar", "rejeitar"})
    void shouldReturn409ForOptimisticConflict(String operation) throws Exception {
        var failure = new ObjectOptimisticLockingFailureException(Budget.class, budget.getBudgetId());
        if (operation.equals("aprovar")) doThrow(failure).when(gateway).approve(budget.getBudgetId());
        else doThrow(failure).when(gateway).reject(budget.getBudgetId());
        expectError(decision(operation), 409);
        assertThat(budget.getStatus()).isEqualTo(BudgetStatus.SENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"aprovar", "rejeitar"})
    void shouldReturn409ForObsoleteRevision(String operation) throws Exception {
        var failure = new br.com.fiap.numberone.billing.budget.domain.exceptions.ObsoleteBudgetRevisionException();
        if (operation.equals("aprovar")) doThrow(failure).when(gateway).approve(budget.getBudgetId());
        else doThrow(failure).when(gateway).reject(budget.getBudgetId());
        expectError(decision(operation), 409);
        assertThat(budget.getStatus()).isEqualTo(BudgetStatus.SENT);
    }

    @Test
    void shouldAlsoHandleJpaOptimisticConflict() throws Exception {
        doThrow(new OptimisticLockException("Detalhes internos")).when(gateway).approve(budget.getBudgetId());
        expectError(decision("aprovar"), 409).andExpect(jsonPath("$.message").value(
                "O orçamento foi alterado por outra operação. Consulte o estado atual antes de tentar novamente"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"aprovar", "rejeitar"})
    void shouldRejectMalformedIdentifier(String operation) throws Exception {
        expectError(mvc.perform(patch("/api/admin/orcamentos-ordem-servico/invalido/" + operation)), 400);
        verifyNoInteractions(gateway);
    }

    @Test
    void shouldRejectMissingIdentifiersBeforeCallingGateway() {
        assertThatThrownBy(() -> approveUseCase.execute(null)).isInstanceOf(InvalidBudgetDataException.class);
        assertThatThrownBy(() -> rejectUseCase.execute(null)).isInstanceOf(InvalidBudgetDataException.class);
        verifyNoInteractions(gateway);
    }

    private Budget draft() {
        return Budget.createDraft(UUID.randomUUID(), 3, new BigDecimal("150.00"), new BudgetComposition(List.of()));
    }

    private ResultActions decision(String operation) throws Exception {
        return mvc.perform(patch("/api/admin/orcamentos-ordem-servico/" + budget.getBudgetId() + "/" + operation));
    }

    private ResultActions expectError(ResultActions result, int status) throws Exception {
        return result.andExpect(status().is(status)).andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.message").isString()).andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.length()").value(3));
    }
}
