package com.mindbridge.agent.repository;

import com.mindbridge.agent.domain.UserMemoryItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 用户画像记忆的数据访问接口。
 */
public interface UserMemoryItemRepository extends JpaRepository<UserMemoryItem, Long> {

    List<UserMemoryItem> findTop12ByUser_IdOrderByUpdatedAtDesc(Long userId);

    List<UserMemoryItem> findByUser_IdOrderByUpdatedAtDesc(Long userId);

    Optional<UserMemoryItem> findByIdAndUser_Id(Long id, Long userId);

    List<UserMemoryItem> findByUser_IdAndIdIn(Long userId, List<Long> ids);
}
