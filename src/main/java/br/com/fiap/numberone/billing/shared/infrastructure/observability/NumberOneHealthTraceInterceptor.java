package br.com.fiap.numberone.billing.shared.infrastructure.observability;

import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Pattern;

public class NumberOneHealthTraceInterceptor implements TraceInterceptor {

    static final String SERVICE_NAME = "numberone-billing";
    static final Pattern HEALTH_RESOURCE = Pattern.compile("^GET /actuator/health(?:/.*)?$");

    @Override
    public Collection<? extends MutableSpan> onTraceComplete(Collection<? extends MutableSpan> trace) {
        boolean isHealthTrace = trace.stream().anyMatch(span ->
                SERVICE_NAME.contentEquals(span.getServiceName())
                        && HEALTH_RESOURCE.matcher(span.getResourceName()).matches());

        return isHealthTrace ? Collections.emptyList() : trace;
    }

    @Override
    public int priority() {
        return 200;
    }
}
