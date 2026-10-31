package com.nextstep.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

import com.nextstep.api.support.IntegrationTestSupport;

/** Keeps the published OpenAPI document and the implemented endpoints in step. */
class OpenApiContractTest extends IntegrationTestSupport {

    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    @SuppressWarnings("unchecked")
    void documentedOperationsMatchImplementedOperations() throws Exception {
        Map<String, Object> document;
        try (InputStream in = Files.newInputStream(Path.of("openapi", "openapi.yaml"))) {
            document = new Yaml().load(in);
        }
        Set<String> documented = new TreeSet<>();
        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
        paths.forEach((path, item) -> ((Map<String, Object>) item).keySet().stream()
                .filter(HTTP_METHODS::contains)
                .forEach(method -> documented.add(method.toUpperCase(Locale.ROOT) + " /api/v1" + path)));

        Set<String> implemented = new TreeSet<>();
        handlerMapping.getHandlerMethods().keySet().forEach(info -> info.getPatternValues().stream()
                .filter(pattern -> pattern.startsWith("/api/v1/"))
                .forEach(pattern -> info.getMethodsCondition().getMethods()
                        .forEach(method -> implemented.add(method.name() + " " + pattern))));
        implemented.add("GET /api/v1/me");
        implemented.add("PATCH /api/v1/me");
        implemented.add("DELETE /api/v1/me");

        assertThat(documented).isEqualTo(implemented);
    }

    @Test
    void documentIsPublishedWithoutSignIn() {
        var response = browser().get("/api/v1/openapi.yaml");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).startsWith("openapi: 3.1.0");
    }
}

