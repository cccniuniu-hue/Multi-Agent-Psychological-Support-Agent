package com.mindbridge.agent.service.knowledge;

import com.mindbridge.agent.config.MindBridgeProperties;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

/** Marker 文档转换 HTTP 客户端；不可用时返回空结果，由本地解析器降级处理。 */
@Component
public class MarkerDocumentClient {

    private final MindBridgeProperties.Knowledge config;
    private final WebClient webClient;

    public MarkerDocumentClient(MindBridgeProperties properties, WebClient.Builder webClientBuilder) {
        this.config = properties.getKnowledge();
        this.webClient = webClientBuilder.baseUrl(config.getMarkerBaseUrl()).build();
    }

    public Optional<DocumentParseResult> parse(String filename, byte[] bytes) {
        if (!config.isMarkerEnabled() || !supports(filename)) {
            return Optional.empty();
        }
        MultipartBodyBuilder multipart = new MultipartBodyBuilder();
        multipart.part("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        try {
            MarkerResponse response = webClient.post()
                    .uri("/marker/upload")
                    .body(BodyInserters.fromMultipartData(multipart.build()))
                    .retrieve()
                    .bodyToMono(MarkerResponse.class)
                    .timeout(Duration.ofSeconds(config.getMarkerTimeoutSeconds()))
                    .block();
            if (response == null || !response.success() || response.output() == null || response.output().isBlank()) {
                return Optional.empty();
            }
            String source = valueOrDefault(response.source(), filename);
            String title = valueOrDefault(response.title(), titleFromSource(source));
            List<DocumentParseResult.ImageReference> images = response.images() == null
                    ? List.of()
                    : response.images().keySet().stream()
                            .map(path -> new DocumentParseResult.ImageReference(path, null))
                            .toList();
            return Optional.of(new DocumentParseResult(
                    source,
                    title,
                    response.output(),
                    List.of(new DocumentParseResult.Page(1, response.output())),
                    images,
                    DocumentParseResult.Status.SUCCESS,
                    null));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private boolean supports(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".pdf") || lower.endsWith(".docx");
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String titleFromSource(String source) {
        int extension = source.lastIndexOf('.');
        return extension > 0 ? source.substring(0, extension) : source;
    }

    private record MarkerResponse(
            boolean success,
            String source,
            String title,
            String output,
            Map<String, String> images
    ) {
    }
}
