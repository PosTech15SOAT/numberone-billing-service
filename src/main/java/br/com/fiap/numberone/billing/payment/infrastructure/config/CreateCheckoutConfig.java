package br.com.fiap.numberone.billing.payment.infrastructure.config;

import br.com.fiap.numberone.billing.payment.application.exceptions.ServiceOrderPaymentUnavailableException;
import br.com.fiap.numberone.billing.payment.application.gateways.ChargeGateway;
import br.com.fiap.numberone.billing.payment.application.gateways.CheckoutGateway;
import br.com.fiap.numberone.billing.payment.application.gateways.ServiceOrderPaymentGateway;
import br.com.fiap.numberone.billing.payment.application.usecases.CreateCheckoutUseCase;
import br.com.fiap.numberone.billing.payment.application.usecases.GetChargeCheckoutUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import br.com.fiap.numberone.billing.payment.infrastructure.rest.ServiceOrderPaymentRestGateway;
import br.com.fiap.numberone.billing.payment.infrastructure.rest.ServiceOrderPaymentMapper;
import br.com.fiap.numberone.billing.infrastructure.rest.ServiceRequestHeaders;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ServiceOrderPaymentProperties.class)
public class CreateCheckoutConfig {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "billing.payment.service-order.local-unauthenticated-enabled", havingValue = "true")
    HttpClient paymentServiceOrderHttpClient(ServiceOrderPaymentProperties properties, Environment environment) {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length != 1 || !profiles[0].equals("local")) {
            throw new IllegalArgumentException("CT-02A sem autenticação exige exclusivamente o profile local");
        }
        return HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Bean
    @ConditionalOnMissingBean(ServiceOrderPaymentGateway.class)
    ServiceOrderPaymentGateway serviceOrderPaymentGateway(ServiceOrderPaymentProperties properties,
            @Qualifier("paymentServiceOrderHttpClient") ObjectProvider<HttpClient> clients, Environment environment) {
        String token = environment.getProperty("billing.integrations.os-token", "");
        if (!token.isBlank()) {
            HttpClient http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
            JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
            requests.setReadTimeout(properties.readTimeout());
            return new ServiceOrderPaymentRestGateway(RestClient.builder()
                    .baseUrl(environment.getRequiredProperty("billing.integrations.os-base-url"))
                    .requestFactory(requests).requestInterceptor(new ServiceRequestHeaders(token)).build(),
                    new ServiceOrderPaymentMapper(true));
        }
        if (!properties.localUnauthenticatedEnabled()) {
            return serviceOrderId -> { throw new ServiceOrderPaymentUnavailableException(); };
        }
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(clients.getObject());
        requests.setReadTimeout(properties.readTimeout());
        return new ServiceOrderPaymentRestGateway(RestClient.builder().baseUrl(properties.baseUrl())
                .requestFactory(requests).build(), new ServiceOrderPaymentMapper());
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
