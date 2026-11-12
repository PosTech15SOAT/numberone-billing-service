package br.com.fiap.numberone.billing.budget.api;

import br.com.fiap.numberone.billing.infrastructure.observability.support.FailingLogAppender;
import br.com.fiap.numberone.billing.budget.api.controllers.BudgetPublicController;
import br.com.fiap.numberone.billing.budget.api.publicview.PublicBudgetRequest;
import br.com.fiap.numberone.billing.budget.api.publicview.PublicBudgetRequestFilter;
import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetAuthorizationException;
import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.application.results.PublicBudgetView;
import br.com.fiap.numberone.billing.budget.application.usecases.ApproveBudgetRemotelyUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.GetBudgetApprovalPageUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.RejectBudgetRemotelyUseCase;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.enums.BudgetStatus;
import br.com.fiap.numberone.billing.budget.domain.valueobjects.BudgetComposition;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PublicBudgetHttpTest {
    private static final String TOKEN = "A".repeat(43);
    private static final String BASE = PublicBudgetRequestFilter.BASE_PATH;
    private final BudgetGateway gateway = mock(BudgetGateway.class);
    private final Budget budget = Budget.restore(UUID.randomUUID(), UUID.randomUUID(), 7, new BigDecimal("123.456700"), null,
            BudgetStatus.DRAFT, null, null, LocalDateTime.of(2026, 11, 1, 10, 0), null,
            new BudgetComposition(List.of(new BudgetComposition.ServiceItem(UUID.randomUUID(), UUID.randomUUID(), null,
                    null, List.of(new BudgetComposition.Supply(UUID.randomUUID(), null, null, "<script>unsafe</script>"))))));
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        budget.markAsSent();
        var templates = new ClassLoaderTemplateResolver();
        templates.setPrefix("templates/");
        templates.setSuffix(".html");
        templates.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(templates);
        var views = new ThymeleafViewResolver();
        views.setTemplateEngine(engine);
        views.setCharacterEncoding("UTF-8");
        mvc = MockMvcBuilders.standaloneSetup(new BudgetPublicController(new GetBudgetApprovalPageUseCase(gateway),
                new ApproveBudgetRemotelyUseCase(gateway), new RejectBudgetRemotelyUseCase(gateway)))
                .setViewResolvers(views).addFilters(new PublicBudgetRequestFilter()).build();
        views.setApplicationContext(mvc.getDispatcherServlet().getWebApplicationContext());
        when(gateway.getAuthorizedBudget(any())).thenAnswer(invocation -> {
            validateToken(invocation.getArgument(0));
            return new PublicBudgetView(budget.getBudgetId(), 7, budget.getQuotedAmount(),
                    Instant.parse("2026-11-08T15:00:00Z"), budget.getComposition());
        });
        when(gateway.approveRemotely(any())).thenAnswer(invocation -> {
            validateToken(invocation.getArgument(0)); budget.approveRemotely(); return budget;
        });
        when(gateway.rejectRemotely(any())).thenAnswer(invocation -> {
            validateToken(invocation.getArgument(0)); budget.reject(); return budget;
        });
    }

    @Test
    void shouldRenderResponsivePageWithoutDecisionOrUnnecessaryData() throws Exception {
        String html = mvc.perform(get(BASE + "/decisao").queryParam("token", TOKEN))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
                .andExpect(header().string("Content-Security-Policy", containsString("style-src 'self'")))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().doesNotExist("Location"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("lang=\"pt-BR\"", "width=device-width", budget.getBudgetId().toString(), "123,456700",
                "08/11/2026 15:00:00", "Aprovar orçamento", "Rejeitar orçamento", "required", "method=\"post\"",
                "não informado", "&lt;script&gt;unsafe&lt;/script&gt;", "href=\"/css/budget.css\"");
        assertThat(html).doesNotContain("<script>", "<img", "<style", "src=", "customerEmail", "row_version",
                "token_hash", "approved_by_employee_id", budget.getServiceOrderId().toString(),
                budget.getComposition().items().getFirst().automotiveServiceId().toString());
        verify(gateway).getAuthorizedBudget(TOKEN);
        verifyNoMoreInteractions(gateway);
    }

    @Test
    void shouldKeepLocalCssAndFormActionsUnderTheServletContext() throws Exception {
        String html = mvc.perform(get("/billing" + BASE + "/decisao").contextPath("/billing").queryParam("token", TOKEN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("href=\"/billing/css/budget.css\"",
                "action=\"/billing" + BASE + "/aprovar\"", "action=\"/billing" + BASE + "/rejeitar\"");
        assertThat(html).doesNotContain("<style", "<script>");
        verify(gateway).getAuthorizedBudget(TOKEN);
        verifyNoMoreInteractions(gateway);
    }

    @ParameterizedTest
    @ValueSource(strings = {"aprovar", "rejeitar"})
    void shouldRequirePostAndExplicitConfirmation(String operation) throws Exception {
        mvc.perform(get(BASE + "/" + operation).queryParam("token", TOKEN)).andExpect(status().isMethodNotAllowed());
        mvc.perform(post(BASE + "/" + operation).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("token", TOKEN))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("Confirme sua decisão")));
        verifyNoInteractions(gateway);
    }

    @ParameterizedTest
    @ValueSource(strings = {"aprovar", "rejeitar"})
    void shouldConfirmDecisionWithoutEchoingTokenOrRedirecting(String operation) throws Exception {
        String html = mvc.perform(post(BASE + "/" + operation).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", TOKEN).param("confirmacao", "sim"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Location"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains(operation.equals("aprovar") ? "Orçamento aprovado" : "Orçamento rejeitado");
        assertThat(html).doesNotContain(TOKEN, "<form", "<script>");
        if (operation.equals("aprovar")) verify(gateway).approveRemotely(TOKEN);
        else verify(gateway).rejectRemotely(TOKEN);
    }

    @ParameterizedTest
    @CsvSource({"INVALID,404,Link inválido", "EXPIRED,410,Link expirado", "REVOKED,410,Link revogado",
            "CONSUMED,409,Link já utilizado", "ALREADY_DECIDED,409,Orçamento já decidido", "OBSOLETE,409,Revisão desatualizada"})
    void shouldRenderClearSafeErrors(BudgetAuthorizationException.Reason reason, int statusCode, String title) throws Exception {
        doThrow(new BudgetAuthorizationException(reason)).when(gateway).getAuthorizedBudget(any());
        String html = mvc.perform(get(BASE + "/decisao").queryParam("token", TOKEN))
                .andExpect(status().is(statusCode)).andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Cache-Control", "no-store, private, max-age=0"))
                .andExpect(header().string("Content-Security-Policy", containsString("style-src 'self'")))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains(title, "href=\"/css/budget.css\"").doesNotContain(TOKEN, "<form", budget.getBudgetId().toString());
        if (reason == BudgetAuthorizationException.Reason.EXPIRED) assertThat(html).contains("solicitar um novo link");
    }

    @Test
    void shouldRejectMissingMalformedRepeatedTokensAndTokensInPostUrl() throws Exception {
        mvc.perform(get(BASE + "/decisao")).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/decisao").queryParam("token", "<script>invalid</script>"))
                .andExpect(status().isNotFound()).andExpect(content().string(not(containsString("<script>invalid"))));
        mvc.perform(get(BASE + "/decisao").queryParam("token", TOKEN, TOKEN)).andExpect(status().isNotFound());
        mvc.perform(post(BASE + "/aprovar").queryParam("token", TOKEN)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("confirmacao", "sim"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/aprovar").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", TOKEN).param("confirmacao", "sim", "sim"))
                .andExpect(status().isBadRequest());
        verify(gateway, never()).approveRemotely(any());
    }

    @Test
    void shouldMapOptimisticConflictWithoutExposingDetails() throws Exception {
        doThrow(new ObjectOptimisticLockingFailureException("row_version=private", new IllegalStateException(TOKEN)))
                .when(gateway).approveRemotely(TOKEN);
        mvc.perform(post(BASE + "/aprovar").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", TOKEN).param("confirmacao", "sim"))
                .andExpect(status().isConflict()).andExpect(content().string(not(containsString("row_version"))));
    }

    @Test
    void shouldNotLogTokenEvenAtTraceOrWhenUnexpectedErrorContainsIt() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level previous = logger.getLevel();
        var events = new ListAppender<ILoggingEvent>(); events.start(); logger.addAppender(events); logger.setLevel(Level.TRACE);
        try {
            mvc.perform(get(BASE + "/decisao").queryParam("token", TOKEN)).andExpect(status().isOk());
            doThrow(new IllegalStateException("SQL and secret " + TOKEN)).when(gateway).approveRemotely(TOKEN);
            mvc.perform(post(BASE + "/aprovar").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .content("token=" + TOKEN + "&confirmacao=sim"))
                    .andExpect(status().isInternalServerError()).andExpect(content().string(not(containsString(TOKEN))))
                    .andExpect(content().string(not(containsString("SQL"))));
            assertThat(events.list).isNotEmpty();
            assertThat(events.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(TOKEN);
                if (event.getThrowableProxy() != null) assertThat(event.getThrowableProxy().getMessage()).doesNotContain(TOKEN);
            });
        } finally { logger.setLevel(previous); logger.detachAppender(events); events.stop(); }
        assertThat(new PublicBudgetRequest(TOKEN, true).toString()).doesNotContain(TOKEN);
    }

    @Test
    void shouldHideBodyAndQueryFromDownstreamLoggersWithoutAffectingOtherRoutes() throws Exception {
        var request = new MockHttpServletRequest("POST", BASE + "/aprovar");
        request.setContentType("application/x-www-form-urlencoded"); request.setContent(("token=" + TOKEN).getBytes());
        request.addParameter("token", TOKEN); request.addParameter("confirmacao", "sim");
        new PublicBudgetRequestFilter().doFilter(request, new MockHttpServletResponse(), (wrapped, response) -> {
            var secured = (jakarta.servlet.http.HttpServletRequest) wrapped;
            assertThat(secured.getQueryString()).isNull(); assertThat(secured.getParameterMap()).isEmpty();
            assertThat(secured.getInputStream().read()).isEqualTo(-1);
            assertThat(secured.getReader().readLine()).isNull();
            assertThat(((PublicBudgetRequest) secured.getAttribute(PublicBudgetRequest.ATTRIBUTE)).token()).isEqualTo(TOKEN);
        });
        var admin = new MockHttpServletRequest("POST", "/api/admin/orcamentos");
        admin.addParameter("valorProposto", "100");
        new PublicBudgetRequestFilter().doFilter(admin, new MockHttpServletResponse(), (original, response) -> {
            assertThat(original.getParameter("valorProposto")).isEqualTo("100");
        });
    }

    private void validateToken(String value) {
        if (!TOKEN.equals(value)) throw new BudgetAuthorizationException(BudgetAuthorizationException.Reason.INVALID);
    }

    @Test
    void shouldRenderOriginalErrorPageWhenLoggingFails() throws Exception {
        var failure = new IllegalStateException("Falha funcional", new IllegalArgumentException("Causa original"));
        doThrow(failure).when(gateway).approveRemotely(TOKEN);
        try (var logging = new FailingLogAppender(BudgetPublicController.class)) {
            mvc.perform(post(BASE + "/aprovar").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("token", TOKEN).param("confirmacao", "sim"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(view().name("budget/decision-result"))
                    .andExpect(model().attribute("title", "Não foi possível concluir"))
                    .andExpect(content().string(containsString("Ocorreu um erro inesperado.")))
                    .andExpect(content().string(not(containsString("Falha funcional"))));
            assertThat(logging.eventCodes()).containsExactly("BUDGET_PUBLIC_REQUEST_FAILED");
        }
        verify(gateway).approveRemotely(TOKEN);
        verifyNoMoreInteractions(gateway);
    }

}
