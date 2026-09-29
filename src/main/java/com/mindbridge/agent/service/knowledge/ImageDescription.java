package com.mindbridge.agent.service.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Qwen2-VL 图片描述，字段与 Python 服务的 JSON 契约一致。 */
public record ImageDescription(
        @JsonProperty("image_type") String imageType,
        String summary,
        @JsonProperty("core_elements") List<String> coreElements,
        @JsonProperty("key_relations") List<String> keyRelations,
        @JsonProperty("data_insights") List<String> dataInsights
) {
    public ImageDescription {
        coreElements = coreElements == null ? List.of() : List.copyOf(coreElements);
        keyRelations = keyRelations == null ? List.of() : List.copyOf(keyRelations);
        dataInsights = dataInsights == null ? List.of() : List.copyOf(dataInsights);
    }
}
