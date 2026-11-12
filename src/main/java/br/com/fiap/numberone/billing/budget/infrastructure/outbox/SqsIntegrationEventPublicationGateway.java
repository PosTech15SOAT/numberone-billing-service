package br.com.fiap.numberone.billing.budget.infrastructure.outbox;

import br.com.fiap.numberone.billing.budget.application.exceptions.IntegrationEventPublicationException;
import br.com.fiap.numberone.billing.budget.application.exceptions.IntegrationEventPublicationException.Failure;
import br.com.fiap.numberone.billing.budget.application.gateways.IntegrationEventPublicationGateway;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.net.URI;

public class SqsIntegrationEventPublicationGateway implements IntegrationEventPublicationGateway {

    private final SqsClient client;
    private final String queueUrl;

    public SqsIntegrationEventPublicationGateway(SqsClient client, URI queueUrl) {
        this.client = client;
        this.queueUrl = queueUrl.toString();
    }

    @Override
    public void publish(String envelope) {
        try {
            var response = client.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl).messageBody(envelope).build());
            if (response.messageId() == null || response.messageId().isBlank()) {
                throw new IntegrationEventPublicationException(Failure.UNAVAILABLE, 200);
            }
        } catch (ApiCallTimeoutException | ApiCallAttemptTimeoutException exception) {
            throw new IntegrationEventPublicationException(Failure.TIMEOUT, 0, exception);
        } catch (SqsException exception) {
            boolean transientFailure = exception.statusCode() >= 500 || exception.statusCode() == 429
                    || exception.isThrottlingException();
            throw new IntegrationEventPublicationException(transientFailure ? Failure.UNAVAILABLE : Failure.REJECTED,
                    exception.statusCode(), exception);
        } catch (SdkClientException exception) {
            Throwable cause = exception;
            while (cause != null) {
                if (cause instanceof java.net.SocketTimeoutException) {
                    throw new IntegrationEventPublicationException(Failure.TIMEOUT, 0, exception);
                }
                if (cause == cause.getCause()) break;
                cause = cause.getCause();
            }
            throw new IntegrationEventPublicationException(Failure.UNAVAILABLE, 0, exception);
        }
    }
}
