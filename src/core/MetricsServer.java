package core;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Lightweight HTTP server exposing operational endpoints for AegisCore.
 *
 * <p>Uses the built-in {@code com.sun.net.httpserver.HttpServer} — no external
 * dependencies required.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET /health} — JSON health status suitable for load-balancer probes.</li>
 *   <li>{@code GET /metrics} — Prometheus plain-text exposition format.</li>
 * </ul>
 */
public final class MetricsServer {

    private final HttpServer        httpServer;
    private final MetricsCollector  collector;

    private MetricsServer(HttpServer httpServer, MetricsCollector collector) {
        this.httpServer = httpServer;
        this.collector  = collector;
    }

    /**
     * Starts the metrics HTTP server on the given port.
     *
     * @param port      TCP port to bind (default 8080)
     * @param collector the metrics collector to query
     * @return the running {@code MetricsServer} instance
     * @throws IOException if the port cannot be bound
     */
    public static MetricsServer start(int port, MetricsCollector collector) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 10);

        server.createContext("/health",  exchange -> handleHealth(exchange, collector));
        server.createContext("/metrics", exchange -> handleMetrics(exchange, collector));
        server.setExecutor(null); // use default executor
        server.start();

        Logger.logServer("MetricsServer: listening on http://localhost:" + port + " (/health, /metrics)");
        return new MetricsServer(server, collector);
    }

    /** Stops the HTTP server immediately. */
    public void stop() {
        httpServer.stop(0);
        Logger.logServer("MetricsServer: stopped.");
    }

    // -----------------------------------------------------------------------

    private static void handleHealth(HttpExchange ex, MetricsCollector collector) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return;
        }
        String body = collector.collect().toJson();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void handleMetrics(HttpExchange ex, MetricsCollector collector) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return;
        }
        String body  = collector.collect().toPrometheusText();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
