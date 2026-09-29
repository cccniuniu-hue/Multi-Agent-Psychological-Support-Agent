package com.mindbridge.agent.service.knowledge;

import com.mindbridge.agent.config.MindBridgeProperties;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

/** 图片描述失败时跳过该图片，保留文档正文的解析与入库。 */
@Component
public class ImageDescriptionClient {
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private final MindBridgeProperties.Knowledge config;
    private final WebClient webClient;

    public ImageDescriptionClient(MindBridgeProperties properties, WebClient.Builder webClientBuilder) {
        config = properties.getKnowledge();
        webClient = webClientBuilder.baseUrl(config.getImageDescriptionBaseUrl()).build();
    }

    public Optional<ImageDescription> describe(String path, String base64) {
        if (!config.isImageDescriptionEnabled() || path == null || base64 == null || base64.isBlank()
                || base64.length() > ((MAX_IMAGE_BYTES + 2) / 3) * 4
                || path.contains("\r") || path.contains("\n")) {
            return Optional.empty();
        }
        String normalized = path.replace('\\', '/');
        String filename = normalized.substring(normalized.lastIndexOf('/') + 1);
        MediaType mediaType = imageType(filename);
        if (mediaType == null) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
                return Optional.empty();
            }
            MultipartBodyBuilder multipart = new MultipartBodyBuilder();
            multipart.part("file", new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            }).contentType(mediaType);
            Response response = webClient.post()
                    .uri("/qwen2-vl/describe")
                    .body(BodyInserters.fromMultipartData(multipart.build()))
                    .retrieve()
                    .bodyToMono(Response.class)
                    .timeout(Duration.ofSeconds(Math.max(1, config.getImageDescriptionTimeoutSeconds())))
                    .block();
            if (response == null || !response.success() || response.description() == null
                    || response.description().summary() == null || response.description().summary().isBlank()
                    || (response.source() != null && !filename.equals(response.source()))) {
                return Optional.empty();
            }
            return Optional.of(response.description());
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private MediaType imageType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        return lower.endsWith(".webp") ? MediaType.parseMediaType("image/webp") : null;
    }

    private record Response(boolean success, String source, ImageDescription description) {
    }
}
