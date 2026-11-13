package br.com.fiap.numberone.billing.payment.infrastructure.rest;

import br.com.fiap.numberone.billing.payment.application.exceptions.CheckoutCreationException;
import br.com.fiap.numberone.billing.payment.infrastructure.config.MercadoPagoProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static br.com.fiap.numberone.billing.payment.infrastructure.rest.SellerIdentityVerificationException.Reason;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class MercadoPagoSellerIdentityVerifierTest {
    private static final String TOKEN = "APP_USR-fictitious-only";
    private HttpServer server;
    private HttpClient http;
    private ExecutorService executor;
    private MercadoPagoSellerIdentityVerifier verifier;
    private final AtomicInteger requests = new AtomicInteger();
    private String body = "{\"id\":1234567890123,\"email\":\"private@example.test\",\"access_token\":\"private-response-token\"}";
    private int status = 200;
    private String contentType = "application/json";
    private long delay;
    private String receivedToken;
    private String receivedMethod;
    private String receivedQuery;

    @BeforeEach void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/users/me", exchange -> {
            requests.incrementAndGet();
            receivedToken = exchange.getRequestHeaders().getFirst("Authorization");
            receivedMethod = exchange.getRequestMethod();
            receivedQuery = exchange.getRequestURI().getQuery();
            try { if (delay > 0) Thread.sleep(delay); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            if (contentType != null) exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/must-not-follow");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            try { exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); }
            finally { exchange.close(); }
        });
        server.start();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(400));
        verifier = new MercadoPagoSellerIdentityVerifier(RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).requestFactory(factory).build(),
                new MercadoPagoProperties(true, "TEST", TOKEN, null, null, "1234567890123"));
    }
    @AfterEach void close() { server.stop(0); executor.shutdownNow(); http.close(); }

    @Test void shouldCheckCurrentIdentityOnEveryInvocationWithoutCache() {
        verifier.verifyExpectedSeller();
        verifier.verifyExpectedSeller();
        assertThat(requests).hasValue(2);
        assertThat(receivedMethod).isEqualTo("GET");
        assertThat(receivedToken).isEqualTo("Bearer " + TOKEN);
        assertThat(receivedQuery).isNull();
        body = "{\"id\":1234567890124}";
        assertReason(Reason.IDENTITY_MISMATCH);
        assertThat(requests).hasValue(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{}", "{\"id\":null}", "{\"id\":\"1234567890123\"}", "{\"id\":1.0}", "{\"id\":0}", "{\"id\":-1}",
            "{\"id\":9223372036854775808}", "{\"id\":1234567890123} {}", "{\"id\":1234567890123,\"id\":1234567890124}", "private-malformed-json"})
    void shouldRejectInvalidIdentityContract(String response) {
        body = response;
        assertReason(Reason.INVALID_RESPONSE);
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {201, 204, 301, 302, 307, 308, 400, 401, 403, 404, 408, 429, 500, 503})
    void shouldBlockAllNon200ResponsesWithoutFollowingRedirectsOrPreservingPrivateBody(int responseStatus) {
        status = responseStatus;
        body = TOKEN + " private-error-body";
        SellerIdentityVerificationException failure = catchThrowableOfType(SellerIdentityVerificationException.class, verifier::verifyExpectedSeller);
        assertThat(failure).isNotNull();
        assertThat(failure.getHttpStatus()).isEqualTo(responseStatus);
        assertThat(failure.isResultUnknown()).isFalse();
        assertThat(failure.getCause()).isInstanceOfSatisfying(RestClientResponseException.class,
                cause -> assertThat(cause.getResponseBodyAsByteArray()).isEmpty());
        assertThat(failure.getMessage()).doesNotContain(TOKEN, body);
        assertThat(requests).hasValue(1);
    }

    @Test void shouldBlockOversizedResponse() { body = " ".repeat(65537); assertReason(Reason.INVALID_RESPONSE); }
    @Test void shouldBlockWrongContentType() { contentType = "text/plain"; assertReason(Reason.INVALID_RESPONSE); }
    @Test void shouldBlockMissingContentType() { contentType = null; assertReason(Reason.INVALID_RESPONSE); }
    @Test void shouldNotRetryTimeout() { delay = 1000; assertReason(Reason.TIMEOUT); assertThat(requests).hasValue(1); }
    @Test void shouldPreserveCommunicationCauseWithoutFallback() { server.stop(0); assertReason(Reason.COMMUNICATION); }

    @Test void shouldRejectDatabaseTransactionBeforeAnyHttp() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertReason(Reason.ACTIVE_TRANSACTION); assertThat(requests).hasValue(0); }
        finally { TransactionSynchronizationManager.clear(); }
    }

    @Test void shouldBlockDisabledVerifierWithoutAnyRequest() {
        MercadoPagoSellerIdentityVerifier disabled = new MercadoPagoSellerIdentityVerifier(RestClient.create(), new MercadoPagoProperties(false, null, null, null, null, null));
        assertThatThrownBy(disabled::verifyExpectedSeller).isInstanceOfSatisfying(SellerIdentityVerificationException.class, failure -> assertThat(failure.getReason()).isEqualTo(Reason.DISABLED));
        assertThat(requests).hasValue(0);
    }

    @Test void shouldNotLogCredentialsIdentityPayloadOrPrivateErrorAtTrace(CapturedOutput output) {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.springframework.web.client");
        ch.qos.logback.classic.Level previous = logger.getLevel();
        try {
            logger.setLevel(ch.qos.logback.classic.Level.TRACE);
            verifier.verifyExpectedSeller();
            status = 401;
            assertReason(Reason.AUTHORIZATION);
            assertThat(output.getAll()).doesNotContain(TOKEN, "1234567890123", "private@example.test", "private-response-token");
        } finally { logger.setLevel(previous); }
    }

    private void assertReason(Reason expected) {
        assertThatThrownBy(verifier::verifyExpectedSeller).isInstanceOfSatisfying(SellerIdentityVerificationException.class, failure -> {
            assertThat(failure.getReason()).isEqualTo(expected);
            assertThat(failure.isResultUnknown()).isFalse();
        });
    }
}
