package br.com.fiap.numberone.billing.infrastructure.rest;

import br.com.fiap.numberone.billing.infrastructure.observability.HttpObservationFilter;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.util.UUID;

public class ServiceRequestHeaders implements ClientHttpRequestInterceptor {
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (request.getHeaders().getFirst("X-Correlation-Id") == null) {
            request.getHeaders().set("X-Correlation-Id", correlationId().toString());
        }
        return execution.execute(request, body);
    }

    public static UUID correlationId() {
        String value = MDC.get(HttpObservationFilter.CORRELATION);
        return value == null ? UUID.randomUUID() : UUID.fromString(value);
    }
}
