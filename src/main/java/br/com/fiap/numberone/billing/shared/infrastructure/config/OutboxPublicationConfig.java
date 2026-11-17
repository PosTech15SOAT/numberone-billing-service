package br.com.fiap.numberone.billing.shared.infrastructure.config;

import br.com.fiap.numberone.billing.budget.application.gateways.IntegrationEventPublicationGateway;
import br.com.fiap.numberone.billing.shared.infrastructure.outbox.HttpIntegrationEventPublicationGateway;
import br.com.fiap.numberone.billing.shared.infrastructure.outbox.IntegrationEventOutboxRepository;
import br.com.fiap.numberone.billing.shared.infrastructure.outbox.OutboxPublisher;
import br.com.fiap.numberone.billing.shared.infrastructure.outbox.SqsIntegrationEventPublicationGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
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

import java.net.URI;
import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "billing.outbox.enabled", havingValue = "true")
@EnableConfigurationProperties({OutboxPublisherProperties.class, SqsPublicationProperties.class})
@EnableScheduling
public class OutboxPublicationConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "billing.outbox.transport", havingValue = "sqs", matchIfMissing = true)
    DefaultCredentialsProvider sqsCredentialsProvider() {
        return DefaultCredentialsProvider.builder().build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "billing.outbox.transport", havingValue = "sqs", matchIfMissing = true)
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
    @ConditionalOnProperty(name = "billing.outbox.transport", havingValue = "sqs", matchIfMissing = true)
    IntegrationEventPublicationGateway integrationEventPublicationGateway(SqsClient client, SqsPublicationProperties properties) {
        if (properties.queueUrl() == null) {
            throw new IllegalArgumentException("SQS_QUEUE_URL é obrigatória para publicação direta no SQS.");
        }
        return new SqsIntegrationEventPublicationGateway(client, properties.queueUrl());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "billing.outbox.transport", havingValue = "http")
    HttpIntegrationEventPublicationGateway httpIntegrationEventPublicationGateway(
            @Value("${billing.outbox.bridge-url}") String bridgeUrl,
            @Value("${billing.outbox.bridge-token-file}") String tokenFile,
            SqsPublicationProperties properties) {
        return new HttpIntegrationEventPublicationGateway(URI.create(bridgeUrl), Path.of(tokenFile),
                properties.connectionTimeout(), properties.apiCallTimeout());
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
