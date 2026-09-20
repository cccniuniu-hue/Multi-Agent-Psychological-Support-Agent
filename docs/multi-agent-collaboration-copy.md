# MindBridge 多 Agent 协作讲解文案

本文件按 humanizer 的写作流程整理：先写出工作稿，再检查里面还像 AI 写作的地方，最后给出可直接使用的最终文案。最终文案可用于项目答辩、作品集介绍、README 扩写、路演讲稿或技术方案说明。

## 初稿

MindBridge 是一个面向校园心理健康场景的智能体系统。项目采用多 Agent 协作，把一轮学生输入拆成多个环节处理：先读取记忆，再判断意图，随后根据场景决定是否检索知识库、是否进行风险评估，最后由对应的回复 Agent 生成学生端回答。这种设计让系统在普通聊天、心理咨询和高风险求助之间保持清晰边界。

在代码实现上，`AgentRuntimeService` 是多 Agent loop 的调度中心。它把 `MemoryAgent`、`SupervisorAgent`、`KnowledgeAgent`、`RiskGuardianAgent`、`CompanionAgent` 和 `CounselorAgent` 放入固定顺序，并通过 `AgentContext` 传递上下文状态。每个 Agent 通过 `supports(context)` 判断自己是否应该执行，通过 `act(context)` 完成本步任务，再返回 `AgentDecision`。整个 loop 最多执行 8 步，避免心理安全场景中出现不可控的无限循环。

项目中的多 Agent 协作不是简单的多模型堆叠。多个 Agent 可以共用同一个项目微调模型，但它们的 prompt、职责和工具权限不同。MemoryAgent 只负责准备记忆，SupervisorAgent 只负责路由，KnowledgeAgent 只在咨询和风险场景下检索知识，RiskGuardianAgent 只负责后台风险判断，最终回复则由 CompanionAgent 或 CounselorAgent 接手。

普通聊天会走轻量路径：MemoryAgent 读取上下文，SupervisorAgent 判断为 CHAT，随后 CompanionAgent 直接规划普通回复。咨询和风险输入会走更完整的路径：KnowledgeAgent 改写检索 query 并执行 RAG，RiskGuardianAgent 结合词库、模型 JSON 和关键词兜底做后台评估，CounselorAgent 再把记忆、知识库命中和风险守护结果组合成回复策略。高风险场景还会生成报告，写入 Excel，并按配置触发预警。

这套架构的价值在于让系统更可控、更透明、更适合校园心理支持场景。学生端看到的是一次自然的对话，后台则保留了每一步 agent 的运行轨迹、意图、风险等级、知识 query 和回复计划，方便管理员审计和排查。

## 初稿里还像 AI 的地方

- 有些句子太像架构说明书，例如“更可控、更透明、更适合”，虽然准确，但节奏有点整齐。
- “价值在于”这种说法偏总结式，读起来像宣传收尾，缺少一点项目里的具体落点。
- 初稿对每个 Agent 的说明还不够细，尤其是记忆、RAG、风险兜底、工具链和 trace 落库之间的关系还可以讲得更实。
- 初稿把“多 Agent”说成一种架构优点，但没有充分解释为什么心理健康场景不能只靠一个大 prompt。

## 最终文案

### 一句话介绍

MindBridge 的多 Agent 协作可以用一句话概括：系统不会把一轮学生输入直接丢给一个“大而全”的 prompt，而是先让不同 Agent 分别完成记忆读取、意图判断、知识检索、风险守护和回复规划。学生端看到的是一次连贯的聊天，后台跑的是一条有边界、有记录、能降级的协作链路。

这个设计很适合校园心理健康场景。因为这里的问题不只是“回答得像不像人”，还包括几个更现实的要求：普通学习问题不能被误判成心理咨询，真正的风险信号不能漏掉，心理支持回答不能随口诊断，后台还要能留下足够清楚的处理记录，方便辅导员或管理员回看。

MindBridge 选择多 Agent，并不是为了制造复杂度。它解决的是单 Agent 在心理安全场景里很难同时做好的几件事：既要自然聊天，又要识别风险；既要引用知识库，又不能把每句话都强行心理学化；既要保护隐私，又要保留必要的审计线索。把这些工作拆开后，每个 Agent 只负责一小段，系统整体反而更稳。

### 为什么本项目需要多 Agent

如果只用一个大 prompt，系统会很容易陷入两个问题。

第一，边界会变模糊。学生问“帮我解释 Java 线程池怎么写”，这就是普通学习或编程问题。如果系统因为项目是心理健康主题，就主动追问“你是不是压力很大”，体验会很奇怪。MindBridge 在 `IntentClassifier` 里专门加入普通任务词，包括 Java、Python、作业、论文、项目、接口、bug 等。只要没有明显心理痛苦或危险信号，这类请求会留在 CHAT 路由，不进入后台心理评估。

第二，风险判断不能完全交给自由生成模型。心理健康场景里，模型一句“看起来还好”并不够。项目在 `RiskLexicon` 中维护高风险词库，明确的自伤、轻生、伤人表达会优先触发 RISK 路由。后面 `PsychologicalAssessmentService` 还会把词库、模型结构化 JSON 和关键词兜底结合起来。模型能参与判断，但它不是唯一防线。

第三，系统需要可追踪。MindBridge 不是只生成一段回复就结束。每一轮对话都会生成 `AgentRunTrace`，记录本轮由哪些 Agent 执行、每一步做了什么、路由结果是什么、风险等级是什么、知识库 query 是什么、最后由哪个回复 Agent 接手。这些信息不会展示给学生，但管理员后台可以查看。对于心理支持系统来说，这种可审计性比“回答看起来很聪明”更重要。

所以，MindBridge 的多 Agent 协作不是几个角色在聊天，也不是多个模型互相投票。它更像一条有状态的处理流水线：每个 Agent 只处理自己应该处理的部分，然后把结构化结果交给下一步。

### 整体协作流程

一轮学生输入进入系统后，首先到达 `ChatController` 的 `/api/chat/stream` 接口。这个接口只允许学生账号发起对话，管理员账号只能查看后台记录，不能模拟学生聊天。随后 `ChatService` 开始准备本轮会话：修剪输入、做隐私脱敏、解析或创建会话，再调用 `AgentRuntimeService` 运行多 Agent loop。

Agent loop 的固定顺序是：

```text
MemoryAgent
-> SupervisorAgent
-> KnowledgeAgent
-> RiskGuardianAgent
-> CompanionAgent / CounselorAgent
```

这条链路最多执行 8 步。这个限制写在 `AgentRuntimeService.MAX_STEPS` 中。它的意义很直接：系统不能在心理安全场景里无限自主循环，不能让 Agent 一直自我规划、自我调用。每一轮输入都必须在有限步内形成明确结果。

调度方式也很克制。`AgentRuntimeService` 不让模型决定“下一个 Agent 是谁”，而是从固定列表里按顺序找第一个 `supports(context)` 返回 true 的 Agent。也就是说，下一步由代码状态决定，不由模型自由发挥决定。

每个 Agent 执行后会返回一个 `AgentDecision`，里面包含三类信息：

- `action`：本步做了什么，例如读取记忆、路由意图、检索知识、评估风险、规划回复。
- `observation`：给后台看的简短说明，例如加载了多少条记忆、检索 query 是什么、风险等级是什么。
- `complete`：本轮 Agent loop 是否结束。

这些决策会被转换成 `AgentStep`，最后随 `AgentRunResult` 一起交给 `AgentRunTraceService` 保存。这样，系统既能对学生流式输出，也能在后台保留完整处理轨迹。

### AgentContext 是协作的共享黑板

多 Agent 协作要可靠，不能只靠自然语言互相传话。MindBridge 使用 `AgentContext` 作为一轮对话里的共享状态对象。它保存原始输入、脱敏后的模型输入、历史消息、记忆摘要、意图、检索结果、心理评估、风险等级、回复策略、最终 prompt 消息和执行步骤。

这里有一个很细的设计：`originalInput` 和 `modelInput` 是分开的。原始输入用于会话落库和必要的后台报告，模型侧则使用 `PrivacySanitizer` 处理后的文本。这样可以在保留审计线索的同时，减少模型调用中的敏感信息暴露。

`AgentContext` 还有一组状态位：

- `memoryLoaded`：记忆是否准备好。
- `intentRouted`：意图是否已经路由。
- `knowledgeHandled`：知识库是否处理完成。
- `riskAssessed`：风险是否评估完成。
- `responsePlanned`：回复策略是否规划完成。
- `finished`：本轮 loop 是否结束。

这些状态位就是整个 agent loop 的轨道。Agent 不需要猜前面发生了什么，也不需要读一大段自然语言日志。它只看上下文状态，判断自己能不能接手。这让协作变得清楚，也让测试更容易写。

### MemoryAgent：先把“这个学生是谁”和“刚才聊到哪”准备好

`MemoryAgent` 是每轮对话的第一步。它做的事情不显眼，但很重要。心理支持不是一次性问答，学生可能前几轮已经说过自己的睡眠、学业压力、沟通偏好或求助方式。如果每轮都像第一次见面，回答就会显得生硬。

MemoryAgent 会优先读取 Redis 短期记忆。Redis 里按会话保存最近 N 轮用户和助手消息，默认 TTL 是 24 小时。如果 Redis 里没有记录，说明短期记忆可能过期或没有启动 Redis，系统会从 MySQL 的长期聊天记录里恢复最近上下文，并刷新回 Redis。

除了聊天历史，它还会调用 `UserProfileMemoryService` 召回用户画像。用户画像不是把整段聊天都塞进 prompt，而是从对话中抽取相对稳定的信息，例如用户偏好、沟通方式、支持需求、个人背景和反复出现的状态模式。项目里还做了几层限制：不保存手机号、学号、证件号、真实姓名、详细地址，不保存诊断结论，也不保存风险等级。

用户画像既写入关系库，方便审计和删除，也可以镜像到 Chroma 做语义召回。`profileBrief` 默认最多取 8 条与当前输入相关的画像记忆。这样后续 Agent 拿到的不是杂乱聊天记录，而是一段短而有用的记忆摘要。

MemoryAgent 最后会把历史消息和当前输入组合成 `modelHistory`，同时生成 `memoryBrief`。后面的 SupervisorAgent、KnowledgeAgent、CompanionAgent 和 CounselorAgent 都会使用这份上下文。

### SupervisorAgent：先判断这轮到底是什么场景

`SupervisorAgent` 是路由 Agent。它不回答学生问题，也不做心理评估，只负责把输入分到 `CHAT`、`CONSULT` 或 `RISK`。

这一步看起来简单，其实决定了后面整条链路的成本和安全策略。

如果是 `CHAT`，系统会跳过知识检索和风险评估，直接走 CompanionAgent。普通问候、课程学习、编程、作业、项目、论文、校园事务等内容都应该保留在这条路径里。这样可以避免系统把普通问题强行解释成心理困扰。

如果是 `CONSULT`，说明用户明确表达了焦虑、低落、失眠、压力、无助等心理求助内容。这时系统会继续进入知识检索和风险评估，再由 CounselorAgent 生成心理支持式回复。

如果是 `RISK`，说明出现了自伤、自杀、伤人、严重绝望或即时危险信号。这个路由优先级最高。项目在分类前先跑高风险词库，命中后直接进入 RISK，不等模型慢慢判断。

SupervisorAgent 使用 `IntentClassifier` 完成分类。IntentClassifier 的策略不是纯模型调用，而是“硬规则优先，模型补充，关键词兜底”。明确高风险表达优先归为 RISK；明确普通任务优先归为 CHAT；剩下的再交给模型按 prompt 输出标签。如果模型调用失败，系统还会根据咨询词和最近咨询上下文兜底判断。

这种路由设计有一个好处：它减少了不必要的 RAG 和心理评估，也降低了普通问题被误导的概率。

### KnowledgeAgent：只在需要时检索知识库

`KnowledgeAgent` 不是每轮都执行。只有当意图已经路由，并且当前不是 CHAT 时，它才会接手。也就是说，普通闲聊、编程问题、课程问题不会被强制检索心理知识库。

在咨询或风险场景中，KnowledgeAgent 先把学生输入改写成适合检索的中文 query。这个 query 不超过 40 个字，聚焦心理支持、校园求助流程、风险处理或情绪调节知识。比如用户说“我最近焦虑得睡不着，考试越来越近了”，检索 query 可能会变成“考试焦虑 失眠 情绪调节 校园求助”。

随后它调用 `KnowledgeService.retrieve(query, topK)`。KnowledgeService 的检索不是单一路线。它会优先使用 Chroma 向量检索，如果 Chroma 不可用或没有结果，就尝试基于本地 embedding 的相似度检索；同时还会使用 BM25 做关键词排序。项目把向量得分和 BM25 得分合并，默认权重是 0.65 和 0.35，再交给 reranker 做二阶段排序。

检索到候选片段后，KnowledgeAgent 还会判断结果是否足够支持回答。如果结果不足，它会要求模型给出一个更具体的新 query，再检索一次。这个二次检索不是为了炫技，而是为了处理心理场景里常见的模糊表达。学生经常不会直接说“我需要危机安全计划”，而是说“我今晚有点撑不住”。系统需要把这种表达转成更适合知识库的查询。

KnowledgeService 还有一个上下文扩展细节：命中片段会补上前后相邻 chunk，减少切块边界导致的信息断裂。知识库默认 chunk size 是 512，overlap 是 64。最终传给 CounselorAgent 的不是原始文档全量内容，而是和当前问题相关的几段材料。

### RiskGuardianAgent：风险判断不能只靠模型口感

`RiskGuardianAgent` 是咨询和风险链路里的安全守门员。它只在 KnowledgeAgent 处理完成后执行，并且 CHAT 路径不会进入这一步。

它调用 `PsychologicalAssessmentService.assess(modelInput, modelHistory)`，生成后台心理评估结果，包括 emotion、emotionScore、risk、confidence 和 summary。这些字段用于报告和工具链，不会直接展示给学生。

项目对风险评估做了三层保护。

第一层是高风险词库。只要输入里出现明确的自伤、自杀、轻生、伤害自己、伤害他人等表达，服务会直接返回 HIGH 风险，并给出较高置信度。这一步在模型调用前执行。

第二层是模型结构化输出。Prompt 要求模型只返回严格 JSON，字段固定，情绪标签限定为 NORMAL、ANXIETY、DEPRESSED、HIGH_RISK，风险等级限定为 LOW、MEDIUM、HIGH。服务端会解析 JSON，并根据情绪分数重新校正风险等级。如果 emotion 是 HIGH_RISK，最终风险一定是 HIGH。

第三层是关键词兜底。如果模型输出不是 JSON，或者调用失败，服务不会让整条链路断掉，而是用关键词启发式判断。比如低落、崩溃、depress、hopeless 会倾向 MEDIUM；焦虑、压力、睡不着、insomnia 会倾向 LOW。

还有一个专门针对 RISK 路由的保护：如果 SupervisorAgent 已经判断为 RISK，而模型评估结果没有给 HIGH，RiskGuardianAgent 会把风险等级抬到 HIGH。这样可以减少模型低估风险的情况。

这就是为什么项目里叫 RiskGuardian，而不只是 RiskClassifier。它不是单纯分类，而是在模型判断之外加了一道工程化的安全边界。

### CompanionAgent：让普通聊天保持普通

`CompanionAgent` 处理 CHAT 路由。它的任务是给普通学习、生活、校园事务、编程和日常聊天生成回复策略。

这个 Agent 很容易被忽略，但它对用户体验很重要。一个校园心理健康系统如果每次普通提问都带着咨询腔，会让人不自在。CompanionAgent 的 prompt 明确要求：不要做心理评估，不要输出风险等级，不要替用户下诊断，不要把普通聊天强行引导成心理咨询。

在实现上，CompanionAgent 会把风险等级设为 LOW，把 `responseAgent` 设为 `COMPANION_AGENT`，再生成一句简短回复策略。最后它通过 `PromptTemplates.answerSystemPrompt` 构造 CHAT 模式下的系统消息，并把历史消息一起交给最终流式模型。

也就是说，普通聊天仍然由项目模型回答，但回答前已经有一个轻量规划步骤。这个步骤让系统知道：当前不是心理咨询，不要把背景主题扩大化。

### CounselorAgent：心理支持回答由它收口

`CounselorAgent` 处理 CONSULT 和 RISK 路由。它接手时，前面已经完成了记忆读取、意图路由、知识检索和风险评估。

CounselorAgent 不直接给诊断，也不把后台风险等级告诉学生。它会结合几类信息制定回复策略：

- MemoryAgent 准备的用户画像和最近对话记忆。
- KnowledgeAgent 检索到的知识库片段和 query。
- RiskGuardianAgent 生成的后台评估摘要。
- 当前用户输入和最近上下文。

它的回复策略通常遵循一个比较朴素的顺序：先回应情绪，再给出具体支持步骤；如果是高风险，先把重点放在当前安全上。最终系统 prompt 也明确限制了输出边界：不要诊断疾病，不要开药，不要替代持证心理咨询师，不要输出后台标签、评估分数或报告口吻，不要编造知识库里没有的流程或数据。

高风险时，PromptTemplates 会额外注入安全规则：鼓励用户马上联系身边可信任的人、学校辅导员或心理中心，必要时联系当地紧急救助；不提供自伤、伤人或危险操作的细节。

CounselorAgent 的价值不在于“说得更像咨询师”，而在于把前面几个 Agent 的结果收束成一段对学生可读、可执行、不过度承诺的回复。

### 三条典型路径

普通聊天路径最短。比如学生问：“帮我解释一下 Java 多线程。”系统会执行：

```text
MemoryAgent -> SupervisorAgent -> CompanionAgent
```

MemoryAgent 准备上下文。SupervisorAgent 判断为 CHAT，并直接把 `knowledgeHandled` 和 `riskAssessed` 标记为完成。CompanionAgent 规划普通回复。整个过程不会生成心理报告，不会检索心理知识库，也不会触发预警工具。

心理咨询路径更完整。比如学生说：“我最近很焦虑，晚上总是睡不着。”系统会执行：

```text
MemoryAgent -> SupervisorAgent -> KnowledgeAgent -> RiskGuardianAgent -> CounselorAgent
```

SupervisorAgent 判断为 CONSULT。KnowledgeAgent 改写 query 并检索焦虑、睡眠、情绪调节、校园求助相关知识。RiskGuardianAgent 做后台评估。CounselorAgent 结合记忆、RAG 和评估结果生成支持式回复。因为这是咨询输入，系统会保存心理报告，但学生端不会看到风险等级和后台字段。

高风险路径和咨询路径类似，但风险优先级更高。比如学生说：“我不想活了，今晚可能撑不住。”系统会先由高风险词库命中 RISK，再继续走知识检索、风险守护和心理支持回复。后台报告会标记 HIGH 风险。模型回复完成后，工具链会先写 Excel，写入成功后再向配置的收件人发送预警。

这三条路径的区别说明了 MindBridge 多 Agent 协作的重点：不是所有输入都重处理，也不是所有输入都轻处理。系统先判断场景，再决定该走多深。

### 记忆系统如何参与协作

MindBridge 的记忆分成短期记忆和用户画像长期记忆。

短期记忆解决“刚才聊到哪”的问题。`ShortTermMemoryService` 使用 Redis list 保存最近消息，key 以 `mindbridge:chat:short-memory:` 开头。每次保存用户或助手消息时，系统都会把消息追加到 Redis，并按 `historyLimit * 2` 截断，避免上下文无限增长。Redis 读失败或为空时，系统还能从 MySQL 会话消息恢复最近记录。

长期记忆解决“这个学生有哪些稳定偏好和背景”的问题。`UserProfileMemoryService` 在每轮用户输入后尝试抽取候选记忆，只保留对后续对话稳定有帮助的信息。类型包括偏好、沟通方式、支持需求、个人背景和状态模式。候选记忆有置信度门槛，默认低于 0.55 不保存；单个用户最多保留 40 条，旧记录会被裁剪。

更重要的是，长期记忆不是黑箱。它写入关系库，可以在 `/api/profile/memory` 查看，也可以由用户删除。启用 Chroma 时，记忆会镜像到向量库，下一轮对话按当前输入语义召回。没有 Chroma 时，系统也会回退到最近记忆。

这种设计让 Agent 之间共享的是“整理过的记忆”，不是一大堆原始聊天。CounselorAgent 拿到的 memoryBrief 可以帮助它避免重复提问，也可以让回复更贴近用户的表达方式和支持需求。

### RAG 知识库如何参与协作

MindBridge 的知识库主要服务咨询和风险场景，默认内置校园心理健康、风险策略、焦虑睡眠、低落社交、学业压力、校园求助、人际家庭冲突、危机安全计划和隐私边界等 Markdown 文档。

知识入库时，`KnowledgeIngestionService` 会把文档切成 chunk，再由 `KnowledgeService` 保存到数据库。如果配置了 embedding，系统会写入向量；如果没有配置，检索仍可以通过本地兜底运行。Chroma 可作为外部向量库，但不是唯一依赖。

检索时，系统会同时考虑语义相似度和关键词匹配。语义检索适合处理“说法不一样但意思相近”的问题，BM25 适合捕捉明确词语，比如“失眠”“考试焦虑”“心理中心”。两条路线合并后再 rerank，可以减少单一路线的偏差。

RAG 在这里不是为了让回答显得资料更多，而是为了让心理支持回答有出处、有边界。CounselorAgent 会优先基于检索知识回答；如果知识不足，prompt 要求它明确说明，并给出通用、安全的支持建议。这样可以降低模型编造校园流程、咨询电话或心理学术语的风险。

### 工具链如何和 Agent 结果衔接

多 Agent loop 本身负责判断和回复规划，工具链负责后台处理。两者通过 `PsychologicalReport` 衔接。

当 `AgentRunResult.requiresReport()` 返回 true，说明本轮不是 CHAT，并且已经有心理评估结果。`ChatService` 会保存一条心理报告，里面包括学生、会话、原始内容、意图、情绪标签、情绪分数、风险等级、置信度和摘要。

学生端回复通过 SSE 流式输出。模型 token 输出结束后，`ChatService` 才会异步触发 `ToolOrchestrationService.handleAsync(reportId)`。这个顺序很重要：后台工具不阻塞学生端对话体验。

工具链的执行顺序是：

```text
写 Excel 报告
-> 如果风险为 HIGH 且 Excel 写入成功
-> 发送预警通知
```

Excel 写入可以走本地文件、HTTP 或 MCP。预警通知可以走日志、SMTP、HTTP 或 MCP。`McpToolConfig` 为这套工具链配置了独立线程池，线程名前缀是 `mindbridge-mcp-`，避免工具调用占用聊天主流程。

预警通知还有重试和落库。每个收件人会创建一条 `AlertRecord`，每次尝试都会增加 attempts，最终状态写成 SUCCESS 或 FAILED。这样管理员后台可以看到 Excel 是否写入成功、每封预警是否发送成功、失败原因是什么。

### 可观测性：每轮协作都能回看

MindBridge 把 Agent 协作过程保存为运行轨迹。`AgentRunTraceService.saveRun` 会记录一轮对话的 traceId、用户、会话、触发消息、原始输入、意图、风险等级、memoryBrief、knowledgeQuery、responsePlan、responseAgent、步骤数量、开始时间和结束时间。

每个 `AgentStep` 也会保存为 `AgentRunTraceStep`，包括 stepNumber、agent、action、observation 和 createdAt。

这让管理员可以回答几个很具体的问题：

- 这轮为什么走了心理咨询路径？
- 是否检索过知识库？
- 检索 query 是什么？
- 风险等级是谁判断出来的？
- 最终是 CompanionAgent 还是 CounselorAgent 负责回复？
- 工具链是否写入 Excel，是否发送预警？

这些问题靠最终回复本身是看不出来的。Trace 让项目从“能聊天”变成“能解释自己怎样处理了一轮聊天”。在心理健康场景里，这一点很实用。

### 与模型的关系：多个 Agent 不等于多个模型

本项目里的多个 Agent 可以共享同一个大模型客户端。默认配置使用 DeepSeek API 的 `deepseek-flash`，也可以通过配置切换到其他 OpenAI 兼容接口。项目内部通过 `AiClient` 抽象模型调用，`SpringAiChatClient` 再把内部的 `AiMessage` 转成 Spring AI 的 `SystemMessage`、`UserMessage` 和 `AssistantMessage`。

所以，多 Agent 的差异主要来自职责、上下文和 prompt，而不是一定要部署多个模型。

MemoryAgent 调用模型时，它只要求提取与当前输入有关的记忆要点。SupervisorAgent 调用模型时，它只要求输出 CHAT、CONSULT 或 RISK。KnowledgeAgent 调用模型时，它只要求改写 query 或判断检索是否足够。RiskGuardianAgent 调用模型时，它要求严格 JSON。CompanionAgent 和 CounselorAgent 调用模型时，它们分别构造普通聊天和心理支持的回复策略。

同一个模型，在不同 Agent 的约束下承担不同工作。这比把所有要求塞进一个长 prompt 更容易维护，也更容易测试。

### 安全边界和隐私处理

MindBridge 对学生端和后台做了明显区分。学生端不会看到风险等级、情绪分数、后台摘要或报告字段。心理支持回复也被限制为共情、具体建议和求助引导，不能诊断疾病、不能开药、不能输出危险细节。

隐私处理也贯穿多 Agent 链路。模型输入会先经过 `PrivacySanitizer`。MemoryAgent 从历史消息转成模型消息时也会再次脱敏。用户画像抽取时明确禁止保存手机号、学号、证件号、真实姓名、详细地址等信息。画像记忆支持用户自己查看和删除。

这套边界不会让系统变得完美，但它把几个容易出问题的地方都落在了代码里：哪些信息可以进入模型，哪些信息只能后台保存，哪些内容不能展示给学生，哪些信号必须优先处理。

### 工程上的可扩展性

如果后续要新增 Agent，项目已经留出了相对清楚的扩展方式。新增 Agent 只需要实现 `MindBridgeAgent`，定义自己的 `name()`、`supports(context)` 和 `act(context)`，再把它加入 `AgentRuntimeService` 的固定列表。

例如可以增加一个 `ResourceAgent`，专门根据学校配置推荐心理中心、辅导员、学院联系方式或预约入口。也可以增加一个 `ReflectionAgent`，在不打断回复的情况下检查 CounselorAgent 的回复是否泄露后台标签、是否包含诊断、是否遗漏高风险安全提示。

不过，新增 Agent 不应该变成随意堆叠。MindBridge 当前架构的好处在于职责边界清楚。一个新 Agent 应该满足两个条件：它有独立的状态输入和输出，它能减少某个现有 Agent 的复杂度。否则，把逻辑放进已有服务或 prompt 模板里可能更合适。

配置层也比较容易扩展。`MindBridgeProperties` 集中管理模型 provider、温度、token 上限、RAG topK、reranker、chunk size、Chroma、Redis 记忆、Excel 写入和预警方式。部署时可以选择 H2 或 MySQL，可以启用或关闭 Chroma，默认聊天走 DeepSeek API，也可以切到其他 OpenAI 兼容接口。

### 测试如何证明协作路径正确

项目里的测试不是只测单个函数。`AgentLoopHarnessTests` 直接验证三条典型路径：

- 普通编程问题会走 `MemoryAgent -> SupervisorAgent -> CompanionAgent`，不会触发 RAG 和报告。
- 焦虑失眠类咨询会走 `MemoryAgent -> SupervisorAgent -> KnowledgeAgent -> RiskGuardianAgent -> CounselorAgent`，会检索知识库并生成报告。
- 明确自伤表达会进入 RISK，并被提升为 HIGH 风险，最终仍由 CounselorAgent 走心理支持路径。

`SafetyRiskHarnessTests` 则验证高风险词库、普通学习任务路由、咨询信号、模型 JSON 异常后的关键词兜底，以及高风险 prompt 中禁止危险细节、鼓励联系可信任的人和紧急救助等规则。

这些测试说明，多 Agent 协作不是停留在文档里的概念。项目用测试把“普通问题不要误伤”“风险问题不要漏掉”“模型失败要有兜底”这些要求固定了下来。

### 一个完整例子

假设学生输入：“我最近焦虑到心慌，晚上也睡不着，考试快到了，感觉撑不住。”

第一步，ChatService 会保存会话上下文，并把模型输入做脱敏处理。然后 AgentRuntimeService 创建 AgentContext。

第二步，MemoryAgent 读取 Redis 里的最近聊天。如果没有，就从 MySQL 恢复最近记录。它还会召回与当前输入相关的用户画像，比如“用户在考试前容易失眠”或“用户希望回答直接给步骤”。这些内容会变成 memoryBrief。

第三步，SupervisorAgent 调用 IntentClassifier。输入中有焦虑、心慌、睡不着、撑不住等心理求助信号。若没有明确自伤词，通常会进入 CONSULT；如果出现明确自伤或轻生表达，则进入 RISK。

第四步，KnowledgeAgent 把输入改写成知识库 query，比如“考试焦虑 失眠 心慌 情绪调节 校园求助”。它检索内置心理知识库，拿到焦虑、睡眠、五感着陆、校园心理中心资源等片段。如果检索结果不足，它会换一个更具体的 query 再查一次。

第五步，RiskGuardianAgent 评估后台风险。它会结合当前输入和最近上下文，输出 emotion、emotionScore、risk、confidence 和 summary。如果模型输出异常，关键词兜底会继续给出可用评估。

第六步，CounselorAgent 生成回复策略。它不会把“你是 LOW 风险”或“你被评估为焦虑”说给学生听，而是把重点放在当下可以做什么：先承认对方确实很难受，再建议降低今晚目标、做短时间呼吸或五感着陆、减少睡前刺激、联系可信任同学或学校心理中心。高风险时，回复会更明确地引导对方马上找身边的人和紧急帮助。

第七步，最终模型通过 SSE 流式输出回答。学生看到的是自然的逐字回复。后台同时保存 trace 和报告；如果风险为 HIGH，工具链在回复完成后异步写 Excel 并发送预警。

这个例子能看出，多 Agent 协作的重点不是让系统“多想几遍”，而是让每一步想的东西不同：记忆负责背景，路由负责分流，知识库负责依据，风险守护负责安全，回复 Agent 负责把这些结果变成学生听得懂的话。

### 项目亮点可以这样表达

如果要在答辩或展示里讲项目亮点，可以把重点放在下面几句话上。

MindBridge 把校园心理健康对话拆成一个有限步多 Agent loop。每轮输入最多执行 8 步，调度由代码状态控制，不让模型无限自主调用。

系统区分 CHAT、CONSULT 和 RISK。普通学习、编程、校园事务不会被强行心理化；咨询和风险输入才进入 RAG、风险评估和报告链路。

风险判断有硬规则兜底。明确高风险表达优先进入 RISK，模型 JSON 解析失败时还有关键词启发式判断，RISK 路由会被保护性提升为 HIGH。

RAG 只在需要时参与。KnowledgeAgent 会改写 query、检索知识库、判断结果是否足够，并在不足时二次检索。KnowledgeService 同时使用向量检索、BM25、reranker 和相邻 chunk 扩展。

记忆分层处理。Redis 保存短期会话上下文，MySQL 保存完整聊天记录，用户画像长期记忆可审计、可删除，并可通过 Chroma 做语义召回。

后台工具链不阻塞聊天。学生端通过 SSE 先拿到回复，心理报告、Excel 写入和高风险预警在回复后异步执行。

协作过程可回看。每轮 Agent 的执行步骤、动作、观察结果、风险等级、知识 query 和回复计划都会落库，管理员可以在后台追踪处理过程。

### 收束版总结

MindBridge 的多 Agent 协作，本质上是在心理健康对话里加入一套明确的工程秩序。它没有把希望全压在一个模型回答上，而是让系统先准备记忆，再判断场景，再决定是否检索知识和评估风险，最后才组织学生端回复。

这样做的好处很实际。普通问题可以保持普通，咨询问题能得到知识库支持，高风险问题会进入更谨慎的处理链路。学生不会看到后台标签和报告口吻，管理员却能回看每一步怎么发生。对校园心理支持系统来说，这比单纯追求回答流畅更可靠。

如果要用一句更适合展示的话来结束，可以这样说：

MindBridge 的多 Agent 不是为了让系统显得复杂，而是为了让每一次心理支持对话都能被分流、被约束、被记录，也能在真正需要时被及时接住。

## humanizer 修改摘要

最终稿去掉了初稿里偏宣传的表达，把“多 Agent 很有价值”改成了“代码里具体怎么分工、怎么兜底、怎么落库”。同时避免使用夸张形容词、空泛趋势判断、机械三段式总结和聊天式套话。全文未使用 em dash 或 en dash。
