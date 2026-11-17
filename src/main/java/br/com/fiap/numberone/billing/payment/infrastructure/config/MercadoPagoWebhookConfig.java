package br.com.fiap.numberone.billing.payment.infrastructure.config;

import br.com.fiap.numberone.billing.payment.application.gateways.ChargeGateway;
import br.com.fiap.numberone.billing.payment.application.gateways.OrderLookupGateway;
import br.com.fiap.numberone.billing.payment.application.usecases.ConfirmPaymentUseCase;
import br.com.fiap.numberone.billing.payment.application.usecases.ObserveOrderWebhookUseCase;
import br.com.fiap.numberone.billing.payment.infrastructure.webhook.MercadoPagoSignatureValidator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MercadoPagoWebhookProperties.class)
public class MercadoPagoWebhookConfig {
    @Bean
    MercadoPagoSignatureValidator mercadoPagoSignatureValidator(MercadoPagoWebhookProperties properties) {
        return new MercadoPagoSignatureValidator(properties.secret());
    }

    @Bean
    ConfirmPaymentUseCase confirmPaymentUseCase(ChargeGateway charges) {
        return new ConfirmPaymentUseCase(charges);
    }

    @Bean
    ObserveOrderWebhookUseCase observeOrderWebhookUseCase(ChargeGateway charges, OrderLookupGateway orders) {
        return new ObserveOrderWebhookUseCase(charges, orders);
    }
}
