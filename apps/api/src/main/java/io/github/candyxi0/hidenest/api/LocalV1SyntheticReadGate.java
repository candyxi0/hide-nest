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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Synthetic local read gate only; this is not a production session or authorization boundary. */
@Component
@Profile({"local-v1-synthetic", "local-private"})
public final class LocalV1SyntheticReadGate extends OncePerRequestFilter {

    private static final Pattern ALLOWED_PATH = Pattern.compile(
            "^/v1/memories(?:/[0-9a-fA-F-]{36}(?:/evidence)?)?$|^/v1/runs/[0-9a-fA-F-]{36}$");
    private static final Pattern CLOSEOUT_WRITE_PATH = Pattern.compile("^/v1/closeout-submissions$");
    private static final Pattern DELETION_WRITE_PATH =
            Pattern.compile("^/v1/deletion-previews(?:/[0-9a-fA-F-]{36}/confirm)?$");
    private static final Pattern DELETION_RUN_PATH = Pattern.compile("^/v1/deletion-runs/[0-9a-fA-F-]{36}$");
    private static final Pattern FIXTURE_PATH = Pattern.compile("^/v1/deletion-fixtures$");
    private static final Pattern CANDIDATE_SET_WRITE_PATH =
            Pattern.compile("^/v1/review-sessions/[0-9a-fA-F-]{36}/final-submissions$");
    private static final Pattern CONTEXT_PACK_PATH = Pattern.compile("^/v1/context-packs$");
    private static final String BEARER_PREFIX = "Bearer ";

    private final byte[] expectedToken;
    private final ObjectMapper objectMapper;

    public LocalV1SyntheticReadGate(
            @Value("${hidenest.local-v1.synthetic-token}") String token, ObjectMapper objectMapper) {
        LocalV1ReadConfiguration.requireHighEntropyToken(token);
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
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
        if (!path.startsWith("/v1/")) {
            chain.doFilter(request, response);
            return;
        }
        // The closeout write path is validated by LocalV1CloseoutWriteGate (ordered before us).
        if ("POST".equals(request.getMethod()) && CLOSEOUT_WRITE_PATH.matcher(path).matches()) {
            chain.doFilter(request, response);
            return;
        }
        // The CandidateSet write path is validated by LocalV1CandidateSetWriteGate (ordered before us).
        if ("POST".equals(request.getMethod()) && CANDIDATE_SET_WRITE_PATH.matcher(path).matches()) {
            chain.doFilter(request, response);
            return;
        }
        // The deletion write paths are validated by LocalV1DeletionGate (ordered before us).
        if ("POST".equals(request.getMethod()) && DELETION_WRITE_PATH.matcher(path).matches()) {
            chain.doFilter(request, response);
            return;
        }
        if ("GET".equals(request.getMethod()) && DELETION_RUN_PATH.matcher(path).matches()) {
            chain.doFilter(request, response);
            return;
        }
        // The shared-evidence fixture path is validated by LocalV1DeletionGate (ordered before us).
        if ("POST".equals(request.getMethod()) && FIXTURE_PATH.matcher(path).matches()) {
            chain.doFilter(request, response);
            return;
        }
        boolean contextPackWrite =
                "POST".equals(request.getMethod()) && CONTEXT_PACK_PATH.matcher(path).matches();
        boolean readAllowed = "GET".equals(request.getMethod()) && ALLOWED_PATH.matcher(path).matches();
        if (!contextPackWrite && !readAllowed) {
            writeProblem(response, requestId, 403, FailureCode.ACCESS_DENIED, "此本机只读入口不允许该请求");
            return;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            writeProblem(response, requestId, 401, FailureCode.ACCESS_DENIED, "本机访问凭据缺失");
            return;
        }
        byte[] supplied = authorization.substring(BEARER_PREFIX.length()).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, supplied)) {
            writeProblem(response, requestId, 403, FailureCode.ACCESS_DENIED, "本机访问凭据无效");
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
