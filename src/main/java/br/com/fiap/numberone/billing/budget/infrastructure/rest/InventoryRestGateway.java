package br.com.fiap.numberone.billing.budget.infrastructure.rest;

import br.com.fiap.numberone.billing.infrastructure.observability.IntegrationObservation;
import br.com.fiap.numberone.billing.budget.application.exceptions.ExternalServiceException;
import br.com.fiap.numberone.billing.budget.application.gateways.InventoryGateway;
import br.com.fiap.numberone.billing.budget.domain.exceptions.BudgetCalculationException;
import br.com.fiap.numberone.billing.budget.domain.references.InventoryItemPrice;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class InventoryRestGateway implements InventoryGateway {

    private final RestClient restClient;

    public InventoryRestGateway(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public List<InventoryItemPrice> findByInventoryItemIds(Set<UUID> inventoryItemIds) {
        var observation = new IntegrationObservation("INVENTORY_SERVICE", "PRICES_GET");
        try {
            PricesResponse response = fetchPrices(inventoryItemIds, observation);
            return toPrices(response);
        } catch (RuntimeException exception) {
            RuntimeException failure = translateFailure(exception, observation);
            observation.failed(failure, failure instanceof ExternalServiceException known ? known.getFailure().name() : "PROCESSING_FAILED");
            throw failure;
        } finally {
            observation.close();
        }
    }

    private PricesResponse fetchPrices(Set<UUID> inventoryItemIds, IntegrationObservation observation) {
        // TODO: Confirmar CT-04 com Inventory: POST e inventoryItemIds são a proposta provisória aprovada.
        var entity = restClient.post().uri("/internal/inventory/consultar-precos")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PricesRequest(inventoryItemIds.stream().sorted().toList()))
                .retrieve().toEntity(PricesResponse.class);
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

    private List<InventoryItemPrice> toPrices(PricesResponse response) {
        if (response == null || response.items() == null) {
            throw invalidResponse(null);
        }
        return response.items().stream().map(this::toPrice).toList();
    }

    private InventoryItemPrice toPrice(PriceResponse item) {
        if (item == null || item.inventoryItemId() == null) {
            throw invalidResponse(null);
        }
        return new InventoryItemPrice(item.inventoryItemId(), item.salePrice(), item.currency());
    }

    private RuntimeException responseFailure(RestClientResponseException cause) {
        if (cause.getStatusCode().value() == 404) {
            return new BudgetCalculationException("Não foi possível obter os preços necessários das peças");
        }
        return cause.getStatusCode().is5xxServerError() ? unavailable(cause) : invalidResponse(cause);
    }

    private ExternalServiceException communicationFailure(ResourceAccessException cause) {
        Throwable root = cause.getMostSpecificCause();
        if (root instanceof SocketTimeoutException || root instanceof HttpTimeoutException) {
            return new ExternalServiceException("Tempo limite ao consultar o serviço Inventory",
                    ExternalServiceException.Failure.TIMEOUT, cause);
        }
        return unavailable(cause);
    }

    private ExternalServiceException invalidResponse(Throwable cause) {
        return new ExternalServiceException("O serviço Inventory retornou uma resposta inválida",
                ExternalServiceException.Failure.INVALID_RESPONSE, cause);
    }

    private ExternalServiceException unavailable(Throwable cause) {
        return new ExternalServiceException("O serviço Inventory está indisponível",
                ExternalServiceException.Failure.UNAVAILABLE, cause);
    }

    public record PricesRequest(List<UUID> inventoryItemIds) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PricesResponse(List<PriceResponse> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PriceResponse(UUID inventoryItemId, BigDecimal salePrice, String currency) {
    }
}
