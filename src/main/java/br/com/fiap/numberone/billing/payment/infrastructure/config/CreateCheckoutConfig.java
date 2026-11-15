package br.com.fiap.numberone.billing.payment.infrastructure.config;

import br.com.fiap.numberone.billing.payment.application.gateways.ChargeGateway;
import br.com.fiap.numberone.billing.payment.application.gateways.CheckoutGateway;
import br.com.fiap.numberone.billing.payment.application.gateways.ServiceOrderPaymentGateway;
import br.com.fiap.numberone.billing.payment.application.usecases.CreateCheckoutUseCase;
import br.com.fiap.numberone.billing.payment.application.usecases.GetChargeCheckoutUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import br.com.fiap.numberone.billing.budget.infrastructure.config.BudgetIntegrationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import br.com.fiap.numberone.billing.payment.infrastructure.rest.ServiceOrderPaymentRestGateway;
import br.com.fiap.numberone.billing.payment.infrastructure.rest.ServiceOrderPaymentMapper;
import br.com.fiap.numberone.billing.infrastructure.rest.ServiceRequestHeaders;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ServiceOrderPaymentProperties.class, BudgetIntegrationProperties.class})
public class CreateCheckoutConfig {
    @Bean(destroyMethod = "close")
    HttpClient paymentServiceOrderHttpClient(ServiceOrderPaymentProperties properties) {
        return HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Bean
    @ConditionalOnMissingBean(ServiceOrderPaymentGateway.class)
    ServiceOrderPaymentGateway serviceOrderPaymentGateway(ServiceOrderPaymentProperties properties,
            @Qualifier("paymentServiceOrderHttpClient") HttpClient paymentServiceOrderHttpClient, BudgetIntegrationProperties integrations) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(paymentServiceOrderHttpClient);
        requests.setReadTimeout(properties.readTimeout());
        return new ServiceOrderPaymentRestGateway(RestClient.builder().baseUrl(integrations.osBaseUrl())
                .requestFactory(requests).requestInterceptor(new ServiceRequestHeaders()).build(), new ServiceOrderPaymentMapper());
    }

    @Bean
    CreateCheckoutUseCase createCheckoutUseCase(ServiceOrderPaymentGateway serviceOrders, ChargeGateway charges,
                                               CheckoutGateway checkout) {
        return new CreateCheckoutUseCase(serviceOrders, charges, checkout);
    }

    @Bean
    GetChargeCheckoutUseCase getChargeCheckoutUseCase(ChargeGateway charges) {
        return new GetChargeCheckoutUseCase(charges);
    }
}
