package br.com.fiap.numberone.billing.payment.api;

import br.com.fiap.numberone.billing.infrastructure.observability.support.FailingLogAppender;
import br.com.fiap.numberone.billing.payment.api.exceptions.PaymentExceptionHandler;
import br.com.fiap.numberone.billing.payment.api.exceptions.PaymentRequestException;
import br.com.fiap.numberone.billing.payment.application.exceptions.CheckoutCreationException;
import br.com.fiap.numberone.billing.payment.application.exceptions.ServiceOrderPaymentUnavailableException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentExceptionHandlerLoggingTest {
    @ParameterizedTest
    @MethodSource("failures")
    void shouldPreserveResponseAndCauseWhenLoggingFails(RuntimeException failure, int status, String eventCode) {
        var request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("serviceOrderId", UUID.randomUUID().toString()));
        var wrapper = new PaymentRequestException(failure);
        var cause = failure.getCause();
        var handler = new PaymentExceptionHandler();
        var expected = handler.requestFailed(wrapper, request);
        try (var logging = new FailingLogAppender(PaymentExceptionHandler.class)) {
            var response = handler.requestFailed(wrapper, request);
            assertThat(response).isEqualTo(expected);
            assertThat(response.getStatusCode().value()).isEqualTo(status);
            assertThat(logging.eventCodes()).containsExactly(eventCode);
            assertThat(wrapper.getCause()).isSameAs(failure);
            assertThat(failure.getCause()).isSameAs(cause);
        }
    }

    private static Stream<Arguments> failures() {
        var cause = new IllegalStateException("Causa original");
        return Stream.of(
                Arguments.of(new ServiceOrderPaymentUnavailableException(ServiceOrderPaymentUnavailableException.Failure.TIMEOUT, cause), 504, "PAYMENT_SERVICE_ORDER_LOOKUP_FAILED"),
                Arguments.of(new IllegalStateException("Falha funcional", cause), 500, "PAYMENT_REQUEST_FAILED"),
                preparation(CheckoutCreationException.Kind.DISABLED, 503, cause),
                preparation(CheckoutCreationException.Kind.AUTHORIZATION, 503, cause),
                preparation(CheckoutCreationException.Kind.TIMEOUT, 504, cause),
                preparation(CheckoutCreationException.Kind.COMMUNICATION, 503, cause),
                preparation(CheckoutCreationException.Kind.RATE_LIMIT, 503, cause),
                preparation(CheckoutCreationException.Kind.PROVIDER_FAILURE, 503, cause),
                preparation(CheckoutCreationException.Kind.INVALID_RESPONSE, 502, cause));
    }

    private static Arguments preparation(CheckoutCreationException.Kind kind, int status, Throwable cause) {
        return Arguments.of(new CheckoutCreationException(kind, null, false, cause), status, "PAYMENT_CHECKOUT_PREPARATION_FAILED");
    }
}
