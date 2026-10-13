# 实施交付记录

记录日期：2026-10-13（Asia/Shanghai）。本文件汇总 2026-09-14 至 2026-10-13 的代码改造、最终静态验收和已有提交，用于交付回溯，不是后续开发计划。

## 交付范围

| 模块 | 当前实现 |
| --- | --- |
| 模型与向量 | 默认 DeepSeek 聊天；知识与画像使用独立 Embedding 配置；画像向量化默认关闭；Chroma v2 显式向量、模型/维度过滤和重放入口 |
| 文档入库 | 统一解析状态与来源；Marker PDF/DOCX；Markdown AST；章节/段落、表格和代码切块；Qwen2-VL 图片描述关联、回填与独立图片块 |
| 检索 | 默认向量与 BM25 各 Top50，RRF/可选 weighted，独立 BGE Top50→Top5，服务失败退回本地或初排 |
| Agent 与回答 | 原问题优先，复杂咨询最多一次补检索，严格证据判定，3/5 步固定状态路由，trace，回答来源与证据不足提示，文档指令隔离 |
| 分层记忆 | Redis 最近对话与阶段摘要，关系库历史回退，画像按轮次/重要变化/会话结束更新、去重和进度落库，用户隔离与删除确认、规则脱敏 |
| 质量入口 | RAGAS 四项核心指标、合成样例、免服务输入检查、失败/覆盖统计、模型和版本记录，以及 GitHub 编译/语法检查流程 |

架构、默认值与降级说明见 [README](../README.md) 和 [RAG 技术说明](rag-technical-guide.md)；评估命令见 [RAGAS 工具说明](../eval/README.md)。内部包名、数据库名和历史模型目录继续保留 MindBridge 名称，不影响 README 中的公开项目名。

## 最终验收结果

本次检查的应用代码基线为 `9d892149d8f91be5c68c02e3541df8c365845ac1`。2026-10-13 的后续改动为文档校对、交付记录及已完成计划的清理，不修改业务代码或服务配置。

| 检查 | 实际结果 |
| --- | --- |
| Java 17 / Maven 3.9.9 `compile` | BUILD SUCCESS；javac 编译 125 个 Java 源文件 |
| 全部 Git 跟踪 Python 源码 `py_compile` | 17 个文件通过 |
| RAGAS 工具免服务检查 | 14 项通过；合成输入检查为 3 条输入、2 条可评估、1 条空上下文跳过，不写分数 |
| Marker 上传限制契约 | 3 项通过 |
| Qwen2-VL 契约与模拟客户端 | 6 项通过 |
| BGE 契约与模拟客户端 | 4 项通过 |
| 前端 `app.js` 语法 / 会话结束测试 | `node --check` 通过；4 项测试通过 |
| Bash `-n` | 6 个跟踪脚本语法通过，不执行脚本内容 |
| 配置格式 | 4 份 YAML、3 份 JSON 与 pom.xml XML 解析通过；.env.example 赋值格式通过 |
| 默认值核对 | DeepSeek / deepseek-flash、画像 Embedding 默认关闭保留 |
| 文档检查 | 检查本地链接、代码围栏及旧架构描述；提交前检查差异与敏感信息 |

未执行 Java 全量测试、应用启动、Docker 构建、真实数据库/模型/索引/通知服务联通，也未运行真实 RAGAS 评分或提示注入对抗评测。Python 服务检查使用标准库和模拟客户端，不加载模型权重；配置解析不等于 Docker Compose 部署验证。

本次验收环境为 Windows、JDK 17、Python 3.11。使用现有本地依赖，不为完成检查安装模型或拉起外部服务。

在仓库根目录可复查以下免模型入口：

```powershell
& ./.tools/apache-maven-3.9.9/bin/mvn.cmd --batch-mode --no-transfer-progress '-Dmaven.repo.local=.m2/repository' -DskipTests compile
python -m unittest discover -s eval -p 'test_ragas_eval.py' -v
python -m unittest discover -s services/marker -p 'test_*.py' -v
python -m unittest discover -s services/qwen2vl -p 'test_*.py' -v
python -m unittest discover -s services/reranker -p 'test_*.py' -v
node --check src/main/resources/static/app.js
node --test src/test/js/chat-session-end.test.cjs
```

每个 Python 服务的测试单独启动进程，避免各目录中同名 `contract` / `service` 模块混用。没有附带 Maven 时改用系统 `mvn`；依赖未缓存的机器仍需下载构建依赖。

## 已知限制

- 关系库与 Chroma 不构成分布式事务；知识入库时外部删除/写入失败可能留下旧记录或缺失记录。重放只修复已有主数据的索引，不清理孤立记录，也不替换缺失或旧模型的知识向量。
- 结构化切块使用 token 估算，长表格行、代码行和图片路径可能超预算；图片描述和答案引用需真实模型评测及人工核对。
- 默认演示账号、H2 控制台和 Compose 演示口令仍需在对外部署前处理。模型外发数据和规则脱敏的实际覆盖范围需结合使用场景确认。
- 本次没有性能、质量分数或服务可用性的实测结论；`complete` 评估状态仅表示评分完整，不能作为质量达标证据。

## 提交索引

以下按 Git 真实提交日期列出从基线 `8ff53e2` 之后至 `49bc13d` 的 57 个已有提交，包含功能、文档与执行规则调整；补做任务保留实际日期，提交标题保留历史原文。

| 日期 | Commit | 改动 |
| --- | --- | --- |
| 2026-09-14 | [4501db7](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/4501db71afa7856f4f5c2742560a7bed2218331c) | 添加30天改造与每日推送计划 |
| 2026-09-14 | [6cbc4ab](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/6cbc4ab7e18afd3669dbf587f4fc680e90bbedcb) | 添加每日编译与Python语法检查 |
| 2026-09-15 | [60f9b20](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/60f9b20951680e41c7ec3f8b8a87bf71dca70498) | 迁移知识库Chroma网关到v2接口 |
| 2026-09-16 | [b631c44](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/b631c44cdab27dd914501472d65ca9e5e2992851) | 迁移用户画像Chroma网关到v2接口 |
| 2026-09-17 | [f815589](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/f8155896010615e22b1790d4f87de162ea3adfbe) | 增加Embedding维度配置与校验 |
| 2026-09-18 | [7e30cc2](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/7e30cc2147c0524c621f88a7b147aeead5dd1125) | 统一知识库Embedding向量与版本 |
| 2026-09-19 | [fc70c10](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/fc70c103925244fff9a7b90aca2ad10935c9b64f) | 隔离用户画像向量化配置与客户端 |
| 2026-09-20 | [8fe7a66](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/8fe7a667d0f104d137c55b0cc37a647aafd11b5d) | D07 重建向量索引并接入 DeepSeek API |
| 2026-09-20 | [28a31cb](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/28a31cb42706834ba09cdb54dd0ff47264ef7fc8) | 明确每日任务分次提交与提交命名规则 |
| 2026-09-21 | [254da0e](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/254da0e20865453915508c2712f0593c0bbe0e38) | 统一文档解析结果并保留 PDF 页码 |
| 2026-09-22 | [113757f](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/113757f2fa1ec29fa105db869f2a72299050c76b) | 新增 Marker PDF 解析 HTTP 服务 |
| 2026-09-22 | [18b1905](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/18b1905e0c11af0f6808fef1593f6abd5fad38b2) | 明确每日任务至少分两次实质提交 |
| 2026-09-23 | [4e590d4](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/4e590d4fbe9a2e87136c6dca90f992a882171fc4) | 定义 Marker 上传限制与错误契约 |
| 2026-09-23 | [fc20741](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/fc207419066ee25cd363ad465f8995bedcfb8296) | 接入 Marker PDF 与 DOCX 上传转换 |
| 2026-09-23 | [f1b88e9](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/f1b88e920bd9375905fcd54d459a69ea597d5921) | 补充 Marker 运行参数与接口说明 |
| 2026-09-24 | [bcee548](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/bcee548964e7b6c1eb822023c1918205d0b9d3d8) | 实现 Marker 文档转换客户端 |
| 2026-09-24 | [a54b8d4](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/a54b8d40bf3db973be5ce68c28b9d91fa5d15921) | 接入 Marker 上传解析与本地降级 |
| 2026-09-24 | [7ecaceb](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/7ecaceb51ca5823992cd4b597bdc119891bccce8) | 补充 Marker 接入配置与使用说明 |
| 2026-09-25 | [a419b01](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/a419b0134b7ff6f52c9e06c7c38d66ba156a4be4) | 实现 Markdown 标题段落与图片解析 |
| 2026-09-25 | [686d0f8](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/686d0f8e785aabe73542876d85ee73e3a8109339) | 接入 GFM 表格解析与上传结构化结果 |
| 2026-09-27 | [d495b74](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/d495b7441cf0e6ac6c085d75df173d70331de520) | 实现按章节和段落的结构化切块 |
| 2026-09-27 | [2f3c15a](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/2f3c15a85efac8c453ed89e3753f204e8f533027) | 保存知识块章节与类型元数据 |
| 2026-09-27 | [4ee6b46](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/4ee6b469305d8e1bf640d8cf4361dc1c1c5386db) | 保留Markdown代码块边界 |
| 2026-09-27 | [94ea20a](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/94ea20ad4ed8deb568535eef62db5fa554a89560) | 拆分超长表格与代码块 |
| 2026-09-28 | [c5e690d](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/c5e690dee7c8604643ac51cddff01851a3e734dc) | 定义图片描述结构化输出契约 |
| 2026-09-28 | [360a212](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/360a21280c7342f95ee4b237acce380855032fe1) | 新增Qwen2-VL图片描述服务 |
| 2026-09-29 | [0e1b4c9](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/0e1b4c9946566620e0f7c6ed2d6b6147dc2598c2) | 接入图片描述HTTP客户端 |
| 2026-09-29 | [0d2d29a](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/0d2d29a8fa4ce399736540f26b7625894f347011) | 关联文档图片内容与结构化描述 |
| 2026-09-30 | [b0bb23a](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/b0bb23ab84491039ab61b103c645c6f284df0396) | 在Markdown图片原位置回填描述 |
| 2026-09-30 | [02e7363](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/02e7363d7177349ba1f9d0d7afc7e8c1e303001c) | 生成保留原图来源的独立图片检索块 |
| 2026-10-01 | [1b6bfed](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/1b6bfed251de255b393fbc4cc2573089fb883a27) | 配置向量和BM25粗召回Top50 |
| 2026-10-01 | [b83463a](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/b83463afa8d191d410a7635496b9ef351e694d0c) | 保留Chroma异常时的本地检索降级 |
| 2026-10-02 | [c877891](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/c877891250db93d9f5308e87b832391960d22f01) | 实现RRF融合并保留加权策略 |
| 2026-10-02 | [e68ab27](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/e68ab2732d4bac8ed4932ef13f60ea57cbfa9029) | 支持按融合策略生成可比较的评测报告 |
| 2026-10-02 | [d672382](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/d672382e285fc9791ce6874ff988f0de6ab9e4d0) | 更新README项目名称 |
| 2026-10-03 | [dd0ba6f](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/dd0ba6f1cea195e84bbf9dcc5fd7ab670330f819) | 定义BGE批量评分请求与错误契约 |
| 2026-10-03 | [0117349](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/0117349b0651912d8ffdf24b601855e076ea73f3) | 部署BGE批量评分HTTP服务 |
| 2026-10-04 | [7d51a61](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/7d51a61de18ae6fd44cfe7e2ce07b29b8beba5a4) | 接入BGE批量评分HTTP客户端 |
| 2026-10-04 | [a864580](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/a864580ca8838eb3cbe22098ecc511d536bf30b0) | 实现BGE重排Top5与初排降级 |
| 2026-10-05 | [42b4f43](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/42b4f43331f49d48679aabe700386cc1f2f3a55c) | 仅对复杂咨询改写并保留原问题 |
| 2026-10-05 | [4a1db83](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/4a1db83cabf5ccfffe58cf70a490ca07560a59a9) | 限制检索改写输出与等待时间 |
| 2026-10-07 | [4a7eb3b](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/4a7eb3b8ad54579e835325aef3e5493d2a76f77b) | 收紧知识充分性判定 |
| 2026-10-07 | [53d3f0d](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/53d3f0d2488e88f0d90ebf7c7c315dd14dbee723) | 合并原问题与改写检索结果 |
| 2026-10-07 | [d99303f](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/d99303f6e6d06a366f4f381fbfd0bfa72731a9ad) | 约束Agent路由与风险评估顺序 |
| 2026-10-07 | [70c7da3](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/70c7da3e044b2a5b36c4de3bdd06ec22e42caf43) | 限制Agent循环步数并记录路由状态 |
| 2026-10-08 | [6de082e](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/6de082e94880d9e867059524c6f227d23c84ddfd) | 缓存短期摘要并统计会话轮次 |
| 2026-10-08 | [ae36808](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/ae3680882f15326256002b317ac62ede38de65d3) | 按会话轮次阶段性更新短期摘要 |
| 2026-10-09 | [8c1bafc](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/8c1bafc11273bef0684632770c7f3f1eedbe2331) | 合并重复画像候选并保留历史证据 |
| 2026-10-09 | [94a71d2](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/94a71d293ba8a76fa1c7868fa4947fb3cea5cfb7) | 按对话轮次和重要变化触发画像抽取 |
| 2026-10-09 | [b05dfd6](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/b05dfd6559cb2c6cd6bc44c5470f028ddc25297c) | 新增会话结束整理入口并保留失败重试 |
| 2026-10-10 | [c0cedb8](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/c0cedb8307760e4a6953f0db8af68fa6b6524023) | 确认画像索引删除结果并加强用户隔离 |
| 2026-10-10 | [cd4ac38](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/cd4ac38acd13f6f923f91e1106eaf1672e42d8b4) | 统一画像保存展示与外发前的脱敏处理 |
| 2026-10-11 | [f2fa2f7](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/f2fa2f751ded4dee67b7aa94d7c8d75a3fb8c7fb) | 为知识回答附上可核对的检索来源 |
| 2026-10-11 | [ef9f648](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/ef9f648ee045799521808dbec1118bfef7e4e43f) | 明确证据不足并隔离检索文档中的指令 |
| 2026-10-12 | [ff3a07d](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/ff3a07d578b6f1bf3ac8def376221f1ba795a557) | 增加RAGAS输入校验与免服务检查样例 |
| 2026-10-12 | [9d89214](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/9d892149d8f91be5c68c02e3541df8c365845ac1) | 完善RAGAS四项指标报告与失败统计 |
| 2026-10-13 | [49bc13d](https://github.com/cccniuniu-hue/Multi-Agent-Psychological-Support-Agent/commit/49bc13dba13eb8412a36ea11eaf6f679d95c5459) | 更新项目架构与服务降级说明 |

本记录所在提交及最终计划清理提交不写入自身 SHA，避免修改内容后改变 SHA。完整交付历史可在 GitHub main 或使用以下命令查看：

```bash
git log --reverse --format="%h %as %s" 8ff53e2..main
```

最终交付以正常推送后 `git ls-remote origin refs/heads/main` 与本地 HEAD 一致为准；已完成计划的删除另作独立提交，历史仍可通过 Git 回溯。
