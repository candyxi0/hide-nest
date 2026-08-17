package io.github.candyxi0.hidenest.api;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@ControllerAdvice
@Profile({"local-v1-synthetic", "local-private"})
public final class LocalV1ResponseHeadersAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(
            org.springframework.core.MethodParameter returnType,
            Class<? extends org.springframework.http.converter.HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            org.springframework.core.MethodParameter returnType,
            org.springframework.http.MediaType selectedContentType,
            Class<? extends org.springframework.http.converter.HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        response.getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
        return body;
    }
}
