package io.github.candyxi0.hidenest.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.candyxi0.hidenest.embedding.HttpEmbeddingProviderAdapter.EmbeddingServiceException;
import io.github.candyxi0.hidenest.memory.port.EmbeddingProviderPort.EmbeddingResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Loopback fake-HTTP tests for the fail-closed embedding provider adapter. */
class HttpEmbeddingProviderAdapterTest {

    private static final String MODEL = "bge-small-zh-v1.5";
    private static final int DIMENSION = 512;

    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(null);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpEmbeddingProviderAdapter adapter(long timeoutMs) {
        return new HttpEmbeddingProviderAdapter("http://127.0.0.1:" + port, MODEL, DIMENSION, timeoutMs, 32);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String vectorJson(int dimension, double first) {
        StringBuilder sb = new StringBuilder(dimension * 8);
        sb.append('[');
        for (int i = 0; i < dimension; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(i == 0 ? first : 0.0);
        }
        sb.append(']');
        return sb.toString();
    }

    private static String okBody(List<String> vectorsJson) {
        return "{\"model\":\"" + MODEL + "\",\"dimension\":" + DIMENSION
                + ",\"vectors\":[" + String.join(",", vectorsJson) + "]}";
    }

    @Test
    void embedsSingleText() {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/embed", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, okBody(List.of(vectorJson(DIMENSION, 1.0))));
        });
        EmbeddingResult result = adapter(5000).embed(List.of("hello"));
        assertEquals(MODEL, result.model());
        assertEquals(DIMENSION, result.dimension());
        assertEquals(1, result.vectors().size());
        assertEquals(DIMENSION, result.vectors().get(0).length);
        assertEquals(1, requests.get());
    }

    @Test
    void embedsBatchOfTwoAndThirtyTwo() {
        server.createContext("/embed", exchange -> {
            int n = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(readBody(exchange))
                    .path("texts")
                    .size();
            List<String> vectors = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                vectors.add(vectorJson(DIMENSION, 1.0));
            }
            respond(exchange, 200, okBody(vectors));
        });
        EmbeddingResult two = adapter(5000).embed(List.of("a", "b"));
        assertEquals(2, two.vectors().size());

        List<String> thirtyTwo = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            thirtyTwo.add("t" + i);
        }
        EmbeddingResult batch = adapter(5000).embed(thirtyTwo);
        assertEquals(32, batch.vectors().size());
    }

    @Test
    void rejectsBlankOversizeAndBatchBoundsBeforeHttp() {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/embed", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, okBody(List.of(vectorJson(DIMENSION, 1.0))));
        });
        HttpEmbeddingProviderAdapter a = adapter(5000);

        assertThrows(IllegalArgumentException.class, () -> a.embed(List.of("")));
        assertThrows(IllegalArgumentException.class, () -> a.embed(List.of("   ")));
        String oversize = "x".repeat(481);
        assertThrows(IllegalArgumentException.class, () -> a.embed(List.of(oversize)));
        assertThrows(IllegalArgumentException.class, () -> a.embed(List.of()));
        List<String> thirtyThree = new ArrayList<>();
        for (int i = 0; i < 33; i++) {
            thirtyThree.add("t" + i);
        }
        assertThrows(IllegalArgumentException.class, () -> a.embed(thirtyThree));
        assertEquals(0, requests.get(), "no HTTP request must be issued for rejected inputs");
    }

    @Test
    void rejectsNon2xxResponse() {
        server.createContext("/embed", exchange -> respond(exchange, 500, "{\"error\":\"embedding_failed\"}"));
        assertThrows(EmbeddingServiceException.class, () -> adapter(5000).embed(List.of("hello")));
    }

    @Test
    void rejectsInvalidJson() {
        server.createContext("/embed", exchange -> respond(exchange, 200, "not-json"));
        assertThrows(EmbeddingServiceException.class, () -> adapter(5000).embed(List.of("hello")));
    }

    @Test
    void rejectsModelDimensionCountAndLengthMismatch() {
        server.createContext("/embed", exchange -> {
            String body = readBody(exchange);
            if (body.contains("\"texts\":[\"model\"]")) {
                respond(exchange, 200, "{\"model\":\"other\",\"dimension\":512,\"vectors\":[" + vectorJson(512, 1.0) + "]}");
            } else if (body.contains("\"texts\":[\"dim\"]")) {
                respond(exchange, 200, "{\"model\":\"" + MODEL + "\",\"dimension\":256,\"vectors\":[" + vectorJson(512, 1.0) + "]}");
            } else if (body.contains("\"texts\":[\"count\"]")) {
                respond(exchange, 200, "{\"model\":\"" + MODEL + "\",\"dimension\":512,\"vectors\":[]}");
            } else {
                respond(exchange, 200, "{\"model\":\"" + MODEL + "\",\"dimension\":512,\"vectors\":[[" + "1.0,2.0" + "]]}");
            }
        });
        HttpEmbeddingProviderAdapter a = adapter(5000);
        assertThrows(EmbeddingServiceException.class, () -> a.embed(List.of("model")));
        assertThrows(EmbeddingServiceException.class, () -> a.embed(List.of("dim")));
        assertThrows(EmbeddingServiceException.class, () -> a.embed(List.of("count")));
        assertThrows(EmbeddingServiceException.class, () -> a.embed(List.of("length")));
    }

    @Test
    void rejectsNonFiniteAndZeroNormVectors() {
        server.createContext("/embed", exchange -> {
            String body = readBody(exchange);
            if (body.contains("\"texts\":[\"inf\"]")) {
                respond(exchange, 200, okBody(List.of(vectorJsonWith(DIMENSION, "1e999"))));
            } else {
                respond(exchange, 200, okBody(List.of(vectorJson(DIMENSION, 0.0))));
            }
        });
        HttpEmbeddingProviderAdapter a = adapter(5000);
        assertThrows(EmbeddingServiceException.class, () -> a.embed(List.of("inf")));
        assertThrows(EmbeddingServiceException.class, () -> a.embed(List.of("zero")));
    }

    @Test
    void rejectsTimeout() {
        server.createContext("/embed", exchange -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, okBody(List.of(vectorJson(DIMENSION, 1.0))));
        });
        assertThrows(EmbeddingServiceException.class, () -> adapter(200).embed(List.of("hello")));
    }

    @Test
    void rejectsDisconnect() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            int disconnectPort = socket.getLocalPort();
            Thread closer = new Thread(() -> {
                try (Socket client = socket.accept()) {
                    client.close();
                } catch (IOException ignored) {
                    // expected
                }
            });
            closer.setDaemon(true);
            closer.start();
            HttpEmbeddingProviderAdapter a = new HttpEmbeddingProviderAdapter(
                    "http://127.0.0.1:" + disconnectPort, MODEL, DIMENSION, 2000, 32);
            assertThrows(EmbeddingServiceException.class, () -> a.embed(List.of("hello")));
            closer.join(3000);
        }
    }

    @Test
    void healthProbesAndDisabledBaseUrl() {
        server.createContext("/health", exchange -> respond(exchange, 200, "{\"model\":\"" + MODEL + "\",\"dimension\":" + DIMENSION + "}"));
        assertTrue(adapter(5000).health().healthy());

        HttpEmbeddingProviderAdapter disabled = new HttpEmbeddingProviderAdapter("", MODEL, DIMENSION, 5000, 32);
        assertFalse(disabled.health().healthy());
        assertThrows(EmbeddingServiceException.class, () -> disabled.embed(List.of("hello")));
    }

    @Test
    void rejectsUrlAttackMatrix() {
        new HttpEmbeddingProviderAdapter("http://127.0.0.1:18090", MODEL, DIMENSION, 5000, 32);
        new HttpEmbeddingProviderAdapter("http://localhost:18090/", MODEL, DIMENSION, 5000, 32);
        assertThrows(IllegalArgumentException.class, () -> new HttpEmbeddingProviderAdapter("https://127.0.0.1:18090", MODEL, DIMENSION, 5000, 32));
        assertThrows(IllegalArgumentException.class, () -> new HttpEmbeddingProviderAdapter("http://127.0.0.1", MODEL, DIMENSION, 5000, 32));
        assertThrows(IllegalArgumentException.class, () -> new HttpEmbeddingProviderAdapter("http://127.0.0.1:18090/api", MODEL, DIMENSION, 5000, 32));
        assertThrows(IllegalArgumentException.class, () -> new HttpEmbeddingProviderAdapter("http://127.0.0.1:18090?x=1", MODEL, DIMENSION, 5000, 32));
        assertThrows(IllegalArgumentException.class, () -> new HttpEmbeddingProviderAdapter("http://127.0.0.1:18090#x", MODEL, DIMENSION, 5000, 32));
        assertThrows(IllegalArgumentException.class, () -> new HttpEmbeddingProviderAdapter("http://user@127.0.0.1:18090", MODEL, DIMENSION, 5000, 32));
        assertThrows(IllegalArgumentException.class, () -> new HttpEmbeddingProviderAdapter("http://example.com:18090", MODEL, DIMENSION, 5000, 32));
    }

    @Test
    void embeddingResultDeepCopiesVectors() {
        double[] raw = new double[DIMENSION];
        raw[0] = 1.0;
        EmbeddingResult result = new EmbeddingResult(MODEL, DIMENSION, List.of(raw));

        raw[0] = 999.0;
        assertEquals(1.0, result.vectors().get(0)[0], "construction must deep-copy");

        result.vectors().get(0)[0] = 777.0;
        assertEquals(1.0, result.vectors().get(0)[0], "accessor must return a fresh copy");
    }

    @Test
    void http10ServerConsecutiveRequestsUseIndependentConnections() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        ServerSocket raw = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(() -> {
            try {
                while (!raw.isClosed()) {
                    Socket socket = raw.accept();
                    connections.incrementAndGet();
                    handleHttp10Connection(socket);
                }
            } catch (IOException ignored) {
                // server closed
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
        int rawPort = raw.getLocalPort();
        try {
            HttpEmbeddingProviderAdapter adapter = new HttpEmbeddingProviderAdapter(
                    "http://127.0.0.1:" + rawPort, MODEL, DIMENSION, 5000, 32);

            assertTrue(adapter.health().healthy(), "health must succeed over HTTP/1.0");
            assertEquals(1, adapter.embed(List.of("A")).vectors().size(), "embed A must succeed");
            assertEquals(1, adapter.embed(List.of("B")).vectors().size(), "embed B must succeed");
            assertEquals(1, adapter.embed(List.of("A")).vectors().size(), "embed replay must succeed");
            assertEquals(1, adapter.embed(List.of("query")).vectors().size(), "embed query must succeed");
            assertEquals(5, connections.get(), "health + 4 embeds must each use an independent connection");
        } finally {
            raw.close();
        }
    }

    private static void handleHttp10Connection(Socket socket) {
        try (socket) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            byte[] headerBytes = readHttpHeaders(in);
            String headers = new String(headerBytes, StandardCharsets.US_ASCII);
            String requestLine = headers.split("\r\n", 2)[0];
            String path = requestLine.split(" ")[1];
            int contentLength = 0;
            for (String line : headers.split("\r\n")) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
                }
            }
            if (contentLength > 0) {
                in.readNBytes(contentLength);
            }
            String body = path.equals("/health")
                    ? "{\"model\":\"" + MODEL + "\",\"dimension\":" + DIMENSION + "}"
                    : "{\"model\":\"" + MODEL + "\",\"dimension\":" + DIMENSION
                            + ",\"vectors\":[" + vectorJson(DIMENSION, 1.0) + "]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.0 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length
                            + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.flush();
        } catch (IOException ignored) {
            // connection closed
        }
    }

    private static byte[] readHttpHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] window = {-1, -1, -1, -1};
        int b;
        while ((b = in.read()) != -1) {
            out.write(b);
            window[0] = window[1];
            window[1] = window[2];
            window[2] = window[3];
            window[3] = b;
            if (window[0] == '\r' && window[1] == '\n' && window[2] == '\r' && window[3] == '\n') {
                break;
            }
        }
        return out.toByteArray();
    }

    private static String vectorJsonWith(int dimension, String first) {
        StringBuilder sb = new StringBuilder(dimension * 8);
        sb.append('[');
        for (int i = 0; i < dimension; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(i == 0 ? first : "0.0");
        }
        sb.append(']');
        return sb.toString();
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }
}
