package io.github.candyxi0.hidenest.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

final class LocalV1RequestContext {

    static final String REQUEST_ID_ATTRIBUTE = LocalV1RequestContext.class.getName() + ".requestId";
    static final String REQUEST_ID_HEADER = "X-Request-Id";

    private LocalV1RequestContext() {}

    static UUID requestId(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        return value instanceof UUID id ? id : UUID.randomUUID();
    }
}
