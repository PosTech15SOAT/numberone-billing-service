package br.com.fiap.numberone.billing.budget.api;

import br.com.fiap.numberone.billing.budget.application.usecases.RejectBudgetUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.ApproveBudgetUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.ResendBudgetApprovalUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.SendBudgetForApprovalUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.GetBudgetUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.CreateBudgetUseCase;
import br.com.fiap.numberone.billing.budget.api.controllers.BudgetAdminController;
import br.com.fiap.numberone.billing.budget.api.exceptions.BudgetExceptionHandler;
import br.com.fiap.numberone.billing.budget.api.mappers.BudgetApiMapper;
import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetNotFoundException;
import br.com.fiap.numberone.billing.budget.application.exceptions.EmployeeIdentityUnavailableException;
import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.application.gateways.EmployeeIdentityGateway;
import br.com.fiap.numberone.billing.budget.application.usecases.RegisterBudgetApprovalUseCase;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.enums.BudgetStatus;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetDataException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetStatusException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.ObsoleteBudgetRevisionException;
import br.com.fiap.numberone.billing.budget.domain.valueobjects.BudgetComposition;
import br.com.fiap.numberone.billing.budget.infrastructure.config.EmployeeIdentityProperties;
import br.com.fiap.numberone.billing.budget.infrastructure.identity.GatewayEmployeeIdentityAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import br.com.fiap.numberone.billing.budget.domain.references.ServiceOrderQuotationContext;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RegisterBudgetApprovalHttpTest {

    private final BudgetGateway gateway = mock(BudgetGateway.class);
    private Budget budget;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        budget = Budget.restore(UUID.randomUUID(), UUID.randomUUID(), 2, new BigDecimal("350.1234"), null,
            BudgetStatus.DRAFT, null, null, LocalDateTime.of(2026, 11, 1, 10, 0), null, new BudgetComposition(List.of()));
        mvc = mvc();
        when(gateway.findById(budget.getBudgetId())).thenReturn(Optional.of(budget));
        when(gateway.registerApproval(budget.getBudgetId(), "550e8400-e29b-41d4-a716-446655440000")).thenAnswer(invocation -> {
            budget.registerApproval(1, false, invocation.getArgument(1));
            return budget;
        });
    }

    @Test
    void shouldUseAuditContextAndPreserveTheNineLegacyResponseFields() throws Exception {
        mvc.perform(post(path()).header("X-Authenticated-Subject", "550e8400-e29b-41d4-a716-446655440000")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"funcionarioId\":\"untrusted-json-value\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(9))
                .andExpect(jsonPath("$.id").value(budget.getBudgetId().toString()))
                .andExpect(jsonPath("$.idOrdemServico").value(budget.getServiceOrderId().toString()))
                .andExpect(jsonPath("$.valorProposto").value(350.1234))
                .andExpect(jsonPath("$.valorAprovado").value(350.1234))
                .andExpect(jsonPath("$.status").value("APROVADO"))
                .andExpect(jsonPath("$.enviadoEm").isString()).andExpect(jsonPath("$.aprovadoEm").isString())
                .andExpect(jsonPath("$.created_at").isString()).andExpect(jsonPath("$.updated_at").isString());
        verify(gateway).findById(budget.getBudgetId());
        verify(gateway).registerApproval(budget.getBudgetId(), "550e8400-e29b-41d4-a716-446655440000");
        verifyNoMoreInteractions(gateway);
    }

    @Test
    void shouldRequireAuditContextWithoutInventingAnEmployee() throws Exception {
        mvc.perform(post(path())).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").isString()).andExpect(jsonPath("$.errors").isArray());
        verifyNoInteractions(gateway);
    }

    @Test
    void shouldRejectInvalidIdentifierBeforeResolvingEmployee() throws Exception {
        mvc.perform(post("/api/admin/orcamentos-ordem-servico/invalido/registrar-aprovacao"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(gateway);
        EmployeeIdentityGateway identity = mock(EmployeeIdentityGateway.class);
        assertThatThrownBy(() -> new RegisterBudgetApprovalUseCase(gateway, identity, id -> null).execute(null))
                .isInstanceOf(InvalidBudgetDataException.class);
        verifyNoInteractions(identity);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 409, 422})
    void shouldPreserveErrorEnvelope(int status) throws Exception {
        RuntimeException failure = switch (status) {
            case 404 -> new BudgetNotFoundException();
            case 409 -> new ObsoleteBudgetRevisionException();
            default -> new InvalidBudgetStatusException("Orçamento já decidido");
        };
        when(gateway.registerApproval(budget.getBudgetId(), "550e8400-e29b-41d4-a716-446655440000")).thenThrow(failure);
        mvc.perform(post(path()).header("X-Authenticated-Subject", "550e8400-e29b-41d4-a716-446655440000"))
                .andExpect(status().is(status)).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.status").value(status)).andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void shouldNotPersistWhenIdentityPortHasNoEmployee() {
        assertThatThrownBy(() -> new RegisterBudgetApprovalUseCase(gateway, () -> null, id -> null).execute(budget.getBudgetId()))
                .isInstanceOf(EmployeeIdentityUnavailableException.class);
        verifyNoInteractions(gateway);
    }

    private MockMvc mvc() {
        EmployeeIdentityGateway identity = () -> new GatewayEmployeeIdentityAdapter(
                ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest(),
                new EmployeeIdentityProperties("X-Authenticated-Subject")).currentEmployeeId();
        var mapper = JsonMapper.builder().disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        return MockMvcBuilders.standaloneSetup(new BudgetAdminController(mock(CreateBudgetUseCase.class),
                        mock(GetBudgetUseCase.class),
                        mock(SendBudgetForApprovalUseCase.class),
                        mock(ResendBudgetApprovalUseCase.class),
                        mock(ApproveBudgetUseCase.class),
                        mock(RejectBudgetUseCase.class),
                        new RegisterBudgetApprovalUseCase(gateway, identity,
                                id -> new ServiceOrderQuotationContext(id, "IN_DIAGNOSIS", List.of())),
                        new BudgetApiMapper()))
                .setControllerAdvice(new BudgetExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper)).build();
    }

    private String path() {
        return "/api/admin/orcamentos-ordem-servico/" + budget.getBudgetId() + "/registrar-aprovacao";
    }
}
