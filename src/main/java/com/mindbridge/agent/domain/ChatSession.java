package com.mindbridge.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "chat_sessions")
/**
 * 一次连续对话会话。
 *
 * <p>publicId 暴露给前端使用，数据库自增 id 只在服务端内部关联。</p>
 */
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private UserAccount user;

    @Column(nullable = false, length = 120)
    private String title = "New conversation";

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    private Long profileMemoryThroughMessageId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private RiskLevel profileMemoryRiskLevel;

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public void setPublicId(String publicId) {
        this.publicId = publicId;
    }

    public UserAccount getUser() {
        return user;
    }

    public void setUser(UserAccount user) {
        this.user = user;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getProfileMemoryThroughMessageId() {
        return profileMemoryThroughMessageId;
    }

    public void setProfileMemoryThroughMessageId(Long messageId) {
        this.profileMemoryThroughMessageId = messageId;
    }

    public RiskLevel getProfileMemoryRiskLevel() {
        return profileMemoryRiskLevel;
    }

    public void setProfileMemoryRiskLevel(RiskLevel riskLevel) {
        this.profileMemoryRiskLevel = riskLevel;
    }

    public void touch() {
        // 每次新增消息时更新时间，后台列表可以按最近活跃排序。
        this.updatedAt = Instant.now();
    }
}
