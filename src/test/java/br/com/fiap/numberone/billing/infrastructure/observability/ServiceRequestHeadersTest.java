package br.com.fiap.numberone.billing.infrastructure.observability;

import br.com.fiap.numberone.billing.infrastructure.rest.ServiceRequestHeaders;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.http.HttpStatus;
import java.net.URI;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class ServiceRequestHeadersTest {
    @Test void shouldPropagateCorrelationWithoutAuthorization() throws Exception {
        String correlation = UUID.randomUUID().toString();
        MDC.put(HttpObservationFilter.CORRELATION, correlation);
        try {
            var request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://service.test"));
            new ServiceRequestHeaders().intercept(request, new byte[0], (sent, body) -> {
                assertThat(sent.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(correlation);
                assertThat(sent.getHeaders().getFirst("Authorization")).isNull();
                return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
            });
        } finally {
            MDC.remove(HttpObservationFilter.CORRELATION);
        }
    }
}
