package br.com.fiap.numberone.billing.shared.config;

import br.com.fiap.numberone.billing.shared.infrastructure.observability.NumberOneHealthTraceInterceptor;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.Tracer;
import datadog.trace.api.interceptor.TraceInterceptor;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

class DatadogTraceConfigTest {

    @Test
    void registersHealthTraceInterceptorWithGlobalTracer() {
        Tracer tracer = mock(Tracer.class);
        try (MockedStatic<GlobalTracer> globalTracer = mockStatic(GlobalTracer.class)) {
            globalTracer.when(GlobalTracer::get).thenReturn(tracer);

            TraceInterceptor interceptor = new DatadogTraceConfig().numberOneHealthTraceInterceptor();

            assertThat(interceptor).isInstanceOf(NumberOneHealthTraceInterceptor.class);
            verify(tracer).addTraceInterceptor(interceptor);
        }
    }
}
