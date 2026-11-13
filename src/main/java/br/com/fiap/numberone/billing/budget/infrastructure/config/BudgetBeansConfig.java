package br.com.fiap.numberone.billing.budget.infrastructure.config;

import br.com.fiap.numberone.billing.budget.application.gateways.BudgetGateway;
import br.com.fiap.numberone.billing.budget.application.gateways.BillingSagaStore;
import br.com.fiap.numberone.billing.budget.application.usecases.CloseBillingContext;
import br.com.fiap.numberone.billing.payment.application.gateways.ServiceOrderPaymentGateway;
import br.com.fiap.numberone.billing.budget.application.gateways.BudgetNotificationGateway;
import br.com.fiap.numberone.billing.budget.application.gateways.EmployeeIdentityGateway;
import br.com.fiap.numberone.billing.budget.application.gateways.InventoryGateway;
import br.com.fiap.numberone.billing.budget.application.gateways.ServiceOrderGateway;
import br.com.fiap.numberone.billing.budget.application.usecases.ApproveBudgetRemotelyUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.ApproveBudgetUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.CreateBudgetUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.GetBudgetApprovalPageUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.GetBudgetHistoryUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.GetBudgetUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.RegisterBudgetApprovalUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.RejectBudgetRemotelyUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.RejectBudgetUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.ResendBudgetApprovalUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.SendBudgetForApprovalUseCase;
import br.com.fiap.numberone.billing.budget.infrastructure.rest.InventoryRestGateway;
import br.com.fiap.numberone.billing.budget.infrastructure.rest.ServiceOrderRestGateway;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import br.com.fiap.numberone.billing.infrastructure.rest.ServiceRequestHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({BudgetIntegrationProperties.class, EmployeeIdentityProperties.class, BudgetNotificationProperties.class})
public class BudgetBeansConfig {

    @Bean
    CloseBillingContext closeBillingContext(BillingSagaStore store, ServiceOrderPaymentGateway orders) {
        return new CloseBillingContext(store, orders);
    }

    @Bean
    ServiceOrderGateway serviceOrderGateway(BudgetIntegrationProperties properties,
                                            JsonMapper jsonMapper, Environment environment) {
        return new ServiceOrderRestGateway(client(properties.osBaseUrl(), properties, jsonMapper,
                environment.getProperty("billing.integrations.os-token", "")));
    }

    @Bean
    InventoryGateway inventoryGateway(BudgetIntegrationProperties properties, JsonMapper jsonMapper,
                                      Environment environment) {
        return new InventoryRestGateway(client(properties.inventoryBaseUrl(), properties, jsonMapper,
                environment.getProperty("billing.integrations.inventory-token", "")));
    }

    @Bean
    CreateBudgetUseCase createBudgetUseCase(ServiceOrderGateway serviceOrderGateway,
                                            InventoryGateway inventoryGateway, BudgetGateway budgetGateway) {
        return new CreateBudgetUseCase(serviceOrderGateway, inventoryGateway, budgetGateway);
    }

    @Bean
    SendBudgetForApprovalUseCase sendBudgetForApprovalUseCase(BudgetGateway budgetGateway,
                                                              ServiceOrderGateway serviceOrderGateway) {
        return new SendBudgetForApprovalUseCase(budgetGateway, serviceOrderGateway);
    }

    @Bean
    ApproveBudgetUseCase approveBudgetUseCase(BudgetGateway budgetGateway) {
        return new ApproveBudgetUseCase(budgetGateway);
    }

    @Bean
    RegisterBudgetApprovalUseCase registerBudgetApprovalUseCase(BudgetGateway budgetGateway,
                                                                EmployeeIdentityGateway employeeIdentityGateway,
                                                                ServiceOrderGateway serviceOrderGateway) {
        return new RegisterBudgetApprovalUseCase(budgetGateway, employeeIdentityGateway, serviceOrderGateway);
    }

    @Bean
    RejectBudgetUseCase rejectBudgetUseCase(BudgetGateway budgetGateway) {
        return new RejectBudgetUseCase(budgetGateway);
    }

    @Bean
    GetBudgetUseCase getBudgetUseCase(BudgetGateway budgetGateway, BudgetNotificationGateway notificationGateway) {
        return new GetBudgetUseCase(budgetGateway, notificationGateway);
    }

    @Bean
    ResendBudgetApprovalUseCase resendBudgetApprovalUseCase(BudgetGateway budgetGateway,
                                                            ServiceOrderGateway serviceOrderGateway) {
        return new ResendBudgetApprovalUseCase(budgetGateway, serviceOrderGateway);
    }

    @Bean
    GetBudgetHistoryUseCase getBudgetHistoryUseCase(BudgetGateway budgetGateway) {
        return new GetBudgetHistoryUseCase(budgetGateway);
    }

    @Bean
    GetBudgetApprovalPageUseCase getBudgetApprovalPageUseCase(BudgetGateway gateway) {
        return new GetBudgetApprovalPageUseCase(gateway);
    }

    @Bean
    ApproveBudgetRemotelyUseCase approveBudgetRemotelyUseCase(BudgetGateway gateway) {
        return new ApproveBudgetRemotelyUseCase(gateway);
    }

    @Bean
    RejectBudgetRemotelyUseCase rejectBudgetRemotelyUseCase(BudgetGateway gateway) {
        return new RejectBudgetRemotelyUseCase(gateway);
    }

    private RestClient client(URI baseUrl, BudgetIntegrationProperties properties, JsonMapper jsonMapper, String token) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory)
                .requestInterceptor(new ServiceRequestHeaders(token))
                .messageConverters(converters -> {
                    converters.removeIf(converter -> converter instanceof JacksonJsonHttpMessageConverter);
                    converters.add(new JacksonJsonHttpMessageConverter(jsonMapper));
                }).build();
    }
}
