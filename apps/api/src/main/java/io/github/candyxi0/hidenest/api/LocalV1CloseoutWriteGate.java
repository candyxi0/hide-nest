package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.contracts.model.FailureCode;
import io.github.candyxi0.hidenest.contracts.model.ResultCategory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Synthetic write gate for {@code POST /v1/closeout-submissions}.
 *
 * <p>Requires a high-entropy bearer and a high-entropy {@code X-Action-Capability}; both come from
 * process configuration and never touch HTML, URLs, disk, responses, logs or reports. When no
 * capability is configured the write path fails closed. This is a local synthetic gate, not a
 * production session or authorization boundary.</p>
 */
@Component
@Profile("local-v1-synthetic")
@Order(1)
public final class LocalV1CloseoutWriteGate extends OncePerRequestFilter {

    private static final Pattern CLOSEOUT_PATH = Pattern.compile("^/v1/closeout-submissions$");
    private static final String BEARER_PREFIX = "Bearer ";

    private final byte[] expectedToken;
    private final byte[] expectedCapability;
    private final ObjectMapper objectMapper;

    public LocalV1CloseoutWriteGate(
            @Value("${hidenest.local-v1.synthetic-token}") String token,
            @Value("${hidenest.local-v1.synthetic-capability:}") String capability,
            ObjectMapper objectMapper) {
        LocalV1ReadConfiguration.requireHighEntropyToken(token);
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
        if (capability == null || capability.isBlank()) {
            this.expectedCapability = null;
        } else {
            LocalV1ReadConfiguration.requireHighEntropyToken(capability);
            this.expectedCapability = capability.getBytes(StandardCharsets.UTF_8);
        }
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID requestId = UUID.randomUUID();
        request.setAttribute(LocalV1RequestContext.REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(LocalV1RequestContext.REQUEST_ID_HEADER, requestId.toString());
        response.setHeader("Cache-Control", "no-store");

        String path = request.getRequestURI();
        if (!"POST".equals(request.getMethod()) || !CLOSEOUT_PATH.matcher(path).matches()) {
            chain.doFilter(request, response);
            return;
        }

        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            writeProblem(response, requestId, 401, FailureCode.ACCESS_DENIED, "本机合成访问凭据缺失");
            return;
        }
        byte[] supplied = authorization.substring(BEARER_PREFIX.length()).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, supplied)) {
            writeProblem(response, requestId, 403, FailureCode.ACCESS_DENIED, "本机合成访问凭据无效");
            return;
        }

        if (expectedCapability == null) {
            writeProblem(response, requestId, 403, FailureCode.CAPABILITY_REQUIRED, "本机关窗动作能力未配置");
            return;
        }
        String capability = request.getHeader("X-Action-Capability");
        if (capability == null) {
            writeProblem(response, requestId, 403, FailureCode.CAPABILITY_REQUIRED, "本机关窗动作能力缺失");
            return;
        }
        byte[] suppliedCapability = capability.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedCapability, suppliedCapability)) {
            writeProblem(response, requestId, 403, FailureCode.CAPABILITY_REQUIRED, "本机关窗动作能力无效");
            return;
        }

        chain.doFilter(request, response);
    }

    private void writeProblem(
            HttpServletResponse response, UUID requestId, int status, FailureCode code, String title)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                LocalV1ProblemFactory.create(
                        requestId, status, ResultCategory.DENIED, code, title, false));
    }
}
