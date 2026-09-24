package com.mindbridge.agent.controller;

import com.mindbridge.agent.dto.KnowledgeIngestRequest;
import com.mindbridge.agent.dto.KnowledgeIngestResponse;
import com.mindbridge.agent.service.knowledge.KnowledgeFileService;
import com.mindbridge.agent.service.knowledge.KnowledgeService;
import jakarta.validation.Valid;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/admin/knowledge")
/**
 * 管理员知识库维护接口。
 *
 * <p>支持直接写入文本，也支持上传 PDF、DOCX、Markdown、txt 文件作为 RAG 知识库来源。</p>
 */
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final KnowledgeFileService knowledgeFileService;

    public KnowledgeController(KnowledgeService knowledgeService, KnowledgeFileService knowledgeFileService) {
        this.knowledgeService = knowledgeService;
        this.knowledgeFileService = knowledgeFileService;
    }

    @PostMapping
    public KnowledgeIngestResponse ingest(@Valid @RequestBody KnowledgeIngestRequest request) {
        // JSON 接口适合脚本或调试时直接写入一段知识。
        int chunks = knowledgeService.ingest(request.source(), request.content());
        return new KnowledgeIngestResponse(request.source(), chunks);
    }

    @PostMapping(value = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<KnowledgeIngestResponse> ingestFile(@RequestPart("file") FilePart file) {
        // WebFlux 的 FilePart 是流式数据，这里合并成 byte[] 后交给文件解析服务。
        return DataBufferUtils.join(file.content())
                .map(dataBuffer -> {
                    byte[] bytes = readBytes(dataBuffer);
                    int chunks = knowledgeFileService.ingest(file.filename(), bytes);
                    return new KnowledgeIngestResponse(file.filename(), chunks);
                });
    }

    private byte[] readBytes(DataBuffer dataBuffer) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(dataBuffer.readableByteCount());
            dataBuffer.asInputStream().transferTo(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalArgumentException("文件读取失败：" + exception.getMessage());
        } finally {
            DataBufferUtils.release(dataBuffer);
        }
    }
}
