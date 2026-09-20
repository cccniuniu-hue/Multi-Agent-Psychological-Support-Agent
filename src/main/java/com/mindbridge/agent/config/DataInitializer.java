package com.mindbridge.agent.config;

import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.repository.UserAccountRepository;
import com.mindbridge.agent.service.knowledge.KnowledgeIngestionService;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DataInitializer implements ApplicationRunner {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final KnowledgeIngestionService knowledgeIngestionService;

    public DataInitializer(
            UserAccountRepository userAccountRepository,
            PasswordEncoder passwordEncoder,
            KnowledgeIngestionService knowledgeIngestionService
    ) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.knowledgeIngestionService = knowledgeIngestionService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (args.containsOption("rebuild-index")) {
            return;
        }
        // 首次启动准备演示账号；内置知识库会按 source 补齐或刷新。
        seedUsers();
        knowledgeIngestionService.syncClasspathKnowledge();
    }

    private void seedUsers() {
        if (userAccountRepository.count() > 0) {
            return;
        }
        // 管理员账号用于后台查看，学生账号用于正常聊天体验。
        UserAccount admin = new UserAccount();
        admin.setUsername("admin");
        admin.setDisplayName("Counselor Admin");
        admin.setPassword(passwordEncoder.encode("admin123"));
        admin.setRoles(Set.of("ROLE_ADMIN", "ROLE_USER"));
        userAccountRepository.save(admin);

        UserAccount student = new UserAccount();
        student.setUsername("student");
        student.setDisplayName("Demo Student");
        student.setPassword(passwordEncoder.encode("student123"));
        student.setRoles(Set.of("ROLE_USER"));
        userAccountRepository.save(student);
    }
}
