package br.com.fiap.numberone.billing.budget.infrastructure.config;

import br.com.fiap.numberone.billing.budget.infrastructure.outbox.IntegrationEventOutboxRepository;
import br.com.fiap.numberone.billing.budget.infrastructure.outbox.OutboxPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxPublicationConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(OutboxPublicationConfig.class)
            .withBean(IntegrationEventOutboxRepository.class, () -> {
                var repository = mock(IntegrationEventOutboxRepository.class);
                when(repository.claimAvailable(anyInt(), anyInt(), any())).thenReturn(List.of());
                return repository;
            });

    @Test
    void shouldNotCreateClientOrSchedulerWhenDisabled() {
        runner.withPropertyValues("billing.outbox.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(SqsClient.class).doesNotHaveBean(OutboxPublisher.class)
                    .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
        });
    }

    @Test
    void shouldCreatePublisherAndSchedulingWhenExplicitlyEnabled() {
        configured().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SqsClient.class).hasSingleBean(OutboxPublisher.class);
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(1);
        });
    }

    @Test
    void shouldRejectALeaseTooShortForTheConfiguredBatchTimeout() {
        configured().withPropertyValues("billing.outbox.lease-duration=5s").run(context ->
                assertThat(context).hasFailed().getFailure().hasRootCauseMessage("A posse da Outbox deve superar o timeout total do lote SQS"));
    }

    private ApplicationContextRunner configured() {
        return runner.withPropertyValues("billing.outbox.enabled=true", "billing.outbox.batch-size=2",
                "billing.outbox.lease-duration=30s", "billing.outbox.poll-interval=10s", "billing.outbox.max-attempts=3",
                "billing.outbox.initial-backoff=1s", "billing.outbox.max-backoff=5s",
                "billing.outbox.sqs.region=us-east-1", "billing.outbox.sqs.queue-url=http://localhost:4566/000000000000/test",
                "billing.outbox.sqs.endpoint=", "billing.outbox.sqs.connection-timeout=1s", "billing.outbox.sqs.read-timeout=3s",
                "billing.outbox.sqs.api-call-timeout=5s");
    }
}
