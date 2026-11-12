package br.com.fiap.numberone.billing.budget.infrastructure.notifications;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Servidor restrito a loopback, sem encaminhamento ou conexão com servidores reais. */
final class SimulatedSmtpServer implements AutoCloseable {
    private final ServerSocket server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<String> messages = new CopyOnWriteArrayList<>();
    volatile boolean reject;
    volatile CountDownLatch receivedData;
    volatile CountDownLatch releaseAcceptance;

    SimulatedSmtpServer() throws IOException {
        server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        executor.submit(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    executor.submit(() -> handle(socket));
                } catch (IOException exception) {
                    if (!server.isClosed()) throw new UncheckedIOException(exception);
                }
            }
        });
    }

    int port() { return server.getLocalPort(); }
    List<String> messages() { return List.copyOf(messages); }

    private void handle(Socket socket) {
        try (socket; var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII))) {
            socket.setSoTimeout(10000);
            reply(writer, "220 smtp.test ESMTP");
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("EHLO") || line.startsWith("HELO")) reply(writer, "250 smtp.test");
                else if (line.startsWith("MAIL") || line.startsWith("RCPT") || line.startsWith("RSET")) reply(writer, "250 OK");
                else if (line.equals("DATA")) {
                    if (reject) { reply(writer, "451 Try again later"); continue; }
                    reply(writer, "354 End with dot");
                    var data = new StringBuilder();
                    while ((line = reader.readLine()) != null && !line.equals(".")) data.append(line).append("\r\n");
                    if (receivedData != null) receivedData.countDown();
                    if (releaseAcceptance != null && !releaseAcceptance.await(10, TimeUnit.SECONDS)) throw new IOException("Test timeout");
                    var mime = new MimeMessage(Session.getInstance(new Properties()),
                            new ByteArrayInputStream(data.toString().getBytes(StandardCharsets.UTF_8)));
                    messages.add(mime.getContent().toString());
                    reply(writer, "250 Accepted");
                } else if (line.equals("QUIT")) { reply(writer, "221 Bye"); return; }
                else reply(writer, "250 OK");
            }
        } catch (Exception exception) {
            if (!server.isClosed()) throw new IllegalStateException("Falha no servidor SMTP simulado", exception);
        }
    }

    private void reply(BufferedWriter writer, String response) throws IOException {
        writer.write(response + "\r\n"); writer.flush();
    }

    @Override
    public void close() throws Exception {
        if (releaseAcceptance != null) releaseAcceptance.countDown();
        server.close();
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }
}
