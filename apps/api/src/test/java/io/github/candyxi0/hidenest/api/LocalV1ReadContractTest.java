package io.github.candyxi0.hidenest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.candyxi0.hidenest.contracts.api.MemoriesApi;
import io.github.candyxi0.hidenest.contracts.model.MemoryDetailResponse;
import io.github.candyxi0.hidenest.contracts.model.MemoryEvidenceResponse;
import io.github.candyxi0.hidenest.contracts.model.MemoryListResponse;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

class LocalV1ReadContractTest {

    @Test
    void controllersExposeExactlyTheThreeFormalReadMappingsAndGeneratedDtos() {
        Map<Class<?>, Endpoint> expected = Map.of(
                LocalV1MemoryListController.class,
                new Endpoint("/v1" + MemoriesApi.PATH_LIST_MEMORIES, MemoryListResponse.class),
                LocalV1MemoryDetailController.class,
                new Endpoint("/v1" + MemoriesApi.PATH_GET_MEMORY, MemoryDetailResponse.class),
                LocalV1MemoryEvidenceController.class,
                new Endpoint("/v1" + MemoriesApi.PATH_GET_MEMORY_EVIDENCE, MemoryEvidenceResponse.class));

        for (var entry : expected.entrySet()) {
            Method[] mapped = Arrays.stream(entry.getKey().getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(GetMapping.class))
                    .toArray(Method[]::new);
            assertEquals(1, mapped.length);
            Method method = mapped[0];
            assertEquals(Set.of(entry.getValue().path()), Set.of(method.getAnnotation(GetMapping.class).value()));
            assertEquals(entry.getValue().responseType(), method.getReturnType());
        }
    }

    @Test
    void parameterNamesMechanicallyMatchFormalContract() throws Exception {
        Method list = LocalV1MemoryListController.class.getDeclaredMethod(
                "list", String.class, String.class, String.class, java.util.UUID.class,
                String.class, String.class, Integer.class, jakarta.servlet.http.HttpServletRequest.class);
        assertEquals(Set.of(
                        "query", "state", "memoryType", "perspectiveActorId", "sourceAvailability", "cursor", "limit"),
                Arrays.stream(list.getParameterAnnotations())
                        .flatMap(Arrays::stream)
                        .filter(RequestParam.class::isInstance)
                        .map(RequestParam.class::cast)
                        .map(RequestParam::value)
                        .collect(Collectors.toSet()));

        Method detail = LocalV1MemoryDetailController.class.getDeclaredMethod(
                "detail", java.util.UUID.class, jakarta.servlet.http.HttpServletRequest.class);
        assertEquals("memoryId", detail.getParameters()[0].getAnnotation(PathVariable.class).value());

        Method evidence = LocalV1MemoryEvidenceController.class.getDeclaredMethod(
                "evidence", java.util.UUID.class, java.util.UUID.class,
                jakarta.servlet.http.HttpServletRequest.class);
        assertEquals("memoryId", evidence.getParameters()[0].getAnnotation(PathVariable.class).value());
        assertEquals("revisionId", evidence.getParameters()[1].getAnnotation(RequestParam.class).value());
    }

    @Test
    void cursorIsCanonicalOpaqueAndTamperEvident() {
        String cursor = LocalV1CursorCodec.encode(50);
        assertEquals(50, LocalV1CursorCodec.decode(cursor));
        assertFalse(cursor.contains("50"));
        assertThrows(IllegalArgumentException.class, () -> LocalV1CursorCodec.decode(cursor + "x"));
        assertThrows(IllegalArgumentException.class, () -> LocalV1CursorCodec.decode("MA"));
    }

    @Test
    void loopbackAndEntropyStartupGuardsFailClosed() {
        LocalV1ReadConfiguration.requireLoopback("127.0.0.1");
        LocalV1ReadConfiguration.requireLoopback("::1");
        assertThrows(IllegalStateException.class, () -> LocalV1ReadConfiguration.requireLoopback("0.0.0.0"));
        assertThrows(IllegalStateException.class, () -> LocalV1ReadConfiguration.requireLoopback("192.0.2.1"));
        assertThrows(IllegalStateException.class, () -> LocalV1ReadConfiguration.requireHighEntropyToken("short"));
        LocalV1ReadConfiguration.requireHighEntropyToken(
                "SyntheticOnly-7xP3qW9vN2mK5sR8dF1hJ4cB6yT0uLz");
    }

    private record Endpoint(String path, Class<?> responseType) {}
}
