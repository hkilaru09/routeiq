package com.routeiq;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records webhook calls. Any path containing {@code /fail/} answers 500. */
public class WebhookSink implements AutoCloseable {

    public record Call(String path, String body) {
    }

    private final HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    public WebhookSink() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            calls.add(new Call(path, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            int status = path.contains("/fail/") ? 500 : 204;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Call> calls() {
        return calls;
    }

    public List<Call> callsMentioning(String text) {
        return calls.stream().filter(c -> c.body().contains(text)).toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
