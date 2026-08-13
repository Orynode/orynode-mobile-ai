# 架构说明：本机知识库（Orynode Mobile AI）

本文是 iOS 端**现行架构合同**。它描述产品定位、分层依赖、两条主链路、扩展点，以及已实现与待加固的边界。产品口径见 [product.md](./product.md)，开发约束见 [development.md](./development.md)，发布闸门见 [verification.md](./verification.md)，重构过程见 [rag-refactor-record.md](./rag-refactor-record.md)。

## 1. 一句话定位

Orynode Mobile AI 是运行在 iPhone 上的**严格离线私人知识库**：用户导入资料，本机索引，本机检索，本机生成，回答必须带来源或明确拒答。

它不是通用聊天壳，也不是 Mac 版 [Orynode Local AI](https://github.com/Orynode/orynode-local-ai) 的缩小版。

| | Mac Local AI | Mobile AI |
|---|---|---|
| 角色 | 私有 AI 工作站 / 私有 AI Server | 随身私人知识库 |
| 典型负载 | 大模型、大资料库、复杂任务 | 小模型、万级 chunks、即时问答 |
| 联网 | Trusted-LAN 等可选能力另议 | 核心路径禁止联网功能 |
| 同步 | 可自建本地服务 | 首版不自动同步，数据只在本机 |

共同原则：local-first、用户拥有数据、无账号、无遥测。

## 2. 设计原则

1. **严格离线是架构约束，不是 UI 开关。** 生产核心路径不得依赖网络客户端实现功能。
2. **证据优先于通识。** 生成模型只消费检索命中的资料；证据不足必须拒答。
3. **端口隔离具体运行时。** Domain 定义合同；Infrastructure 可替换实现；UI 不直接碰 SQLite / Core ML / LiteRT。
4. **版本化索引。** embedding、维度、tokenizer、schema 任一变化都必须可检测；禁止新旧向量混用。
5. **小规模优先确定性。** 首版目标约 10,000 chunks，使用精确向量检索，不引入 ANN。
6. **可扩展但不预支复杂度。** 扩展点先以协议暴露，再按真机数据决定是否引入队列、ANN、OCR 入库。

## 3. 分层与依赖

```text
Features / App          SwiftUI · 状态 · 文件选择 · 引用阅读
        ↓
Application             ImportKnowledgeDocument · AskKnowledgeBase · Chunker
        ↓
Domain                  模型、端口、拒答/引用合同（无 UI / 无 SQLite / 无 Core ML）
        ↑
Infrastructure          PDFKit · SQLite/FTS5 · Accelerate · Core ML · LiteRT-LM · Vision OCR
```

| 层 | 目录 | 职责 |
|---|---|---|
| Domain | `Sources/Domain` | `KnowledgeDocument` / `KnowledgeChunk` / `KnowledgeCitation` / `TextEmbedding` / `KnowledgeRepository` 等 |
| Application | `Sources/Application` | 导入与问答编排、结构感知切分、上下文预算 |
| Infrastructure | `Sources/Infrastructure` | 文本抽取、SQLite 仓储、Core ML embedding、Gemma 引擎、OCR 基建 |
| App / Features | `Sources/App` · `Sources/Features` | 模型生命周期、知识库 UI、设置 |

硬规则：

- Domain 不 import SwiftUI / UIKit / PDFKit / SQLite / CoreML / LiteRT。
- LiteRT 与 Core ML 类型只出现在 Infrastructure。
- Features 通过 `KnowledgeBaseServing` 适配层调用用例，不散落编排逻辑。

## 4. 两条主链路

### 4.1 入库（Ingest）

```text
用户选择文件
  → security-scoped 读取
  → 复制进 App sandbox（完整文件保护）
  → 立即写入 documents 行（state=importing，列表可见，不可检索）
  → KnowledgeTextExtractor 抽取正文
  → KnowledgeChunker 结构切分
  → prepareIndexJob（parse 结果进 index_jobs；同 hash 则保留 unpublished checkpoint）
  → 分批 TextEmbedding.embedDocuments
  → appendUnpublishedChunks（事务：unpublished_chunks + checkpoint；不进检索）
  → publishUnpublishedChunks（事务：staging → live chunks+FTS，state=ready）
```

当前用例：`ImportKnowledgeDocument`。

状态机：

```text
importing（列表可见） → ready
                      ↘ failed  →（同 documentID）retry → importing …
ready 文档重试时保持 ready，直到 publish 原子替换 live chunks
```

设计要点：

- 先落盘再解析，不长期依赖外部 security-scoped URL。
- 复制完成后立刻入列；未 `ready` 的文档不得进入检索候选。
- 分批 embedding 写入 `unpublished_chunks`；checkpoint 只在该批事务提交后推进。杀进程 / 取消 / 失败后按原 ID 重试，跳过已提交批次。
- `publish` 前半索引文档不可检索；已 `ready` 的上一完整版本继续服务，直到 publish 原子替换。
- 失败保留 `failed` 与错误信息（ready 重试失败不降级 live 状态），可按原 ID 重试，不要求重新选择原文件。
- embedding 索引版本写入 `knowledge_metadata`；版本不匹配且已有向量时拒绝启动，避免静默错答。
- `retrieval_version` / `chunker_version` 同样写入；与当前融合权重或切分参数不一致且已有索引时拒绝打开库。
- `content_hash_version` 记录去重算法；从 FNV 升到 SHA-256 时视为版本事件，已有索引必须重建。

### 4.2 问答（Ask）

```text
用户问题
  → KnowledgeSearchScope（全部资料 / 指定文档集合）
  → TextEmbedding.embedQuery（query: 前缀）
  → KnowledgeRepository.search（先按 document_id 限定候选，再做 FTS5 BM25 + Accelerate cosine 加权融合）
  → 最低分阈值过滤
  → 按 token 预算挑选证据
  → KnowledgeAnswerGenerator（本机 Gemma，AsyncThrowingStream 增量正文）
  → 生成结束后封闭集引用校验与来源绑定
  → KnowledgeAnswer{text, citations[]}
```

当前用例：`AskKnowledgeBase`。

检索范围属于会话状态并随历史会话持久化；旧会话缺少该字段时按「全部资料」解码。范围过滤必须发生在 FTS 和向量候选阶段，禁止先做全库 Top-K 再过滤。

拒答合同：

- 无 ready 文档 → 服务层错误提示先导入。
- 检索无命中或分数低于阈值 → **不调用生成模型**，直接返回拒答文案与空 citations。
- 生成 prompt 明确：只能依据证据；禁止补充外部常识。

## 5. 领域模型（核心对象）

| 对象 | 含义 |
|---|---|
| `KnowledgeDocument` | 一份已导入资料及其索引状态 |
| `KnowledgeChunk` | 可检索、可引用的文本单元（含 heading / ordinal / tokenEstimate） |
| `EmbeddedKnowledgeChunk` | chunk + 向量 |
| `KnowledgeSearchHit` | 检索命中（chunk + 文档标题 + 融合分） |
| `KnowledgeCitation` | 回答中的结构化来源 |
| `KnowledgeAnswer` | 回答正文 + citations |
| `EmbeddingDescriptor` | embedding 身份（id / version / dimensions / tokenizerVersion） |

端口：

| 端口 | 作用 | 当前默认实现 |
|---|---|---|
| `KnowledgeTextExtractor` | URL → 纯文本 | `LocalKnowledgeTextExtractor`（TXT/MD/PDFKit + OOXML→Markdown） |
| `KnowledgeChunker` | 文本 → chunks | `StructuredKnowledgeChunker` |
| `TextEmbedding` | 文档/查询向量化 | `CoreMLTextEmbedding`（Release）；Debug 可回退 hash |
| `KnowledgeRepository` | 持久化与混合检索 | `SQLiteKnowledgeRepository` |
| `KnowledgeAnswerGenerator` | 证据上下文 → 回答 | LiteRT Gemma 适配器 |
| `LocalModelEngine` / `ModelStore` | 生成模型生命周期 | `LiteRTLMModelEngine` / `FileModelStore` |
| `TextRecognizer` | 图片/渲染页 → OCR | `VisionTextRecognizer`（扫描 PDF 空页/稀疏文字层回退；拍照入口未启用） |

## 6. 存储与检索

SQLite 最小表：

- `documents`
- `chunks`（含 embedding BLOB）
- `chunks_fts`（FTS5）
- `knowledge_metadata`（含 `embedding_index_version`、`retrieval_version`、`chunker_version`、`content_hash_version`）

检索策略（首版）：

1. FTS5：词法候选（BM25）
2. 全库精确 cosine（Accelerate）
3. 融合：`0.7 * cosine + 0.3 * normalized_fts`
4. top-k 后进入上下文预算

为何不做 ANN：万级 chunks 优先简单、可审计、易恢复；只有真机 P95 不达标时再引入。

隐私与安全：

- 原文与数据库使用 iOS 完整文件保护
- `PRAGMA secure_delete = ON`
- 删除文档级联删除 chunks / FTS / 向量与 sandbox 原件
- 日志不得记录正文、问题或回答

## 7. Embedding 与生成的共存

```text
索引期：加载 Core ML embedding → 批量向量化 → 写库 → 可释放
问答期：embedding 查询向量 → 检索 → 加载/复用 Gemma → 生成 → 内存警告时可 unload
```

约束：

- embedding 模型随 App 内置（约 134 MB：Core ML + tokenizer），运行时不下载。
- Gemma `.litertlm` 由用户导入，不进 Git、不进默认安装包。
- 两个重型模型不得无条件同时常驻；内存警告时释放生成引擎。

### 7.1 iPhone / E2B 效果优化合同

生成窗口约 `2048` tokens。质量优先靠**检索准、证据少而精、回答短而可核对**，不靠塞更多上下文。

`OnDeviceRAGBudget.gemmaE2B` 默认：

| 预算项 | 默认 | 作用 |
|---|---|---|
| `evidenceTokenBudget` | 900 | 证据正文上限，给问题与回答留空 |
| `retrievalLimit` | 5 | 先取候选，再裁剪 |
| `maxCitations` | 3 | 回答最多引用 3 条 |
| `maxChunksPerDocument` | 2 | 避免同一文档占满证据槽 |
| `minimumScore` | 0.15 | 低分直接拒答，不调用生成 |
| `preferredAnswerCharacters` | 220 | 引导短答 |
| `evidenceExcerptCharacters` | 420 | 单条证据截断 |

切分默认约 520 字符 / 64 overlap，提高检索粒度。生成使用 temperature 0，禁止闲聊采样。

## 8. UI 组合

```text
Launch →（无模型）ModelSetup →（就绪）Home
Home = KnowledgeBaseView
  ├─ 导入
  ├─ 文档列表 / 状态 / 重试 / 删除
  └─ 问答 Sheet（回答 + 来源详情）
Settings = 模型状态 / 隐私说明 / 删除模型
```

装配根：`App/KnowledgeBaseComposition` 创建 SQLite、embedding、抽取器与生成器。Features 只通过 `KnowledgeBaseServing` 调用，不 `import OrynodeInfrastructure`。

## 9. 扩展点（保持架构完整的关键）

开源项目要可扩展，但扩展必须挂在端口上，而不是穿透分层。

| 扩展意图 | 挂点 | 建议做法 |
|---|---|---|
| 新文件类型（DOCX / EPUB） | `KnowledgeTextExtractor` | OOXML 已由 Infrastructure 抽取；EPUB / 老二进制 Office / anydoc 另挂适配器 |
| 拍照/扫描入库 | `TextRecognizer` → Extractor | 扫描 PDF 空页已 OCR；拍照入口仍外挂同一流水线 |
| 换 embedding 模型 | `TextEmbedding` + `EmbeddingDescriptor` | 升版本 → 强制重建索引 |
| 换生成模型 | `LocalModelEngine` / `ModelStore` | 保持 `AnalysisRequest` 文本生成合同 |
| 更强检索 | `KnowledgeRepository.search` | 可换成 RRF / 重排器 / ANN，题集校准后替换 |
| 后台索引队列 | `ImportKnowledgeDocument` 分批 checkpoint；独立 IndexScheduler 仍可外挂 | 取消 / 失败 / 杀进程共用 staging 恢复路径 |
| 多知识库/命名空间 | Domain 增加 `collectionID` | schema 迁移后过滤检索 |
| Mac ↔ 手机同步 | **新产品阶段** | 不得破坏“核心路径严格离线”合同 |

禁止的扩展方式：

- UI 直接读 SQLite 或直接加载 Core ML
- 在生成 prompt 中“偷偷”加入联网搜索结果
- 用 Debug hash embedding 冒充生产语义模型发版
- 让 OCR 结果绕过索引直接成为无引用答案

## 10. 已实现 vs 待加固

已实现：

- Domain 端口与知识库对象
- TXT / Markdown / PDF（文字层 + 扫描页本机 OCR）/ OOXML（docx·xlsx·pptx → Markdown）导入
- 结构切分、Core ML E5 embedding、SQLite 混合检索（先打分再读 top-k 正文）
- 拒答、引用 UI、严格离线产品路径
- App 装配根；Features 引用 DTO 为 `CitedSource`；列表与会话不含全文
- 10,000 chunks 硬上限（超限失败并保留已有索引；同文档重试按替换计数）
- 本地加载 tokenizer（不走 `HubApi`）
- 复制进 sandbox 后立即入列（`importing`）；未 ready 不可检索
- `SourceLocator`（PDF 页码 / Markdown 行号 / 纯文本偏移）入库与 citation 快照
- 文档预览壳：列表预览 + 引用跳转到原件并高亮（OOXML 预览抽取后的 Markdown）
- 引用协议：检索发号形成内部封闭 evidence pack；**正文以模型输出为准**；`CitationCanonicalizer` **只丢非法号**（不挪标、不压空行、不去重）；`KnowledgeAnswer.citations` 只包含正文实际引用的来源，未引用证据不进入 UI —— 不做重叠强制贴标
- PDF 页码合同：`[n]` 的跳转页 = 该证据 chunk **入库时的** `SourceLocator.pdf.page`（PDFKit **文档序** 1-based index，用于 `go(to:)`）。UI 展示优先用 `PDFPage.label`（印刷/目录页码）；封面等无印刷页码的前置页会导致「文档序 41 ≈ 印刷 34」，属页码体系差异而非定位错误。enrich / 预览不得用全文 `findString` 改绑文档序页码。
- `pageSpans` 随文档详情入库；列表投影不含 spans
- Ask 引用收窄在 Application（`CitationLocatorEnricher` + `CitationLocatorRefiner`）；Features 只解析 PDF 印刷页码
- `retrieval_version` / `chunker_version` 写入 `knowledge_metadata`（当前仍为 0.7/0.3 与 520/64；变更须升版本并重建）
- SHA-256 内容去重（`content_hash_version=sha256-v1`；按 hash 查询，不扫全库正文）
- 可取消的分批索引 checkpoint（`unpublished_chunks` + `index_jobs`；publish 前不可检索；ready 文档重试时上一版本继续服务）

待加固（**代码已实现 ≠ 发布完成**；须按 [verification.md](./verification.md) 出真机报告）：

- 真机 recall / citation / 拒答题集闸门
- 10,000 chunks 真机延迟、峰值内存与恢复证明（硬上限、checkpoint、capacity 清单与机制单测已有；缺 Release 真机归档）
- PDF 页内高亮在复杂版式上的精度
- OOXML 抽取完整度（复杂表格 / 页眉页脚 / 老 `.doc`·`.xls`·`.ppt` 仍不支持；与 Mac anydoc 对齐另议）
- 分时加载策略的系统化调度器

真机进度备忘（非正式验收包，发版前须按 verification 归档 commit / 模型哈希 / 指标）：

- 飞行模式核心路径已通过（导入 → 索引 → 提问 → 引用）
- 预注册题集、10k 容量恢复、内存/性能基线仍缺正式报告

## 11. 源码地图

```text
ios/Sources/
  Domain/
    KnowledgeBase.swift      # 知识库合同与端口
    AnalysisModels.swift     # 模型描述与 AnalysisRequest
    OCRDocument.swift        # OCR 基建类型
    Ports.swift              # LocalModelEngine / ModelStore / TextRecognizer
  Application/
    KnowledgeBaseUseCases.swift
    QueryFocus.swift
    LocalModelKnowledgeAnswerGenerator.swift
    CitationLocatorRefiner.swift
    CitationLocatorEnricher.swift
  Infrastructure/
    KnowledgeTextExtraction.swift
    OfficeOpenXMLTextExtraction.swift
    LocalTextEmbedding.swift
    CoreMLTextEmbedding.swift
    SQLiteKnowledgeRepository.swift
    LiteRTLMModelEngine.swift
    FileModelStore.swift
    VisionTextRecognizer.swift
  Features/KnowledgeBase/    # Serving 适配、CitedSource、印刷页码、预览与问答 UI
  App/
    KnowledgeBaseComposition.swift
```

## 12. 架构验收清单（开源维护者）

合并影响知识库的 PR 前确认：

1. 依赖方向是否仍是 Features → Application → Domain ← Infrastructure
2. 新能力是否只通过端口接入
3. 是否保持严格离线核心路径
4. embedding / schema 变更是否带版本与重建策略
5. 无证据时是否仍拒答，而不是“看起来合理”
6. Domain / Application 是否有对应单测或明确说明为何暂缓
