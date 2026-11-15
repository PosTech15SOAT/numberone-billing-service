package br.com.fiap.numberone.billing.infrastructure.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpObservationFilter extends OncePerRequestFilter {
    public static final String ERROR = HttpObservationFilter.class.getName() + ".error";
    public static final String STAGE = HttpObservationFilter.class.getName() + ".stage";
    public static final String CORRELATION = "correlation.id";
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpObservationFilter.class);
    private static final Set<String> HEALTH_PATHS = Set.of(
            "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        long started = System.nanoTime();
        boolean failed = false;
        boolean healthCheck = ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
                && HEALTH_PATHS.contains(request.getRequestURI().substring(request.getContextPath().length()));
        try {
            MDC.put(CORRELATION, correlation(request));
            if (!healthCheck) ObservationLog.safely(() -> LOGGER.atInfo().addKeyValue("event.code", "HTTP_REQUEST_STARTED")
                    .addKeyValue("http.request.method", method(request)).addKeyValue("http.route", route(request))
                    .log("Requisição HTTP recebida"));
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException failure) {
            failed = true;
            request.setAttribute(ERROR, "UNHANDLED_EXCEPTION");
            throw failure;
        } finally {
            int status = failed ? 500 : response.getStatus();
            if (!healthCheck || status >= 400) ObservationLog.safely(() -> {
                var log = status >= 500 ? LOGGER.atError() : status >= 400 ? LOGGER.atWarn() : LOGGER.atInfo();
                log.addKeyValue("event.code", "HTTP_REQUEST_COMPLETED")
                        .addKeyValue("http.request.method", method(request)).addKeyValue("http.route", route(request))
                        .addKeyValue("http.response.status_code", status)
                        .addKeyValue("event.duration", System.nanoTime() - started)
                        .addKeyValue("event.outcome", status >= 400 ? "failure" : "success");
                Object error = request.getAttribute(ERROR);
                if (error != null) log.addKeyValue("error.code", error);
                else if (status >= 400) log.addKeyValue("error.code", "HTTP_" + status);
                Object stage = request.getAttribute(STAGE);
                if (stage != null) log.addKeyValue("operation.stage", stage);
                log.log("Requisição HTTP concluída");
            });
            if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
        }
    }

    private String correlation(HttpServletRequest request) {
        var headers = request.getHeaders("X-Correlation-Id");
        String value = headers == null || !headers.hasMoreElements() ? null : headers.nextElement();
        if (value != null && !headers.hasMoreElements()
                && value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            return value;
        }
        return UUID.randomUUID().toString();
    }

    private String method(HttpServletRequest request) {
        String method = request.getMethod();
        return method != null && method.matches("GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE") ? method : "OTHER";
    }

    private String route(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (HEALTH_PATHS.contains(path) || path.equals("/api/public/pagamentos/notificacoes/mercado-pago")
                || path.matches("/api/public/orcamentos-ordem-servico(?:/(?:aprovar|rejeitar))?")) return path;
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        // Apenas templates de controllers, nunca URI, query, headers ou parâmetros de clientes.
        return pattern instanceof String route && route.startsWith("/api/") ? route : "UNMAPPED";
    }
}
