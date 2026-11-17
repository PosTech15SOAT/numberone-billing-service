package br.com.fiap.numberone.billing.shared.support.e2e;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Simula somente OS e Inventory em loopback; os adaptadores HTTP do Billing continuam reais. */
public final class BillingExternalServices implements AutoCloseable {
    public static final String ORDER_ID = "10000000-0000-0000-0000-000000000001";
    public static final String MATERIAL_ID = "20000000-0000-0000-0000-000000000001";
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpServer server;
    private volatile boolean completed;

    public BillingExternalServices() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = null;
            if (exchange.getRequestMethod().equals("GET") && path.equals("/internal/service-orders/" + ORDER_ID + "/quotation-context")) {
                body = """
                        {"serviceOrderId":"%s","status":"IN_DIAGNOSIS","compositionVersion":1,
                         "customerEmail":"customer@example.test","items":[
                           {"serviceOrderItemId":"30000000-0000-0000-0000-000000000001",
                            "automotiveServiceId":"40000000-0000-0000-0000-000000000001",
                            "serviceAmount":150.00,"status":"PENDING",
                            "supplies":[{"inventoryItemId":"%s","quantity":2}]}]}
                        """.formatted(ORDER_ID, MATERIAL_ID);
            } else if (exchange.getRequestMethod().equals("POST") && path.equals("/internal/inventory/consultar-precos")) {
                body = """
                        {"items":[{"inventoryItemId":"%s","salePrice":25.00,"currency":"BRL"}]}
                        """.formatted(MATERIAL_ID);
            } else if (exchange.getRequestMethod().equals("GET") && path.equals("/internal/service-orders/" + ORDER_ID)) {
                body = """
                        {"serviceOrderId":"%s","status":"%s","cancellationInProgress":false}
                        """.formatted(ORDER_ID, completed ? "COMPLETED" : "IN_DIAGNOSIS");
            }
            byte[] bytes = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
    }

    public String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    public void completeOrder() { completed = true; }
    public void reset() { completed = false; }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }
}
