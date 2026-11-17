package br.com.fiap.numberone.billing.shared.config;

import br.com.fiap.numberone.billing.shared.infrastructure.observability.NumberOneHealthTraceInterceptor;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.interceptor.TraceInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DatadogTraceConfig {

    @Bean
    public TraceInterceptor numberOneHealthTraceInterceptor() {
        TraceInterceptor interceptor = new NumberOneHealthTraceInterceptor();
        GlobalTracer.get().addTraceInterceptor(interceptor);
        return interceptor;
    }
}
