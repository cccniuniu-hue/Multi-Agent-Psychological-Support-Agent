package com.mindbridge.agent.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** 同一份证据用于模型引用编号与服务端来源展示。 */
public final class KnowledgeEvidence {

    private static final ObjectMapper JSON = new ObjectMapper();

    private KnowledgeEvidence() {
    }

    public static String format(List<SearchResult> results) {
        ArrayNode evidence = JSON.createArrayNode();
        List<SearchResult> entries = entries(results);
        for (int i = 0; i < entries.size(); i++) {
            SearchResult result = entries.get(i);
            ObjectNode item = evidence.addObject();
            item.put("citation", "[" + (i + 1) + "]");
            if (result.chunkId() != null) {
                item.put("chunkId", result.chunkId());
            }
            item.put("source", sourceName(result));
            item.put("content", result.content().substring(0, Math.min(4000, result.content().length())));
        }
        return evidence.toString();
    }

    public static String sourceFooter(List<SearchResult> results) {
        List<SearchResult> entries = entries(results);
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder footer = new StringBuilder("\n\n检索参考来源：");
        for (int i = 0; i < entries.size(); i++) {
            SearchResult result = entries.get(i);
            footer.append("\n[").append(i + 1).append("] ").append(escapeMarkdown(sourceName(result)));
            if (result.chunkId() != null) {
                footer.append("（片段 #").append(result.chunkId()).append("）");
            }
        }
        return footer.toString();
    }

    private static List<SearchResult> entries(List<SearchResult> results) {
        // shortcut: 每轮最多提供十段、每段四千字符；需要更长上下文时再改为 token 预算。
        return results.stream().filter(result -> result.content() != null && !result.content().isBlank())
                .limit(10).toList();
    }

    private static String sourceName(SearchResult result) {
        String source = result.source() == null ? "未知来源" : result.source().replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]", " ");
        return source.substring(0, Math.min(180, source.length()));
    }

    private static String escapeMarkdown(String source) {
        return source.replaceAll("([\\\\`*_{}\\[\\]()<>#!|])", "\\\\$1");
    }
}
