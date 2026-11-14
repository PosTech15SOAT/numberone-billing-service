package br.com.fiap.numberone.billing.infrastructure.rest;

import br.com.fiap.numberone.billing.budget.api.controllers.BillingSagaController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.util.Collections;

@Configuration(proxyBeanMethods = false)
public class BillingSagaCorrelation implements WebMvcConfigurer, HandlerInterceptor {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!(handler instanceof HandlerMethod method) || method.getBeanType() != BillingSagaController.class) return true;
        var correlation = Collections.list(request.getHeaders("X-Correlation-Id"));
        if (correlation.size() != 1 || !correlation.getFirst().matches(
                "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            return reject(response, 400, "INVALID_REQUEST");
        }
        return true;
    }

    private boolean reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().printf("{\"code\":\"%s\",\"message\":\"%s\",\"correlationId\":\"%s\"}",
                code, code, ServiceRequestHeaders.correlationId());
        return false;
    }
}
