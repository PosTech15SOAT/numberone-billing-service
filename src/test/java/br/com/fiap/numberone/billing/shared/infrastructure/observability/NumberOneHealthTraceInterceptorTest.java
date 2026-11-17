package br.com.fiap.numberone.billing.shared.infrastructure.observability;

import datadog.trace.api.interceptor.MutableSpan;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NumberOneHealthTraceInterceptorTest {

    private final NumberOneHealthTraceInterceptor interceptor = new NumberOneHealthTraceInterceptor();

    @Test
    void dropsHealthResourcesIncludingNestedPaths() {
        for (String resource : List.of(
                "GET /actuator/health",
                "GET /actuator/health/**",
                "GET /actuator/health/liveness",
                "GET /actuator/health/readiness",
                "GET /actuator/health/custom/deep/path")) {
            Collection<? extends MutableSpan> filtered = interceptor.onTraceComplete(
                    List.of(span(NumberOneHealthTraceInterceptor.SERVICE_NAME, resource)));

            assertThat(filtered).as(resource).isEmpty();
        }
    }

    @Test
    void keepsBusinessResources() {
        MutableSpan businessSpan = span(
                NumberOneHealthTraceInterceptor.SERVICE_NAME,
                "POST /api/business-operation");
        List<MutableSpan> trace = List.of(businessSpan);

        assertThat(interceptor.onTraceComplete(trace)).isSameAs(trace);
    }

    @Test
    void keepsHealthResourcesFromOtherServices() {
        MutableSpan otherServiceHealth = span("another-service", "GET /actuator/health/readiness");
        List<MutableSpan> trace = List.of(otherServiceHealth);

        assertThat(interceptor.onTraceComplete(trace)).isSameAs(trace);
    }

    private MutableSpan span(String service, String resource) {
        MutableSpan span = mock(MutableSpan.class);
        when(span.getServiceName()).thenReturn(service);
        when(span.getResourceName()).thenReturn(resource);
        return span;
    }
}
