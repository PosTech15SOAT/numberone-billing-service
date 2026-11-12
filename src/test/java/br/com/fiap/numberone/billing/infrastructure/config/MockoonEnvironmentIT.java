package br.com.fiap.numberone.billing.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class MockoonEnvironmentIT {
    private static final String CUSTOMER_EMAIL = "configured-recipient@example.test";
    private static final Path DATA_FILE = Path.of("docker/mockoon/os-service.json").toAbsolutePath();

    @Container
    static final GenericContainer<?> MOCKOON = new GenericContainer<>("mockoon/cli:9.9.0")
            .withEnv("MOCKOON_CUSTOMER_EMAIL", CUSTOMER_EMAIL).withExposedPorts(3000)
            .withCopyFileToContainer(MountableFile.forHostPath(DATA_FILE), "/mockoon/os-service.json")
            .withCommand("--data", "/mockoon/os-service.json", "--port", "3000", "--hostname", "0.0.0.0", "--disable-admin-api")
            .waitingFor(Wait.forHttp("/internal/service-orders/00000000-0000-4000-8000-000000000101/quotation-context"));

    @Test
    void shouldRenderConfiguredEmailAndPreserveEveryQuotationScenario() throws Exception {
        var mapper = new JsonMapper();
        var route = mapper.readTree(Files.readString(DATA_FILE)).path("routes").get(0);
        try (var client = HttpClient.newHttpClient()) {
            for (var fixture : route.path("responses")) {
                String id = fixture.path("default").asBoolean() ? "00000000-0000-4000-8000-000000000199"
                        : fixture.path("rules").get(0).path("value").asText();
                var uri = URI.create("http://" + MOCKOON.getHost() + ":" + MOCKOON.getMappedPort(3000)
                        + "/internal/service-orders/" + id + "/quotation-context");
                var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as(id).isEqualTo(fixture.path("statusCode").asInt());
                String expected = fixture.path("body").asText().replace("{{getEnvVar 'MOCKOON_CUSTOMER_EMAIL'}}", CUSTOMER_EMAIL);
                if (expected.isEmpty()) {
                    assertThat(response.body()).isEmpty();
                } else {
                    var body = mapper.readTree(response.body());
                    assertThat(body).as(id).isEqualTo(mapper.readTree(expected));
                    if (id.endsWith("0103")) assertThat(body.path("customerEmail").isNull()).isTrue();
                    if (id.endsWith("0101")) assertThat(body.path("customerEmail").asText()).isEqualTo(CUSTOMER_EMAIL);
                }
            }
        }
    }
}
