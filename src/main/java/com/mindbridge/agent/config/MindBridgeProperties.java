package com.mindbridge.agent.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mindbridge")
/**
 * mindbridge.* 配置映射。
 *
 * <p>所有业务配置集中在这里，便于通过 application.yml 或环境变量切换模型、
 * RAG、知识库切块、Excel 写入和邮件预警行为。</p>
 */
public class MindBridgeProperties {

    private final Ai ai = new Ai();
    private final Chat chat = new Chat();
    private final Memory memory = new Memory();
    private final Embedding embedding = new Embedding();
    private final Knowledge knowledge = new Knowledge();
    private final RagEval ragEval = new RagEval();
    private final Mcp mcp = new Mcp();

    public Ai getAi() {
        return ai;
    }

    public Chat getChat() {
        return chat;
    }

    public Memory getMemory() {
        return memory;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public Knowledge getKnowledge() {
        return knowledge;
    }

    public RagEval getRagEval() {
        return ragEval;
    }

    public Mcp getMcp() {
        return mcp;
    }

    public static class Ai {
        /** 聊天模型提供方：deepseek 或 openai。 */
        private String provider = "deepseek";
        /** 生成温度，值越高回答越发散。 */
        private double temperature = 0.35;
        /** 学生端单次回复的最大生成 token 数。 */
        private int maxTokens = 512;
        private final ChatApi deepseek = new ChatApi("https://api.deepseek.com", "deepseek-flash");
        private final ChatApi openai = new ChatApi("https://api.openai.com", "gpt-4o-mini");

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public ChatApi getDeepseek() {
            return deepseek;
        }

        public ChatApi getOpenai() {
            return openai;
        }
    }

    public static class ChatApi {
        /** OpenAI 兼容聊天接口地址。 */
        private String baseUrl;
        private String apiKey = "";
        private String model;

        public ChatApi(String baseUrl, String model) {
            this.baseUrl = baseUrl;
            this.model = model;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    public static class Chat {
        /** 保留给模型的历史轮次数，服务层会换算成用户/助手消息条数。 */
        private int historyLimit = 10;
        /** Redis 短期记忆 TTL，过期后可从 MySQL 长期记忆恢复最近上下文。 */
        private long shortMemoryTtlHours = 24;

        public int getHistoryLimit() {
            return historyLimit;
        }

        public void setHistoryLimit(int historyLimit) {
            this.historyLimit = historyLimit;
        }

        public long getShortMemoryTtlHours() {
            return shortMemoryTtlHours;
        }

        public void setShortMemoryTtlHours(long shortMemoryTtlHours) {
            this.shortMemoryTtlHours = shortMemoryTtlHours;
        }
    }

    public static class Memory {
        /** 是否启用 Chroma 作为用户画像长期记忆的语义索引。 */
        private boolean useChroma;
        /** 用户画像专用向量化配置；默认关闭，避免敏感文本离开主存储。 */
        private final MemoryEmbedding embedding = new MemoryEmbedding();
        private String chromaBaseUrl = "http://localhost:8000";
        private String chromaTenant = "default_tenant";
        private String chromaDatabase = "default_database";
        private String chromaCollection = "mindbridge_user_memory";
        /** 每轮按当前输入召回的画像记忆数量。 */
        private int topK = 6;

        public MemoryEmbedding getEmbedding() {
            return embedding;
        }

        public boolean isUseChroma() {
            return useChroma;
        }

        public void setUseChroma(boolean useChroma) {
            this.useChroma = useChroma;
        }

        public String getChromaBaseUrl() {
            return chromaBaseUrl;
        }

        public void setChromaBaseUrl(String chromaBaseUrl) {
            this.chromaBaseUrl = chromaBaseUrl;
        }

        public String getChromaTenant() {
            return chromaTenant;
        }

        public void setChromaTenant(String chromaTenant) {
            this.chromaTenant = chromaTenant;
        }

        public String getChromaDatabase() {
            return chromaDatabase;
        }

        public void setChromaDatabase(String chromaDatabase) {
            this.chromaDatabase = chromaDatabase;
        }

        public String getChromaCollection() {
            return chromaCollection;
        }

        public void setChromaCollection(String chromaCollection) {
            this.chromaCollection = chromaCollection;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }
    }

    public static class Embedding {
        /** Embedding 服务地址。 */
        private String baseUrl = "https://api.openai.com";
        /** Embedding API Key，留空时自动走本地检索兜底。 */
        private String apiKey = "";
        /** 文档要求的默认 embedding 模型。 */
        private String model = "text-embedding-3-small";
        /** 请求与响应必须使用的 embedding 维度。 */
        private int dimensions = 512;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getDimensions() {
            return dimensions;
        }

        public void setDimensions(int dimensions) {
            this.dimensions = dimensions;
        }
    }

    public static class MemoryEmbedding {
        private boolean enabled;
        private String baseUrl = "https://api.openai.com";
        private String apiKey = "";
        private String model = "text-embedding-3-small";
        private int dimensions = 512;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getDimensions() {
            return dimensions;
        }

        public void setDimensions(int dimensions) {
            this.dimensions = dimensions;
        }
    }

    public static class Knowledge {
        /** 每次 RAG 检索返回的候选片段数量。 */
        private int topK = 4;
        /** 是否启用二阶段 reranker。 */
        private boolean rerankerEnabled = true;
        /** 初排后交给 reranker 的最大候选数量。 */
        private int rerankerCandidateLimit = 20;
        /** 送入 reranker 的单个 chunk 最大字符数，避免 prompt 过长。 */
        private int rerankerMaxContentChars = 700;
        /** 是否启用外部 Chroma 向量库。 */
        private boolean useChroma;
        private String chromaBaseUrl = "http://localhost:8000";
        private String chromaTenant = "default_tenant";
        private String chromaDatabase = "default_database";
        private String chromaCollection = "mindbridge_knowledge";
        private int chunkSize = 512;
        private int chunkOverlap = 64;

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public boolean isRerankerEnabled() {
            return rerankerEnabled;
        }

        public void setRerankerEnabled(boolean rerankerEnabled) {
            this.rerankerEnabled = rerankerEnabled;
        }

        public int getRerankerCandidateLimit() {
            return rerankerCandidateLimit;
        }

        public void setRerankerCandidateLimit(int rerankerCandidateLimit) {
            this.rerankerCandidateLimit = rerankerCandidateLimit;
        }

        public int getRerankerMaxContentChars() {
            return rerankerMaxContentChars;
        }

        public void setRerankerMaxContentChars(int rerankerMaxContentChars) {
            this.rerankerMaxContentChars = rerankerMaxContentChars;
        }

        public boolean isUseChroma() {
            return useChroma;
        }

        public void setUseChroma(boolean useChroma) {
            this.useChroma = useChroma;
        }

        public String getChromaBaseUrl() {
            return chromaBaseUrl;
        }

        public void setChromaBaseUrl(String chromaBaseUrl) {
            this.chromaBaseUrl = chromaBaseUrl;
        }

        public String getChromaTenant() {
            return chromaTenant;
        }

        public void setChromaTenant(String chromaTenant) {
            this.chromaTenant = chromaTenant;
        }

        public String getChromaDatabase() {
            return chromaDatabase;
        }

        public void setChromaDatabase(String chromaDatabase) {
            this.chromaDatabase = chromaDatabase;
        }

        public String getChromaCollection() {
            return chromaCollection;
        }

        public void setChromaCollection(String chromaCollection) {
            this.chromaCollection = chromaCollection;
        }

        public int getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }

        public int getChunkOverlap() {
            return chunkOverlap;
        }

        public void setChunkOverlap(int chunkOverlap) {
            this.chunkOverlap = chunkOverlap;
        }
    }

    public static class RagEval {
        /** 是否在启动后生成 RAGAS 输入报告。 */
        private boolean enabled;
        /** 评测集 JSON 路径，支持 classpath: 或文件系统路径。 */
        private String dataset = "classpath:rag-eval/mindbridge-rag-eval.json";
        /** 评测链路使用的 TopK 元数据。 */
        private int topK = 4;
        /** 是否在报告生成后退出应用，便于命令行/CI 单独跑评测。 */
        private boolean exitAfterRun;
        /** RAGAS 输入 JSON 报告输出路径，留空则只打印控制台摘要。 */
        private String outputPath = "target/rag-eval-report.json";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDataset() {
            return dataset;
        }

        public void setDataset(String dataset) {
            this.dataset = dataset;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public boolean isExitAfterRun() {
            return exitAfterRun;
        }

        public void setExitAfterRun(boolean exitAfterRun) {
            this.exitAfterRun = exitAfterRun;
        }

        public String getOutputPath() {
            return outputPath;
        }

        public void setOutputPath(String outputPath) {
            this.outputPath = outputPath;
        }
    }

    public static class Mcp {
        private final Excel excel = new Excel();
        private final Email email = new Email();

        public Excel getExcel() {
            return excel;
        }

        public Email getEmail() {
            return email;
        }
    }

    public static class Excel {
        /** Excel 写入模式：local、http 或 mcp。 */
        private String mode = "local";
        private String url = "http://localhost:8081";
        private String localPath = "./data/mindbridge-reports.xlsx";

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getLocalPath() {
            return localPath;
        }

        public void setLocalPath(String localPath) {
            this.localPath = localPath;
        }
    }

    public static class Email {
        /** 邮件预警模式：log、smtp、http 或 mcp。 */
        private String mode = "log";
        private String url = "http://localhost:8082";
        private String from = "mindbridge@example.com";
        private List<String> recipients = new ArrayList<>(List.of("counselor@example.com"));
        private int maxRetries = 2;
        /** MCP Server 收到 send_risk_alert 工具调用后实际投递方式：log 或 smtp。 */
        private String mcpServerDeliveryMode = "log";

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public List<String> getRecipients() {
            return recipients;
        }

        public void setRecipients(List<String> recipients) {
            this.recipients = recipients;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public String getMcpServerDeliveryMode() {
            return mcpServerDeliveryMode;
        }

        public void setMcpServerDeliveryMode(String mcpServerDeliveryMode) {
            this.mcpServerDeliveryMode = mcpServerDeliveryMode;
        }
    }
}
