package io.github.candyxi0.hidenest.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.candyxi0.hidenest.memory.port.EmbeddingProviderPort;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * JDK {@link HttpClient} implementation of the embedding provider port for the
 * custom {@code /health} and {@code /embed} protocol. It is deliberately not
 * OpenAI-compatible.
 *
 * <p>Fail-closed transport and protocol validation: only loopback base URLs are
 * accepted (empty base URL disables the adapter), only 2xx responses are accepted,
 * and the response model, dimension, vector count, per-vector length, finiteness
 * and non-zero norm are all re-verified. No body or vector content is ever logged
 * or embedded in an exception message.
 */
public class HttpEmbeddingProviderAdapter implements EmbeddingProviderPort {

    private static final int MAX_UTF8_BYTES = 480;

    private final String baseUrl;
    private final String modelName;
    private final int dimension;
    private final long timeoutMs;
    private final int maxBatchSize;
    private final ObjectMapper objectMapper;

    public HttpEmbeddingProviderAdapter(
            String baseUrl, String modelName, int dimension, long timeoutMs, int maxBatchSize) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.modelName = Objects.requireNonNull(modelName, "modelName");
        this.dimension = dimension;
        this.timeoutMs = timeoutMs;
        this.maxBatchSize = maxBatchSize;
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive");
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive");
        }
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be positive");
        }
        this.objectMapper = new ObjectMapper();
    }

    /** A fresh, single-use JDK HttpClient. Never reused across requests. */
    private HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    @Override
    public EmbeddingHealth health() {
        if (baseUrl.isEmpty()) {
            return new EmbeddingHealth(false, modelName, dimension);
        }
        try (HttpClient client = newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (!is2xx(response.statusCode())) {
                return new EmbeddingHealth(false, modelName, dimension);
            }
            JsonNode root = objectMapper.readTree(response.body());
            String responseModel = root.path("model").asText("");
            int responseDimension = root.path("dimension").asInt(0);
            boolean matches = modelName.equals(responseModel) && responseDimension == dimension;
            return new EmbeddingHealth(
                    matches,
                    responseModel.isEmpty() ? modelName : responseModel,
                    responseDimension > 0 ? responseDimension : dimension);
        } catch (Exception exception) {
            return new EmbeddingHealth(false, modelName, dimension);
        }
    }

    @Override
    public EmbeddingResult embed(List<String> texts) {
        requireEnabled();
        validateInputs(texts);

        String body = writeRequestBody(texts);
        HttpResponse<String> response;
        try (HttpClient client = newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/embed"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception exception) {
            throw new EmbeddingServiceException("embedding service transport failure");
        }
        if (!is2xx(response.statusCode())) {
            throw new EmbeddingServiceException("embedding service non-2xx response");
        }
        return parseResponse(response.body(), texts.size());
    }

    private void validateInputs(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("texts must not be empty");
        }
        if (texts.size() > maxBatchSize) {
            throw new IllegalArgumentException("texts batch exceeds maximum");
        }
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("text must be non-blank");
            }
            if (text.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
                throw new IllegalArgumentException("text exceeds UTF-8 byte limit");
            }
        }
    }

    private String writeRequestBody(List<String> texts) {
        try {
            return objectMapper.writeValueAsString(Map.of("texts", texts));
        } catch (Exception exception) {
            throw new EmbeddingServiceException("embedding request serialization failure");
        }
    }

    private EmbeddingResult parseResponse(String body, int expectedCount) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception exception) {
            throw new EmbeddingServiceException("embedding response is not valid JSON");
        }
        JsonNode modelNode = root.path("model");
        JsonNode dimensionNode = root.path("dimension");
        JsonNode vectorsNode = root.path("vectors");
        if (!modelNode.isTextual() || !modelName.equals(modelNode.asText())) {
            throw new EmbeddingServiceException("embedding response model mismatch");
        }
        if (!dimensionNode.isInt() || dimensionNode.asInt() != dimension) {
            throw new EmbeddingServiceException("embedding response dimension mismatch");
        }
        if (!vectorsNode.isArray() || vectorsNode.size() != expectedCount) {
            throw new EmbeddingServiceException("embedding response vector count mismatch");
        }
        List<double[]> vectors = new ArrayList<>(expectedCount);
        for (JsonNode vectorNode : vectorsNode) {
            if (!vectorNode.isArray() || vectorNode.size() != dimension) {
                throw new EmbeddingServiceException("embedding response vector length mismatch");
            }
            double[] vector = new double[dimension];
            double sumSquares = 0.0;
            for (int i = 0; i < dimension; i++) {
                JsonNode valueNode = vectorNode.get(i);
                if (!valueNode.isNumber()) {
                    throw new EmbeddingServiceException("embedding response non-numeric value");
                }
                double value = valueNode.asDouble();
                if (!Double.isFinite(value)) {
                    throw new EmbeddingServiceException("embedding response non-finite value");
                }
                vector[i] = value;
                sumSquares += value * value;
            }
            double norm = Math.sqrt(sumSquares);
            if (!Double.isFinite(norm) || norm <= 0.0) {
                throw new EmbeddingServiceException("embedding response zero-norm vector");
            }
            vectors.add(vector);
        }
        return new EmbeddingResult(modelName, dimension, vectors);
    }

    private void requireEnabled() {
        if (baseUrl.isEmpty()) {
            throw new EmbeddingServiceException("embedding service is disabled");
        }
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.strip();
        if (normalized.isEmpty()) {
            return "";
        }
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("embedding base URL is invalid");
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("embedding base URL must use http scheme");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("embedding base URL must not contain user info");
        }
        String host = uri.getHost();
        if (host == null || !isLoopback(host)) {
            throw new IllegalArgumentException("embedding base URL must be loopback");
        }
        int port = uri.getPort();
        if (port < 1) {
            throw new IllegalArgumentException("embedding base URL must have an explicit port");
        }
        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new IllegalArgumentException("embedding base URL must not have a path");
        }
        if (uri.getRawQuery() != null) {
            throw new IllegalArgumentException("embedding base URL must not have a query");
        }
        if (uri.getRawFragment() != null) {
            throw new IllegalArgumentException("embedding base URL must not have a fragment");
        }
        return "http://" + host + ":" + port;
    }

    private static boolean isLoopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    private static boolean is2xx(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    /** Sanitized fail-closed exception carrying no body or vector content. */
    public static final class EmbeddingServiceException extends RuntimeException {
        public EmbeddingServiceException(String message) {
            super(message);
        }
    }
}
