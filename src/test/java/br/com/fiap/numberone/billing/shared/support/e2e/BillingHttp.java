package br.com.fiap.numberone.billing.shared.support.e2e;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public final class BillingHttp {
    private BillingHttp() { }

    public static HttpResponse<String> send(int port, String method, String path, String body,
                                            Map<String, String> headers) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15))
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
