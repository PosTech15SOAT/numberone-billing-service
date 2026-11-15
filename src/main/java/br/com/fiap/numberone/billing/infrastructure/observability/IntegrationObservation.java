package br.com.fiap.numberone.billing.infrastructure.observability;

import br.com.fiap.numberone.billing.budget.infrastructure.logging.SafeExceptionDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientResponseException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Uma operação lógica inclui HTTP e validação da resposta, sem ler payloads para logging. */
public final class IntegrationObservation implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(IntegrationObservation.class);
    private final String integration;
    private final String operation;
    private final long started = System.nanoTime();
    private Integer status;
    private Throwable failure;
    private String category;
    private final java.util.Map<String, Object> identifiers = new java.util.LinkedHashMap<>();

    public IntegrationObservation(String integration, String operation) {
        this.integration = integration;
        this.operation = operation;
        ObservationLog.safely(() -> LOGGER.atInfo().addKeyValue("event.code", "INTEGRATION_STARTED")
                .addKeyValue("integration.name", integration).addKeyValue("operation.name", operation)
                .log("Operação externa iniciada"));
    }

    public void identifier(String name, Object value) {
        if (value != null) identifiers.put(name, value);
    }

    public void status(int status) { this.status = status; }
    public void failed(Throwable failure, String category) { this.failure = failure; this.category = category; }

    @Override public void close() {
        ObservationLog.safely(() -> {
            String error = failure == null ? null : classification();
            var log = failure == null ? LOGGER.atInfo() : "PROCESSING_FAILED".equals(error) ? LOGGER.atError() : LOGGER.atWarn();
            log.addKeyValue("event.code", failure == null ? "INTEGRATION_COMPLETED" : "INTEGRATION_FAILED")
                    .addKeyValue("integration.name", integration).addKeyValue("operation.name", operation)
                    .addKeyValue("event.duration", System.nanoTime() - started)
                    .addKeyValue("event.outcome", failure == null ? "success" : "failure");
            identifiers.forEach(log::addKeyValue);
            if (status != null) log.addKeyValue("http.response.status_code", status);
            if (failure != null) log.addKeyValue("error.code", error).addKeyValue("error.reason", category)
                    .addKeyValue("error.type", failure.getClass().getName())
                    .addKeyValue("error.stack_trace", SafeExceptionDetails.stackTrace(failure));
            log.log("Operação externa concluída");
        });
    }

    private String classification() {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) return "TIMEOUT";
            if (cause instanceof ConnectException) return "CONNECTION_FAILED";
            if (cause instanceof RestClientResponseException response) {
                if (status == null) status = response.getStatusCode().value();
                return "HTTP_ERROR";
            }
        }
        if (status != null && status >= 400) return "HTTP_ERROR";
        return category;
    }
}
