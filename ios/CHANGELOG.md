# Changelog（iOS）

本端版本遵循 [SemVer](https://semver.org/lang/zh-CN/)。

- **营销版本** `CFBundleShortVersionString` = `MARKETING_VERSION`（如 `0.1.0`）
- **构建号** `CFBundleVersion` = `CURRENT_PROJECT_VERSION`（单调递增整数）
- 事实源：`project.yml` 中 `OrynodeMobileAI` 的上述两项；发版时同步更新本文件

`0.x` 期间为 Spike / 预览：破坏性或用户可见行为变化可递增次版本（`0.Y.0`）。进入 `1.0.0` 后再严格按主/次/修订语义。

发版时在本节顶部追加新版本，格式见下方模板。

---

## Unreleased（相对 0.3.0；营销版本仍为 `0.3.0`）

架构整理与入库加固已合入本地 `main`（HEAD `fdd6803`）。**不因本节宣称发布完成**；发布资格仍看 [verification.md](./docs/verification.md)。

### 新增 / 加固
- 10,000 chunks 硬上限（超限拒绝新增，保留已有索引；同文档重试按替换计数）
- 可取消的分批索引 checkpoint（`unpublished_chunks` + `index_jobs`；`publish` 前不可检索）
- 复制进 sandbox 后立即 `importing` 入列；问答来源在 enrich 完成前先显示
- PDF `pageSpans` 入库；Ask 引用收窄在 Application（`CitationLocatorEnricher`）；Features 只解析印刷页码
- 扫描 PDF：无文字层页面本机 Vision OCR 后进入同一索引流水线
- `retrieval_version` / `chunker_version` / `content_hash_version` 写入 `knowledge_metadata`
- 内容去重改为 SHA-256，并按 hash 查询
- 预注册题集 seed（`docs/verification/evalset/`；含 4 个文本层 PDF 与页码锚点题）

### 变更
- Composition Root 迁到 `App/KnowledgeBaseComposition`；Features 引用 DTO 为 `CitedSource`
- 列表与会话不再常驻全文；检索先打分再读 top-k 正文
- tokenizer 本地 JSON 加载（不走 `HubApi.shared`）

### 真机记录（部分闸门）
- iPhone 真机 **飞行模式**下核心路径（导入 → 索引 → 提问 → 引用）已通过；不等于整份 verification 发版完成

### 仍未完成（不宣称发布完成）
- 预注册题集的 recall / citation / 拒答闸门
- 10,000 chunks 真机容量、恢复与内存/性能基线（机制单测与 `docs/verification/capacity/` 清单已备；缺 Release 真机归档）
- 分时加载调度器；PDF 复杂版式高亮；OOXML 复杂表格 / 页眉页脚完整度

---

## 0.3.0 — 2026-08-12

**严格离线本机知识库 MVP。**

### 新增
- 本机知识库：导入 TXT / Markdown / 文本型 PDF / Office Open XML → 切分 → Core ML embedding → SQLite FTS5 + 向量检索 → Gemma 带来源回答
- 内置 `multilingual-e5-small` INT8 Core ML 与离线 tokenizer（运行时不下载）
- 证据不足拒答、失败索引可按原文档 ID 重试、完整文件保护与 `secure_delete`
- 文档预览与引用跳转到来源片段

### 当时已知未完成
- 见上方 Unreleased「仍未完成」；其中硬上限、分批 checkpoint、pageSpans / Ask 收窄、SHA-256 与版本闸门已在后续提交落地，但仍须真机题集与容量闸门才可发版

### 变更
- 首页改为知识库管理与问答
- Domain 收敛为模型运行时、OCR 基建与知识库合同
- App 仅负责模型生命周期与知识库入口

### 依赖 / 工程
- `MARKETING_VERSION` `0.3.0` · `CURRENT_PROJECT_VERSION` `3`
- LiteRT-LM `v0.15.0`、huggingface/swift-transformers Tokenizers、ZIPFoundation

---

## 模板（复制到顶部）

```markdown
## X.Y.Z — YYYY-MM-DD

**一句话概括本版。**

### 新增
- …

### 变更
- …

### 修复
- …

### 依赖 / 工程
- …
```
