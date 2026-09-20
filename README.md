# 多agent心理护航智能体



- 动态路由 RAG：先识别 `CHAT / CONSULT / RISK`，闲聊不查知识库，咨询与风险消息才进入检索增强。
- SSE 流式输出：`/api/chat/stream` 返回 `text/event-stream`，适合前端做打字机效果。
- 后台心理状态识别：记录情绪标签、情绪分数、风险等级和置信度，但学生端不展示评估结果。
- 用户画像记忆：从对话中抽取稳定偏好、沟通方式和支持需求，MySQL 保存可审计记录；显式开启画像向量化后可用 Chroma 语义召回。
- 数据闭环：咨询/风险消息写入数据库，高风险先写 Excel，再触发邮件或 HTTP MCP 预警。
- Spring AI 模型接入：默认通过 DeepSeek API 提供聊天与生成能力，也可按需切到 `openai`。
- 可替换知识库：默认本地轻量检索，可打开 Chroma 镜像和查询。
- 多 Agent loop：每轮输入由 MemoryAgent、SupervisorAgent、KnowledgeAgent、RiskGuardianAgent 和回复 Agent 协作完成

默认聊天模型为 DeepSeek API 的 `deepseek-flash`。知识库和用户画像的 Embedding 服务独立配置；DeepSeek API Key 不会自动用作向量化密钥。

## 目录

```text
src/main/java/com/mindbridge/agent
├── config                 # 配置、安全、AI/MCP Bean
├── controller             # Chat / Knowledge / Report API
├── domain                 # JPA 实体与枚举
├── dto                    # 请求与响应对象
├── repository             # Spring Data JPA
├── security               # 当前用户与认证查询
└── service
	    ├── ai                 # Spring AI 模型适配器与 Prompt
	    ├── agent              # 多 Agent loop：记忆、路由、知识检索、风险守护与回复规划
	    ├── knowledge          # 切块、检索、Chroma 网关
	    ├── memory             # Redis 短期记忆与用户画像长期记忆
	    └── mcp                # Excel 与邮件/HTTP 预警工具
```

## Agent loop 与多 Agent 分工

每轮对话进入一个有限步 agent loop，最多执行 8 步，防止心理安全场景中出现无限自主循环：

```text
MemoryAgent
-> SupervisorAgent
-> KnowledgeAgent
-> RiskGuardianAgent
-> CompanionAgent / CounselorAgent
```

各 Agent 分工：

- `MemoryAgent`：读取 Redis 短期记忆，并用当前输入从 Chroma 召回相关用户画像；Redis 为空时从 MySQL 长期聊天记录恢复。
- `SupervisorAgent`：调用模型判断 `CHAT / CONSULT / RISK`，决定后续交给普通陪伴还是心理支持链路。
- `KnowledgeAgent`：调用模型改写 Chroma/RAG 检索 query，并判断检索结果是否足够，不足时二次检索。
- `RiskGuardianAgent`：调用模型做后台心理状态评估，同时保留高风险词库硬兜底。
- `CompanionAgent`：调用模型生成普通聊天回复策略，并组装普通助手回复 prompt。
- `CounselorAgent`：调用模型生成心理支持回复策略，并结合记忆、RAG、风险守护结果组装回复 prompt。

最终回复仍通过 Spring AI 流式调用项目模型输出给学生端；后台风险报告、Excel 和预警工具链仍按安全规则执行。

## 部署与运行

### 1. 环境准备

| 软件 | 版本/用途 | 是否必需 |
| --- | --- | --- |
| JDK | 17 | 本地构建或运行 Jar 时必需 |
| Maven | 3.9+ | 本地构建时必需 |
| DeepSeek API Key | 外接聊天模型 | 默认聊天模式必需 |
| Docker / Docker Compose | 启动 MySQL、Redis、Chroma 和 Mailpit | 仅容器部署时必需 |

先进入项目根目录：

```bash
cd /path/to/MindBridge
```

可用下列命令检查本地环境：

```bash
java -version
mvn -version
docker compose version
```

> 如果发布包中包含 `.tools/` 目录，项目脚本会优先使用其中的 JDK 17 和 Maven；否则使用系统已安装的版本。

### 2. 本地快速运行（适合开发与演示）

先从 DeepSeek 平台获取 API Key，并在当前终端设置环境变量：

```bash
export DEEPSEEK_API_KEY='你的_API_Key'
```

然后启动项目（PowerShell 可先执行 `$env:DEEPSEEK_API_KEY='你的_API_Key'`）：

```bash
./scripts/run-dev.sh
```

`run-dev.sh` 不再安装或启动本地大模型，只通过 Maven 启动 Spring Boot。需要选择其他 DeepSeek 模型时可设置：

```bash
DEEPSEEK_MODEL=deepseek-v4-pro ./scripts/run-dev.sh
```

也可以直接执行 `mvn -Dmaven.repo.local=.m2/repository spring-boot:run`。

这种模式默认使用：

- `./data/mindbridge.mv.db` H2 文件数据库；
- `./data/mindbridge-reports.xlsx` 高风险报告文件；
- DeepSeek 远程聊天模型；
- 日志模式预警（不真正发送邮件）。

### 3. 打包并运行 Jar（适合服务器部署）

先构建可执行 Jar：

```bash
mvn -Dmaven.repo.local=.m2/repository clean package
```

本机启动：

```bash
DEEPSEEK_API_KEY=你的_API_Key \
java -jar target/mindbridge-agent-0.1.0.jar \
  --server.address=127.0.0.1 \
  --server.port=8080
```

如需从其他主机访问，将 `--server.address` 改为 `0.0.0.0`，并在防火墙或反向代理中放行对应端口。生产环境建议使用 systemd 等进程管理工具，并由 Nginx 或网关提供 HTTPS。

### 4. Docker Compose 完整部署

Compose 会启动以下服务：

| 服务 | 端口 | 说明 |
| --- | --- | --- |
| MindBridge | `8080` | Web 界面与 API |
| MySQL | `3306` | 业务数据 |
| Redis | `6379` | 短期会话记忆 |
| Chroma | `8000` | 知识库和用户画像向量检索 |
| Mailpit | `1025` / `8025` | SMTP 测试服务 / 管理页面 |

Docker Compose 会将 `DEEPSEEK_API_KEY` 注入应用容器。可复制 `.env.example` 为 `.env`，填入真实密钥；`.env` 已被 Git 忽略。对话内容会发往外部 DeepSeek 服务，处理真实心理咨询数据前应明确告知使用者并确认数据处理安排。

为避免首次启动时 MySQL 尚未就绪，建议先启动依赖，再构建应用容器：

```bash
docker compose up -d mysql redis chroma mailpit
docker compose ps
docker compose up -d --build app
```

查看应用日志：

```bash
docker compose logs -f app
```

停止服务（保留数据卷）：

```bash
docker compose down
```

如需同时删除 MySQL、Redis 和 Chroma 的持久化数据，可执行 `docker compose down -v`。该操作不可恢复，仅建议在重置开发环境时使用。

### 5. 常用配置

所有配置都可通过环境变量覆盖，常用项如下：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | 应用端口 |
| `AI_PROVIDER` | `deepseek` | `deepseek` 或 `openai` |
| `DEEPSEEK_API_KEY` | 空 | DeepSeek 聊天密钥，默认模式必需 |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | DeepSeek OpenAI 兼容接口地址 |
| `DEEPSEEK_MODEL` | `deepseek-flash` | DeepSeek 聊天模型名 |
| `OPENAI_API_KEY` | 空 | OpenAI 密钥 |
| `OPENAI_MODEL` | `gpt-4o-mini` | OpenAI 模型名 |
| `OPENAI_EMBEDDING_MODEL` / `OPENAI_EMBEDDING_DIMENSIONS` | `text-embedding-3-small` / `512` | 知识库 Embedding 模型与输出维度 |
| `MEMORY_EMBEDDING_ENABLED` | `false` | 显式允许画像文本向量化；默认不发送画像文本到 Embedding 服务或 Chroma |
| `MEMORY_EMBEDDING_API_KEY` | 空 | 画像专用密钥，不复用 `OPENAI_API_KEY` |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 见配置文件 | 数据库连接 |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis 连接 |
| `USE_CHROMA` | `true` | 是否使用 Chroma 知识检索 |
| `CHROMA_TENANT` / `CHROMA_DATABASE` | `default_tenant` / `default_database` | Chroma v2 租户和数据库 |
| `MCP_EMAIL_MODE` | `log` | `log`、`smtp`、`http` 或 `mcp` |

更多模型、MySQL、Chroma、SMTP 和 MCP 参数见本文档后续同名章节。不要将 API Key 或真实密码提交到代码仓库。

### 6. 启动验证

应用启动后，访问：

```text
http://localhost:8080
```

检查健康状态：

```bash
curl http://localhost:8080/actuator/health
```

预期返回包含 `"status":"UP"` 的 JSON。页面左上角会显示当前模型模式；如果 DeepSeek Key 无效或接口不可达，聊天接口会提示模型连接失败。

首次启动会创建两个演示账号：

```text
admin / admin123
student / student123
```

> 上述账号和 Docker Compose 中的数据库密码仅适用于本地演示。对外部署前，需在 `DataInitializer.java` 中替换演示账号机制，修改 Compose 数据库口令，并根据实际需求收紧网络端口和访问权限。

### 7. 常见问题

- 启动时报 `DEEPSEEK_API_KEY` 缺失：在终端或未提交的 `.env` 中设置密钥。
- DeepSeek 返回 401 或模型错误：核对 API Key、账户状态和 `DEEPSEEK_MODEL`。
- `Address already in use`：8080 端口被占用，可使用 `SERVER_PORT=8090 ./scripts/run-dev.sh` 更换端口。
- Docker 中无法访问 DeepSeek：检查容器出站网络以及 `DEEPSEEK_BASE_URL`。
- MySQL 或 Chroma 启动较慢：先用 `docker compose ps` 检查依赖服务，再用 `docker compose restart app` 重启应用。

## 调用示例

```bash
curl -N -u student:student123 \
  -H 'Content-Type: application/json' \
  -d '{"message":"我最近很焦虑，晚上总是睡不着"}' \
  http://localhost:8080/api/chat/stream
```

高风险示例会触发报告、Excel 写入和预警：

```bash
curl -N -u student:student123 \
  -H 'Content-Type: application/json' \
  -d '{"message":"我不想活了，感觉撑不下去了"}' \
  http://localhost:8080/api/chat/stream
```

管理员查看后台报告：

```bash
curl -u admin:admin123 http://localhost:8080/api/admin/reports
```

查看当前是否接入真实大模型：

```bash
curl -u student:student123 http://localhost:8080/api/agent/status
```

查看当前学生账号的画像记忆：

```bash
curl -u student:student123 http://localhost:8080/api/profile/memory
```

管理员追加知识库：

默认内置知识库位于 `src/main/resources/knowledge/`，启动时会按来源文件补齐到 RAG 库中；当前包含校园心理健康、风险策略、焦虑睡眠、低落社交、学业压力、校园求助、人际家庭冲突、危机安全计划和隐私边界等 Markdown 文档。

```bash
curl -u admin:admin123 \
  -H 'Content-Type: application/json' \
  -d '{"source":"sleep-guide","content":"失眠时可先固定起床时间，减少睡前屏幕刺激，必要时联系校心理中心。"}' \
  http://localhost:8080/api/admin/knowledge
```

## 接入 DeepSeek API

设置 `DEEPSEEK_API_KEY` 后直接运行 `./scripts/run-dev.sh`；聊天与流式输出共用 `deepseek-flash`。项目沿用 Spring AI 的 OpenAI 兼容客户端，并默认指定非思考模式，以适配当前单次回复的 token 上限。可通过 `DEEPSEEK_MODEL` 切换 DeepSeek 支持的模型；无需下载 Qwen 或启动 Ollama。

DeepSeek 这里只提供聊天能力。知识库 Embedding 仍由 `OPENAI_API_KEY`、`OPENAI_EMBEDDING_MODEL` 等参数独立控制；未设置时使用本地关键词检索。用户画像 Embedding 默认关闭，只有显式配置专用密钥时才会发送画像文本。

## 接入 OpenAI

```bash
cd MindBridge
AI_PROVIDER=openai \
OPENAI_API_KEY=你的_API_Key \
OPENAI_MODEL=gpt-4o-mini \
JAVA_HOME="$PWD/.tools/amazon-corretto-17.jdk/Contents/Home" \
  .tools/apache-maven-3.9.9/bin/mvn -Dmaven.repo.local=.m2/repository spring-boot:run
```

## 使用 MySQL、Chroma、SMTP

启动依赖：

```bash
docker compose up -d mysql redis chroma mailpit
```

使用 MySQL profile：

```bash
DEEPSEEK_API_KEY=你的_API_Key \
USE_CHROMA=true \
MEMORY_USE_CHROMA=true \
MCP_EMAIL_MODE=smtp \
ALERT_MAIL_RECIPIENTS=counselor@example.com \
mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```

配置了相应向量化服务后可使用两个 Chroma collection：

- `mindbridge_knowledge`：RAG 知识库切块检索。
- `mindbridge_user_memory`：用户画像/偏好长期语义记忆召回；默认关闭画像向量化，改用关系库最近记忆召回。显式开启需设置 `MEMORY_EMBEDDING_ENABLED=true` 和独立的 `MEMORY_EMBEDDING_API_KEY`，画像文本随后会发送到所配置的 Embedding 服务及 Chroma。

Mailpit 管理页面：`http://localhost:8025`

## 重建 Chroma 索引

知识库切块和用户画像仍以关系库为主数据。以下命令只用稳定 ID 向 Chroma 重放向量，不删除或改写关系库记录；失败时命令报错，可直接重试。Chroma 不可用时，在线检索沿用已有的数据库降级路径。

```bash
docker compose run --rm --no-deps app \
  --spring.main.web-application-type=none \
  --rebuild-index=all
```

先确保 MySQL、Redis、Chroma 已启动，且 `.env` 配有 `DEEPSEEK_API_KEY`。可将 `all` 换成 `knowledge` 或 `memory`。知识库只重放数据库中已有且维度匹配的向量，缺失向量会计入 `knowledgeSkipped`；画像索引只有同时设置 `MEMORY_USE_CHROMA=true`、`MEMORY_EMBEDDING_ENABLED=true` 和专用 `MEMORY_EMBEDDING_API_KEY` 才会重建。`all` 遇到画像向量化关闭会跳过画像，显式指定 `memory` 时会报错。该命令使用 upsert 修复/恢复索引，不清理 Chroma 中已无对应关系库记录的旧条目。

## MCP 工具模式

Excel 工具：

- `MCP_EXCEL_MODE=local`：默认写入 `./data/mindbridge-reports.xlsx`
- `MCP_EXCEL_MODE=http`：调用 `MCP_EXCEL_URL/write`
- `MCP_EXCEL_MODE=mcp`：通过标准 Model Context Protocol Client 调用 MCP Server 暴露的 `mindbridge_write_excel_report` 工具

邮件工具：

- `MCP_EMAIL_MODE=log`：默认只记录日志，便于本地演示
- `MCP_EMAIL_MODE=smtp`：使用 Spring Mail 发送
- `MCP_EMAIL_MODE=http`：调用 `MCP_EMAIL_URL/send`
- `MCP_EMAIL_MODE=mcp`：通过标准 Model Context Protocol Client 调用 MCP Server 暴露的 `mindbridge_send_risk_alert` 工具

标准 MCP：

- `MCP_SERVER_ENABLED=true`：启用 Spring AI MCP WebFlux Server，默认 SSE 端点为 `/sse`，消息端点为 `/mcp/messages`
- `MCP_CLIENT_ENABLED=true`：启用 Spring AI MCP WebFlux Client，默认连接 `MCP_SERVER_URL`
- `MCP_EMAIL_SERVER_DELIVERY_MODE=log|smtp`：MCP Server 收到邮件工具调用后的实际投递方式

高风险链路按文档实现为：写入报告 -> 写入 Excel -> Excel 成功后发送预警 -> 更新状态。

## RAGAS 评测

项目使用 RAGAS 做 RAG 质量评测。Java 主工程不引入 RAGAS 依赖，只负责执行检索和单次 RAG 回答生成，导出 RAGAS 输入报告；RAGAS 作为 `eval/` 目录下的可选 Python 工具运行。

Java 输入报告包含每条样本的：

- `question`：用户问题
- `answer`：模型最终回答
- `retrievedContexts`：RAG 检索到的上下文
- `referenceAnswer`：参考答案
- `retrievedSources`、`expectedIntent`、`expectedRiskLevel`：用于人工分析的元数据

运行评测：

```bash
SPRING_MAIN_WEB_APPLICATION_TYPE=none \
DEEPSEEK_API_KEY=你的_API_Key \
USE_CHROMA=false \
RAG_EVAL_ENABLED=true \
RAG_EVAL_EXIT_AFTER_RUN=true \
DB_URL='jdbc:h2:mem:mindbridge-rag-eval;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1' \
JAVA_HOME="$PWD/.tools/amazon-corretto-17.jdk/Contents/Home" \
  .tools/apache-maven-3.9.9/bin/mvn -Dmaven.repo.local=.m2/repository spring-boot:run
```

默认评测集：`src/main/resources/rag-eval/mindbridge-rag-eval.json`

默认评测集包含 100 条人工整理样本，覆盖全部 9 个内置知识主题，并按风险分层为 45 条 LOW、40 条 MEDIUM、15 条 HIGH。完整运行会对每条样本执行检索和回答生成，因此相比早期 10 条 smoke set 会消耗更多模型调用时间；调试链路时可通过 `RAG_EVAL_DATASET` 指向更小的自定义数据集。

默认 Java 输入报告：`target/rag-eval-report.json`

评测集中的每条样本包含：

- `question`：待检索问题
- `expectedSources`：应该命中的知识库来源文件
- `expectedTerms`：人工分析检索命中的辅助关键词
- `referenceAnswer`：RAGAS 使用的参考答案
- `expectedIntent` / `expectedRiskLevel`：路由和风险分级期望，作为 RAGAS 输出里的元数据保留

`eval/run-ragas-eval.py` 读取 `target/rag-eval-report.json` 后计算：

- `LLMContextPrecisionWithReference`：检索片段排序是否把相关内容排在前面
- `LLMContextRecall`：检索内容是否覆盖参考答案需要的信息
- `ResponseRelevancy`：回答是否切题
- `Faithfulness`：回答中的事实是否能被检索上下文支持
- `FactualCorrectness`：若当前 RAGAS 版本支持，则对比参考答案检查事实正确性

安装 RAGAS 依赖：

```bash
python3 -m pip install -r eval/requirements-ragas.txt
```

使用 OpenAI 评审模型：

```bash
OPENAI_API_KEY=... \
python3 eval/run-ragas-eval.py \
  --provider openai \
  --input target/rag-eval-report.json \
  --output target/ragas-report.json
```

`eval/` 中的评审模型与主应用聊天模型独立配置；详见 `eval/README.md`。

RAGAS 输出报告：`target/ragas-report.json`
