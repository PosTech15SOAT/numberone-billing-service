package br.com.fiap.numberone.billing.budget.infrastructure.rest;

import br.com.fiap.numberone.billing.infrastructure.observability.IntegrationObservation;
import br.com.fiap.numberone.billing.budget.application.exceptions.ExternalServiceException;
import br.com.fiap.numberone.billing.budget.application.exceptions.ServiceOrderNotFoundException;
import br.com.fiap.numberone.billing.budget.application.gateways.ServiceOrderGateway;
import br.com.fiap.numberone.billing.budget.domain.exceptions.IneligibleServiceOrderException;
import br.com.fiap.numberone.billing.budget.domain.references.ServiceOrderQuotationContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.UUID;

public class ServiceOrderRestGateway implements ServiceOrderGateway {

    private final RestClient restClient;

    public ServiceOrderRestGateway(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public ServiceOrderQuotationContext findByServiceOrderId(UUID serviceOrderId) {
        var observation = new IntegrationObservation("OS_SERVICE", "QUOTATION_CONTEXT_GET");
        observation.identifier("service_order.id", serviceOrderId);
        try {
            ContextResponse response = fetchContext(serviceOrderId, observation);
            return toContext(serviceOrderId, response);
        } catch (RuntimeException exception) {
            RuntimeException failure = translateFailure(exception, observation);
            observation.failed(failure, failure instanceof ExternalServiceException known ? known.getFailure().name() : "PROCESSING_FAILED");
            throw failure;
        } finally {
            observation.close();
        }
    }

    private ContextResponse fetchContext(UUID serviceOrderId, IntegrationObservation observation) {
        var entity = restClient.get()
                .uri("/internal/service-orders/{serviceOrderId}/quotation-context", serviceOrderId)
                .retrieve().toEntity(ContextResponse.class);
        observation.status(entity.getStatusCode().value());
        return entity.getBody();
    }

    private RuntimeException translateFailure(RuntimeException failure, IntegrationObservation observation) {
        return switch (failure) {
            case RestClientResponseException response -> {
                observation.status(response.getStatusCode().value());
                yield responseFailure(response);
            }
            case ResourceAccessException communication -> communicationFailure(communication);
            case RestClientException invalid -> invalidResponse(invalid);
            default -> failure;
        };
    }

    private ServiceOrderQuotationContext toContext(UUID serviceOrderId, ContextResponse response) {
        if (response == null || !serviceOrderId.equals(response.serviceOrderId())
                || response.status() == null || response.status().isBlank() || response.items() == null) {
            throw invalidResponse(null);
        }
        var items = response.items().stream().map(this::toItem).toList();
        return new ServiceOrderQuotationContext(response.serviceOrderId(), response.status(), items,
                response.customerEmail(), response.compositionVersion());
    }

    private ServiceOrderQuotationContext.Item toItem(ItemResponse item) {
        if (item == null || item.serviceOrderItemId() == null || item.automotiveServiceId() == null
                || item.supplies() == null) {
            throw invalidResponse(null);
        }
        var supplies = item.supplies().stream().map(this::toSupply).toList();
        return new ServiceOrderQuotationContext.Item(item.serviceOrderItemId(), item.automotiveServiceId(),
                item.serviceAmount(), item.status(), supplies);
    }

    private ServiceOrderQuotationContext.Supply toSupply(SupplyResponse supply) {
        if (supply == null || supply.inventoryItemId() == null) {
            throw invalidResponse(null);
        }
        return new ServiceOrderQuotationContext.Supply(supply.inventoryItemId(), supply.quantity());
    }

    private RuntimeException responseFailure(RestClientResponseException cause) {
        if (cause.getStatusCode().value() == 404) {
            return new ServiceOrderNotFoundException();
        }
        if (cause.getStatusCode().value() == 409) {
            return new IneligibleServiceOrderException("A ordem de serviço não permite criar orçamento");
        }
        return cause.getStatusCode().is5xxServerError() ? unavailable(cause) : invalidResponse(cause);
    }

    private ExternalServiceException communicationFailure(ResourceAccessException cause) {
        Throwable root = cause.getMostSpecificCause();
        if (root instanceof SocketTimeoutException || root instanceof HttpTimeoutException) {
            return new ExternalServiceException("Tempo limite ao consultar o serviço OS",
                    ExternalServiceException.Failure.TIMEOUT, cause);
        }
        return unavailable(cause);
    }

    private ExternalServiceException invalidResponse(Throwable cause) {
        return new ExternalServiceException("O serviço OS retornou uma resposta inválida",
                ExternalServiceException.Failure.INVALID_RESPONSE, cause);
    }

    private ExternalServiceException unavailable(Throwable cause) {
        return new ExternalServiceException("O serviço OS está indisponível",
                ExternalServiceException.Failure.UNAVAILABLE, cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContextResponse(UUID serviceOrderId, String status, List<ItemResponse> items,
                                  String customerEmail, Integer compositionVersion) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ItemResponse(UUID serviceOrderItemId, UUID automotiveServiceId, BigDecimal serviceAmount,
                               String status, List<SupplyResponse> supplies) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SupplyResponse(UUID inventoryItemId, Integer quantity) {
    }
}
