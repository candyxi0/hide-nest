package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.contracts.model.FailureCode;
import io.github.candyxi0.hidenest.contracts.model.ProblemDetail;
import io.github.candyxi0.hidenest.contracts.model.ResultCategory;
import java.net.URI;
import java.util.Locale;
import java.util.UUID;

final class LocalV1ProblemFactory {

    private LocalV1ProblemFactory() {}

    static ProblemDetail create(
            UUID requestId,
            int status,
            ResultCategory category,
            FailureCode code,
            String safeTitle,
            boolean retryable) {
        return new ProblemDetail(
                URI.create("urn:hide-nest:problem:" + code.getValue().toLowerCase(Locale.ROOT)),
                safeTitle,
                status,
                requestId,
                category,
                code,
                retryable);
    }
}
