package br.com.fiap.numberone.billing.payment.infrastructure.config;

import br.com.fiap.numberone.billing.payment.application.exceptions.CheckoutCreationException;
import br.com.fiap.numberone.billing.payment.application.exceptions.OrderLookupException;
import br.com.fiap.numberone.billing.payment.application.gateways.CheckoutGateway;
import br.com.fiap.numberone.billing.payment.application.gateways.OrderLookupGateway;
import br.com.fiap.numberone.billing.payment.infrastructure.rest.MercadoPagoCheckoutGateway;
import br.com.fiap.numberone.billing.payment.infrastructure.rest.MercadoPagoOrderMapper;
import br.com.fiap.numberone.billing.payment.infrastructure.rest.MercadoPagoSellerIdentityVerifier;
import br.com.fiap.numberone.billing.payment.infrastructure.rest.MercadoPagoOrderLookupGateway;
import br.com.fiap.numberone.billing.payment.infrastructure.rest.MercadoPagoOrderDetailsMapper;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MercadoPagoProperties.class)
public class MercadoPagoCheckoutConfig {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "billing.payment.mercado-pago.enabled", havingValue = "true")
    HttpClient mercadoPagoHttpClient(MercadoPagoProperties properties, ConfigurableEnvironment environment) {
        MercadoPagoTestEnvironment.validate(environment);
        PropertySource<?> variables = environment.getPropertySources().get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        if (variables == null || !properties.accessToken().equals(variables.getProperty("MERCADO_PAGO_TEST_ACCESS_TOKEN"))) {
            throw new IllegalArgumentException("Credencial Mercado Pago deve ser fornecida pela variável de ambiente dedicada");
        }
        return HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Bean
    @ConditionalOnProperty(name = "billing.payment.mercado-pago.enabled", havingValue = "true")
    RestClient mercadoPagoOrdersClient(MercadoPagoProperties properties,
                                      @Qualifier("mercadoPagoHttpClient") HttpClient httpClient) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().baseUrl("https://api.mercadopago.com").requestFactory(requestFactory).build();
    }

    @Bean
    @ConditionalOnProperty(name = "billing.payment.mercado-pago.enabled", havingValue = "true")
    MercadoPagoSellerIdentityVerifier mercadoPagoSellerIdentityVerifier(MercadoPagoProperties properties,
                                      @Qualifier("mercadoPagoHttpClient") HttpClient httpClient, ConfigurableEnvironment environment) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        RestClient identityClient = RestClient.builder().baseUrl("https://api.mercadolibre.com")
                .requestFactory(requestFactory).build();
        return new MercadoPagoSellerIdentityVerifier(identityClient, properties, environment.matchesProfiles("kind"));
    }

    @Bean
    CheckoutGateway checkoutGateway(MercadoPagoProperties properties,
                         @Qualifier("mercadoPagoOrdersClient") ObjectProvider<RestClient> clients,
                         ObjectProvider<MercadoPagoSellerIdentityVerifier> identities) {
        if (!properties.enabled()) {
            return () -> {
                throw new CheckoutCreationException(CheckoutCreationException.Kind.DISABLED, null, false, null);
            };
        }
        return new MercadoPagoCheckoutGateway(clients.getObject(), new MercadoPagoOrderMapper(), properties, identities.getObject());
    }

    @Bean
    OrderLookupGateway orderLookupGateway(MercadoPagoProperties properties,
                         @Qualifier("mercadoPagoOrdersClient") ObjectProvider<RestClient> clients,
                         ObjectProvider<MercadoPagoSellerIdentityVerifier> identities) {
        if (!properties.enabled()) {
            return providerOrderId -> { throw new OrderLookupException(OrderLookupException.Kind.BLOCKED, null, null); };
        }
        return new MercadoPagoOrderLookupGateway(clients.getObject(), properties, new MercadoPagoOrderDetailsMapper(), identities.getObject());
    }
}
