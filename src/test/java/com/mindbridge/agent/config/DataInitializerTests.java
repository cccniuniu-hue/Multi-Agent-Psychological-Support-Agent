package com.mindbridge.agent.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mindbridge.agent.repository.UserAccountRepository;
import com.mindbridge.agent.service.knowledge.KnowledgeIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

class DataInitializerTests {

    @Test
    void indexRebuildDoesNotSeedAccountsOrResyncKnowledge() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        KnowledgeIngestionService knowledge = mock(KnowledgeIngestionService.class);
        DataInitializer initializer = new DataInitializer(users, mock(PasswordEncoder.class), knowledge);

        initializer.run(new DefaultApplicationArguments("--rebuild-index=all"));

        verifyNoInteractions(users, knowledge);
    }
}
