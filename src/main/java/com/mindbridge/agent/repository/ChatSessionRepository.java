package com.mindbridge.agent.repository;

import com.mindbridge.agent.domain.ChatSession;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话的数据访问接口。
 */
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    /** 学生继续对话时，必须校验会话属于当前用户。 */
    Optional<ChatSession> findByPublicIdAndUser_Id(String publicId, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ChatSession s where s.publicId = :publicId and s.user.id = :userId")
    Optional<ChatSession> findForMemoryUpdate(String publicId, Long userId);

    @Modifying
    @Transactional
    @Query("update ChatSession s set s.updatedAt = :updatedAt where s.id = :sessionId")
    void updateActivity(Long sessionId, Instant updatedAt);

    /** 管理员查看会话详情时需要连同用户信息一起加载。 */
    @EntityGraph(attributePaths = "user")
    Optional<ChatSession> findByPublicId(String publicId);
}
