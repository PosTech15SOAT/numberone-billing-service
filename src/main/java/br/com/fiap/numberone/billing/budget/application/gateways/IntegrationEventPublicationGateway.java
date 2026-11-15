package br.com.fiap.numberone.billing.budget.application.gateways;

public interface IntegrationEventPublicationGateway {

    void publish(String envelope);
}
