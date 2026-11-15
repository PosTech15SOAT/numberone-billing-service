package br.com.fiap.numberone.billing.budget.api.publicview;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PublicBudgetRequestFilter extends OncePerRequestFilter {
    public static final String BASE_PATH = "/api/public/orcamentos-ordem-servico";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals(BASE_PATH) && !path.startsWith(BASE_PATH + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store, private, max-age=0");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", "default-src 'none'; style-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'");
        PublicBudgetRequest data = readRequest(request);
        request.setAttribute(PublicBudgetRequest.ATTRIBUTE, data);
        // O MVC não recebe query, parâmetros ou body que possam aparecer em logs DEBUG/TRACE.
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public String getQueryString() { return null; }
            @Override public String getParameter(String name) { return null; }
            @Override public String[] getParameterValues(String name) { return null; }
            @Override public Map<String, String[]> getParameterMap() { return Map.of(); }
            @Override public Enumeration<String> getParameterNames() { return Collections.emptyEnumeration(); }
            @Override public int getContentLength() { return 0; }
            @Override public long getContentLengthLong() { return 0; }
            @Override public BufferedReader getReader() { return new BufferedReader(new StringReader("")); }
            @Override public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    @Override public int read() { return -1; }
                    @Override public boolean isFinished() { return true; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) {
                        try { listener.onAllDataRead(); }
                        catch (IOException exception) { throw new IllegalStateException("Falha na leitura da requisição pública"); }
                    }
                };
            }
        }, response);
    }

    private PublicBudgetRequest readRequest(HttpServletRequest request) {
        try {
            boolean post = "POST".equals(request.getMethod());
            String contentType = request.getContentType();
            if (post && (request.getQueryString() != null || contentType == null
                    || !"application/x-www-form-urlencoded".equalsIgnoreCase(contentType.split(";")[0].strip()))) {
                return new PublicBudgetRequest(null, false);
            }
            String[] tokens = request.getParameterValues("token");
            String token = tokens != null && tokens.length == 1 && tokens[0].matches("[A-Za-z0-9_-]{43}") ? tokens[0] : null;
            String[] confirmations = request.getParameterValues("confirmacao");
            boolean confirmed = post && confirmations != null && confirmations.length == 1 && "sim".equals(confirmations[0]);
            return new PublicBudgetRequest(token, confirmed);
        } catch (RuntimeException exception) {
            return new PublicBudgetRequest(null, false);
        }
    }
}
