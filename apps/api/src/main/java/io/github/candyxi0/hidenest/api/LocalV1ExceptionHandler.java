package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1S2BException;
import io.github.candyxi0.hidenest.contracts.model.FailureCode;
import io.github.candyxi0.hidenest.contracts.model.ProblemDetail;
import io.github.candyxi0.hidenest.contracts.model.ResultCategory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
@Profile("local-v1-synthetic")
public final class LocalV1ExceptionHandler {

    @ExceptionHandler({
        LocalV1RequestException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentNotValidException.class
    })
    ResponseEntity<ProblemDetail> requestInvalid(Exception ignored, HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.UNPROCESSABLE_ENTITY,
                ResultCategory.FAILED,
                FailureCode.REQUEST_SCHEMA_INVALID,
                "请求参数不符合本机只读接口约束",
                false);
    }

    @ExceptionHandler(LocalV1NotFoundException.class)
    ResponseEntity<ProblemDetail> explicitNotFound(Exception ignored, HttpServletRequest request) {
        return notFound(request);
    }

    @ExceptionHandler(LocalV1S2BException.class)
    ResponseEntity<ProblemDetail> s2b(LocalV1S2BException exception, HttpServletRequest request) {
        return switch (exception.code()) {
            case INVALID_ARGUMENT -> requestInvalid(exception, request);
            case NOT_FOUND, DELETION_FENCED -> notFound(request);
            case PAYLOAD_UNAVAILABLE -> problem(
                    request,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ResultCategory.FAILED,
                    FailureCode.PAYLOAD_STORE_UNAVAILABLE,
                    "已保存证据当前无法完整读取",
                    true);
            default -> integrityFailure(request);
        };
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> failClosed(Exception ignored, HttpServletRequest request) {
        return integrityFailure(request);
    }

    private static ResponseEntity<ProblemDetail> notFound(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.NOT_FOUND,
                ResultCategory.NO_RELEVANT_RESULT,
                FailureCode.RETRIEVAL_NO_MATCH,
                "未找到可读取的当前记忆",
                false);
    }

    private static ResponseEntity<ProblemDetail> integrityFailure(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.INTERNAL_SERVER_ERROR,
                ResultCategory.FAILED,
                FailureCode.INTERNAL_FAILURE,
                "记忆读取完整性检查失败",
                false);
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpServletRequest request,
            HttpStatus status,
            ResultCategory category,
            FailureCode code,
            String title,
            boolean retryable) {
        var requestId = LocalV1RequestContext.requestId(request);
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(LocalV1RequestContext.REQUEST_ID_HEADER, requestId.toString())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(LocalV1ProblemFactory.create(
                        requestId, status.value(), category, code, title, retryable));
    }
}
