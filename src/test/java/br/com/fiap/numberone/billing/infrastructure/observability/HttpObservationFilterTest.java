package br.com.fiap.numberone.billing.infrastructure.observability;

import br.com.fiap.numberone.billing.budget.support.EcsLogCapture;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class HttpObservationFilterTest {
    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    void shouldKeepSuccessfulHealthChecksSilentAndPreserveContext(String path) throws Exception {
        var previous = MDC.getCopyOfContextMap();
        MDC.put("correlation.id", "parent");
        var request = new MockHttpServletRequest("GET", "/billing" + path);
        request.setContextPath("/billing");
        try (var logs = new EcsLogCapture(HttpObservationFilter.class)) {
            new HttpObservationFilter().doFilter(request, new MockHttpServletResponse(), (req, res) ->
                    assertThat(MDC.get("correlation.id")).isNotBlank().isNotEqualTo("parent"));
            assertThat(logs.events()).isEmpty();
            assertThat(MDC.get("correlation.id")).isEqualTo("parent");
        } finally { if (previous == null) MDC.clear(); else MDC.setContextMap(previous); }
    }

    @Test
    void shouldPreserveHealthFailureLogsAndExceptionPropagation() throws Exception {
        try (var logs = new EcsLogCapture(HttpObservationFilter.class)) {
            var request = new MockHttpServletRequest("GET", "/actuator/health/readiness");
            var response = new MockHttpServletResponse();
            new HttpObservationFilter().doFilter(request, response, (req, res) -> response.setStatus(503));
            assertThat(logs.events()).hasSize(1);
            assertThat(logs.events().getFirst().path("log").path("level").asText()).isEqualTo("ERROR");
            assertThat(logs.events().getFirst().path("http").path("route").asText()).isEqualTo("/actuator/health/readiness");
            assertThatThrownBy(() -> new HttpObservationFilter().doFilter(
                    new MockHttpServletRequest("GET", "/actuator/health/liveness"),
                    new MockHttpServletResponse(), (req, res) -> { throw new ServletException("private-secret"); }))
                    .isInstanceOf(ServletException.class);
            assertThat(logs.events()).hasSize(2);
            assertThat(logs.events().getLast().path("error").path("code").asText()).isEqualTo("UNHANDLED_EXCEPTION");
            assertThat(logs.events().toString()).doesNotContain("private-secret");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/healthcheck", "/actuator/health/readiness/other", "/api/admin/health"})
    void shouldContinueObservingPathsOutsideTheExactHealthEndpoints(String path) throws Exception {
        try (var logs = new EcsLogCapture(HttpObservationFilter.class)) {
            new HttpObservationFilter().doFilter(new MockHttpServletRequest("GET", path),
                    new MockHttpServletResponse(), (req, res) -> { });
            assertThat(logs.events()).hasSize(2);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"private-token", " ", "request\ninjection"})
    void shouldGenerateCorrelationAndCleanContextWithoutLoggingRequestData(String supplied) throws Exception {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        MDC.clear();
        try (var logs = new EcsLogCapture(HttpObservationFilter.class)) {
            var request = new MockHttpServletRequest("POST", "/api/admin/private-path-value");
            request.setQueryString("token=private-query");
            request.addHeader("Authorization", "Bearer private-credential");
            request.setContent("private-body".getBytes());
            if (supplied != null) request.addHeader("X-Correlation-Id", supplied);
            var response = new MockHttpServletResponse();
            new HttpObservationFilter().doFilter(request, response, (req, res) -> {
                assertThatCode(() -> UUID.fromString(MDC.get("correlation.id"))).doesNotThrowAnyException();
                req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/admin/{id}");
                response.setStatus(401);
            });
            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
            var events = logs.events();
            assertThat(events).hasSize(2);
            var completed = events.getLast();
            assertThat(completed.path("event").path("code").asText()).isEqualTo("HTTP_REQUEST_COMPLETED");
            assertThat(completed.path("http").path("response").path("status_code").asInt()).isEqualTo(401);
            assertThat(completed.path("http").path("route").asText()).isEqualTo("/api/admin/{id}");
            assertThat(completed.path("event").path("duration").asLong()).isGreaterThanOrEqualTo(0);
            assertThat(completed.path("correlation").path("id").asText()).isEqualTo(events.getFirst().path("correlation").path("id").asText()).isNotBlank();
            assertThat(events.toString()).doesNotContain("private-", "Bearer", "request\\ninjection");
        } finally { if (previous == null) MDC.clear(); else MDC.setContextMap(previous); }
    }

    @Test
    void shouldReuseSingleUuidAndRestoreCallerContextAfterEarlyFilterFailure() throws Exception {
        var previous = MDC.getCopyOfContextMap();
        MDC.put("correlation.id", "parent");
        String correlation = UUID.randomUUID().toString();
        var request = new MockHttpServletRequest("POST", "/api/public/pagamentos/notificacoes/mercado-pago");
        request.addHeader("X-Correlation-Id", correlation);
        try (var logs = new EcsLogCapture(HttpObservationFilter.class)) {
            assertThatThrownBy(() -> new HttpObservationFilter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                assertThat(MDC.get("correlation.id")).isEqualTo(correlation);
                throw new ServletException("private-secret");
            })).isInstanceOf(ServletException.class);
            assertThat(MDC.get("correlation.id")).isEqualTo("parent");
            assertThat(logs.events().getLast().path("error").path("code").asText()).isEqualTo("UNHANDLED_EXCEPTION");
            assertThat(logs.events().toString()).doesNotContain("private-secret");
        } finally { if (previous == null) MDC.clear(); else MDC.setContextMap(previous); }
    }

    @Test
    void shouldNotTrustRepeatedCorrelationHeaders() throws Exception {
        var request = new MockHttpServletRequest("GET", "/unknown-private-path");
        String supplied = UUID.randomUUID().toString();
        request.addHeader("X-Correlation-Id", supplied);
        request.addHeader("X-Correlation-Id", supplied);
        try (var logs = new EcsLogCapture(HttpObservationFilter.class)) {
            new HttpObservationFilter().doFilter(request, new MockHttpServletResponse(), (req, res) ->
                    assertThat(MDC.get("correlation.id")).isNotEqualTo(supplied));
            assertThat(logs.events().getLast().path("http").path("route").asText()).isEqualTo("UNMAPPED");
        }
    }

    @Test
    void shouldContainLoggingSinkFailures() {
        assertThatCode(() -> ObservationLog.safely(() -> { throw new IllegalStateException("sink failed"); }))
                .doesNotThrowAnyException();
    }
}
