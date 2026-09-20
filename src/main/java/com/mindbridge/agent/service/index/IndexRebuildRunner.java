package com.mindbridge.agent.service.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 单次索引重建入口：--spring.main.web-application-type=none --rebuild-index=all。 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class IndexRebuildRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(IndexRebuildRunner.class);
    private final IndexRebuildService service;

    public IndexRebuildRunner(IndexRebuildService service) {
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("rebuild-index")) {
            return;
        }
        var values = args.getOptionValues("rebuild-index");
        if (values == null || values.size() != 1) {
            throw new IllegalArgumentException("Supply exactly one --rebuild-index=knowledge|memory|all");
        }
        IndexRebuildService.Result result = service.rebuild(values.get(0));
        logger.info("Index rebuild completed: knowledgeIndexed={}, knowledgeSkipped={}, memoryIndexed={}, memoryDisabled={}",
                result.knowledgeIndexed(), result.knowledgeSkipped(),
                result.memoryIndexed(), result.memoryDisabled());
    }
}
