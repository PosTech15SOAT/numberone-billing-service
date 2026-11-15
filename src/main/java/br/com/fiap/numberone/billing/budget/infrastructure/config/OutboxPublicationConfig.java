package br.com.fiap.numberone.billing.budget.infrastructure.config;

import br.com.fiap.numberone.billing.budget.application.gateways.IntegrationEventPublicationGateway;
import br.com.fiap.numberone.billing.budget.infrastructure.outbox.IntegrationEventOutboxRepository;
import br.com.fiap.numberone.billing.budget.infrastructure.outbox.OutboxPublisher;
import br.com.fiap.numberone.billing.budget.infrastructure.outbox.SqsIntegrationEventPublicationGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "billing.outbox.enabled", havingValue = "true")
@EnableConfigurationProperties({OutboxPublisherProperties.class, SqsPublicationProperties.class})
@EnableScheduling
public class OutboxPublicationConfig {

    @Bean(destroyMethod = "close")
    DefaultCredentialsProvider sqsCredentialsProvider() {
        return DefaultCredentialsProvider.builder().build();
    }

    @Bean(destroyMethod = "close")
    public SqsClient sqsClient(SqsPublicationProperties properties, AwsCredentialsProvider credentialsProvider) {
        var builder = SqsClient.builder().region(Region.of(properties.region())).credentialsProvider(credentialsProvider)
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(properties.connectionTimeout()).socketTimeout(properties.readTimeout()))
                .overrideConfiguration(configuration -> configuration.apiCallTimeout(properties.apiCallTimeout())
                        .apiCallAttemptTimeout(properties.apiCallTimeout())
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()));
        if (properties.endpoint() != null) builder.endpointOverride(properties.endpoint());
        return builder.build();
    }

    @Bean
    IntegrationEventPublicationGateway integrationEventPublicationGateway(SqsClient client, SqsPublicationProperties properties) {
        return new SqsIntegrationEventPublicationGateway(client, properties.queueUrl());
    }

    @Bean
    OutboxPublisher outboxPublisher(IntegrationEventOutboxRepository repository, IntegrationEventPublicationGateway gateway,
                                    OutboxPublisherProperties properties, SqsPublicationProperties sqsProperties) {
        if (properties.leaseDuration().compareTo(sqsProperties.apiCallTimeout().multipliedBy(properties.batchSize())) <= 0) {
            throw new IllegalArgumentException("A posse da Outbox deve superar o timeout total do lote SQS");
        }
        return new OutboxPublisher(repository, gateway, properties);
    }
}
