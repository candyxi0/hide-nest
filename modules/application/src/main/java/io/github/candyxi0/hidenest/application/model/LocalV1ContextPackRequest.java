package io.github.candyxi0.hidenest.application.model;

import java.util.UUID;

/**
 * Transport model for a Local V1 context pack request.
 *
 * <p>Mirrors the formal {@code ContextPackRequest} contract as a plain record so the application
 * boundary does not depend on the generated OpenAPI DTOs.</p>
 */
public record LocalV1ContextPackRequest(UUID threadId, UUID turnId, String purpose, String query) {}
