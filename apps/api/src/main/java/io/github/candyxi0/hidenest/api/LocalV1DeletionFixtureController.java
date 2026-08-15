package io.github.candyxi0.hidenest.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Loopback-only, capability-gated fixture seeding for the shared-evidence browser QA.
 * Not part of the OpenAPI contract; never registered in a production profile.
 */
@RestController
@Profile("local-v1-synthetic")
public final class LocalV1DeletionFixtureController {

    private final LocalV1SharedEvidenceFixtureCoordinator fixture;

    public LocalV1DeletionFixtureController(LocalV1SharedEvidenceFixtureCoordinator fixture) {
        this.fixture = fixture;
    }

    @PostMapping(value = "/v1/deletion-fixtures", produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, String> create(HttpServletRequest request) {
        LocalV1SharedEvidenceFixtureCoordinator.Fixture result = fixture.createSharedEvidenceFixture();
        return Map.of(
                "requestId", LocalV1RequestContext.requestId(request).toString(),
                "resultCategory", "SUCCEEDED",
                "memoryA", result.memoryA().toString(),
                "memoryB", result.memoryB().toString(),
                "memoryC", result.memoryC().toString(),
                "sharedFixtureBoundary", "SHARED_FIXTURE_NOT_PROOF_OF_MULTI_CANDIDATE_CLOSEOUT");
    }
}
