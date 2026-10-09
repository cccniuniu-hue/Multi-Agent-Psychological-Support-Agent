package com.mindbridge.agent.repository;

import com.mindbridge.agent.domain.ChatMessage;
import com.mindbridge.agent.domain.MessageRole;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 聊天消息的数据访问接口。
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** 取最近消息用于模型上下文，Service 层会再反转为时间正序。 */
    List<ChatMessage> findTop20BySession_IdOrderByCreatedAtDesc(Long sessionId);

    List<ChatMessage> findTop10BySession_IdAndRoleAndIdGreaterThanOrderByIdAsc(
            Long sessionId, MessageRole role, Long messageId);

    /** 管理员点开记录时读取完整会话。 */
    List<ChatMessage> findBySession_PublicIdOrderByCreatedAtAsc(String publicId);
}
