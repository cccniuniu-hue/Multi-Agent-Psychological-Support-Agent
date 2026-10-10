package com.mindbridge.agent.dto;

import com.mindbridge.agent.domain.UserMemoryItem;
import com.mindbridge.agent.domain.UserMemoryType;
import com.mindbridge.agent.service.PrivacySanitizer;
import java.time.Instant;

/**
 * 用户画像记忆响应。
 */
public record UserMemoryItemResponse(
        Long id,
        UserMemoryType type,
        String summary,
        String evidence,
        double confidence,
        Instant createdAt,
        Instant updatedAt,
        Instant lastSeenAt
) {
    public static UserMemoryItemResponse from(UserMemoryItem item, PrivacySanitizer privacySanitizer) {
        return new UserMemoryItemResponse(
                item.getId(),
                item.getType(),
                privacySanitizer.sanitize(item.getSummary()),
                privacySanitizer.sanitize(item.getEvidence()),
                item.getConfidence(),
                item.getCreatedAt(),
                item.getUpdatedAt(),
                item.getLastSeenAt());
    }
}
