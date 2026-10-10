package com.mindbridge.agent.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.domain.UserMemoryItem;
import com.mindbridge.agent.repository.UserAccountRepository;
import com.mindbridge.agent.repository.UserMemoryItemRepository;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.memory.UserMemoryChromaGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:mindbridge-memory-api;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "mindbridge.knowledge.use-chroma=false",
        "mindbridge.memory.use-chroma=false",
        "spring.ai.mcp.server.enabled=false",
        "spring.ai.mcp.client.enabled=false"
})
@AutoConfigureWebTestClient
class UserMemoryApiTests {

    @Autowired
    private WebTestClient client;
    @Autowired
    private UserMemoryItemRepository memories;
    @Autowired
    private UserAccountRepository users;
    @MockBean
    private UserMemoryChromaGateway chroma;
    @MockBean
    private AiClient aiClient;

    @BeforeEach
    void setUp() {
        memories.deleteAll();
        when(chroma.delete(anyLong(), anyLong())).thenReturn(true);
    }

    @Test
    void listOnlyReturnsCurrentUsersMemories() {
        UserMemoryItem own = memory("student", "偏好简短直接的回复");
        memory("admin", "另一个账号的独立偏好");
        client.get().uri("/api/profile/memory")
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].id").isEqualTo(own.getId().intValue());
    }

    @Test
    void deletesOwnedMemoryAndItsIndex() {
        UserMemoryItem own = memory("student", "偏好简短直接的回复");
        client.delete().uri("/api/profile/memory/{id}", own.getId())
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .exchange().expectStatus().isNoContent();
        assertThat(memories.findById(own.getId())).isEmpty();
        verify(chroma).delete(own.getUser().getId(), own.getId());
    }

    @Test
    void rejectsForeignAndMissingIdsWithoutTouchingChroma() {
        UserMemoryItem foreign = memory("admin", "另一个账号的独立偏好");
        for (Long id : new Long[] { foreign.getId(), -1L }) {
            client.delete().uri("/api/profile/memory/{id}", id)
                    .headers(headers -> headers.setBasicAuth("student", "student123"))
                    .exchange().expectStatus().isNotFound();
        }
        assertThat(memories.findById(foreign.getId())).isPresent();
        verifyNoInteractions(chroma);
    }

    @Test
    void failedIndexDeletionKeepsDatabaseRowAndRetryCompletesDeletion() {
        UserMemoryItem own = memory("student", "偏好简短直接的回复");
        when(chroma.delete(own.getUser().getId(), own.getId())).thenReturn(false).thenReturn(true);
        client.delete().uri("/api/profile/memory/{id}", own.getId())
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .exchange().expectStatus().isEqualTo(503);
        assertThat(memories.findById(own.getId())).isPresent();
        client.delete().uri("/api/profile/memory/{id}", own.getId())
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .exchange().expectStatus().isNoContent();
        assertThat(memories.findById(own.getId())).isEmpty();
    }

    @Test
    void anonymousRequestsCannotReadOrDeleteMemories() {
        client.get().uri("/api/profile/memory").exchange().expectStatus().isUnauthorized();
        client.delete().uri("/api/profile/memory/1").exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(chroma);
    }

    @Test
    void legacyMemoryResponsesMaskIdentifiersWithoutMutatingDatabaseRows() {
        UserMemoryItem legacy = memory("student", "偏好通过 student@example.test 接收通知");
        legacy.setEvidence("电话13800000000，学号：SYNTHETIC001，姓名：示例甲，地址：示例路0号");
        memories.save(legacy);
        client.get().uri("/api/profile/memory")
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$[0].summary").isEqualTo("偏好通过 [邮箱] 接收通知")
                .jsonPath("$[0].evidence").isEqualTo("电话[手机号]，学号:[学号]，姓名：[姓名]，地址：[地址]");
        assertThat(memories.findById(legacy.getId()).orElseThrow().getSummary()).contains("student@example.test");
    }

    private UserMemoryItem memory(String username, String summary) {
        UserAccount owner = users.findByUsername(username).orElseThrow();
        UserMemoryItem item = new UserMemoryItem();
        item.setUser(owner);
        item.setSummary(summary);
        item.setEvidence("synthetic evidence");
        return memories.save(item);
    }
}
