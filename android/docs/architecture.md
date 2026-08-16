# 架构说明：Android 本机知识库

本文是 Android 端**现行架构合同**。它描述与 iOS 对齐的产品语义、Gradle 模块边界、两条主链路，以及已接线的 Infrastructure。产品口径服从仓库根 [README.md](../../README.md)；iOS 参考实现见 [ios/docs/architecture.md](../../ios/docs/architecture.md)。

**状态：Spike 主链路已接线（导入 / 检索 / 拒答 / 生成本机回答）；真机闸门与发版数字见 [verification.md](./verification.md)。** 编译通过 ≠ 可对外宣称发布。

## 1. 一句话定位

Orynode Mobile AI 的 Android 端是运行在手机上的**严格离线私人知识库**：导入资料，本机索引，本机检索，本机生成，回答必须带来源或明确拒答。

应对齐 iOS 的是 **Domain / Application 语义与验收口径**；Infrastructure 用 Android 等价栈替换，**不强制同构 API**。

## 2. 设计原则

1. **严格离线是架构约束，不是 UI 开关。** 知识库导入、索引、检索、生成不得依赖网络；**允许**在模型准备页从国内镜像下载生成权重（Manifest 声明 INTERNET 仅服务该获取路径）。
2. **证据优先于通识。** 生成模型只消费检索命中的资料；证据不足必须拒答，且**不调用生成引擎**。
3. **端口隔离具体运行时。** Domain 定义合同；Infrastructure 可替换；UI 只通过 `KnowledgeBaseServing` 说话。
4. **版本化索引。** embedding / retrieval / chunker / content_hash 任一变化必须可检测。
5. **小规模优先确定性。** 首版约 10,000 chunks，精确 cosine + FTS 融合，不预支 ANN。
6. **可扩展但不预支复杂度。** SQLite、LiteRT、OCR 已接入；后台索引（如 WorkManager）仅作扩展挂点，未实现。

## 3. 模块与依赖（编译期单向）

```text
:app                 Compose · SAF · Serving · 装配根
        ↓
:application         ImportKnowledgeDocument · AskKnowledgeBase · Chunker
        ↓
:domain              模型、端口、拒答/引用合同（纯 JVM，无 Android / SQLite / LiteRT）
        ↑
:infrastructure      抽取 · 仓储 · Embedding · ModelStore · OCR/LLM 适配器
```

| 模块 | 类型 | 允许依赖 | 禁止 |
|---|---|---|---|
| `:domain` | Kotlin JVM | coroutines | Android、SQLite、LiteRT、Compose |
| `:application` | Kotlin JVM | `:domain` | Android、Infrastructure 类型 |
| `:infrastructure` | Android Library | `:domain` | Application、Compose |
| `:app` | Application | `:application` + `:infrastructure` | UI 包直接 import Infrastructure（仅 `app.composition` 允许） |

硬规则：

- Features / ViewModel 只依赖 `KnowledgeBaseServing`（允许引用 domain 合同类型；允许引用 `:application` 的纯展示工具如 `DocumentDisplayName`，禁止引用 Infrastructure）。
- LiteRT / ML Kit / `android.database.sqlite` 只出现在 `:infrastructure`。
- **例外：** `PdfRenderer` 允许出现在 `app/ui/preview` 的原件预览（只读渲染页图）；抽取/OCR 用的 PdfRenderer 仍只在 `:infrastructure`。
- 换仓储或换模型不改 Domain 合同。
- 导入 / 重试索引 / 问答在 Serving 层串行（`knowledgeOpsMutex`），UI 同步互禁，避免卸模与 embed 交叉。

## 4. 两条主链路

### 4.1 入库

```text
SAF 选文件 → 复制进 filesDir/KnowledgeBase/Documents
  → documents(state=Importing)
  → Extractor → Chunker
  → 分批 embed → unpublished + checkpoint
  → publish 原子进入 live（state=Ready）
```

未 `Ready` 的文档不得进入检索。杀进程后按同一 documentId 重试，跳过已提交批次。

### 4.2 问答

```text
问题 → embedQuery → 混合检索
  → 最低分阈值 → 证据预算裁剪
  → 本机 LLM 流式生成 → 封闭集引用校验 → 回答 + citations
```

无 Ready 文档或低分无命中 → **不调用生成模型**，直接拒答。

融合公式（与 iOS 对齐，变更须升 `retrieval_version`）：

`0.7 * cosine + 0.3 * normalized_fts`

## 5. 当前实现 vs 下一刀

已落地：

- Domain 端口与知识库对象（对齐 iOS 语义）
- Application：结构切分、入库、问答、引用 canonicalizer、证据包装
- Infrastructure：明文 TXT/MD、**PDF（PdfBox 文字层 + PdfRenderer 渲染）**、**OOXML→Markdown（docx/xlsx/pptx）**、扫描页 **ML Kit 中文 OCR** 回退、SQLite + FTS5 混合检索、unpublished staging / checkpoint、索引版本闸门
- Embedding：`LiteRtTextEmbedding`（multilingual-e5-small）；assets 缺失时 **仅 Debug** 回退 hash；Release 必须打包 assets
- 生成：`LiteRtLmModelEngine`（LiteRT-LM **0.15.0**）+ `FileModelStore`；Gemma `.litertlm` 用户导入
- App：装配根、`KnowledgeBaseServing`、Compose UI（首页 / 聊天 / 预览 / 设置 / 开源声明，品牌 Logo）
- 单测：拒答、引用封闭集、入库、SQLite、embedding 合同、引擎未加载、OCR 阈值、OOXML fixture

下一刀：

1. 真机闸门（见 [verification.md](./verification.md)；无芯片白名单，发版数字只认验证参考真机）
2. 可选：PDF 页内文字精确高亮（当前为跳页 + 文本行高亮）

`InMemoryKnowledgeRepository` 仅保留作轻量仓储单测夹具（不做索引版本闸门，不进生产装配）。

## 6. 扩展点

| 意图 | 挂点 |
|---|---|
| 新文件类型 | `KnowledgeTextExtractor` |
| 拍照入库 | `TextRecognizer` → Extractor |
| 换 embedding | `TextEmbedding` + `EmbeddingDescriptor`（升版本 → 重建） |
| 换生成模型 | `LocalModelEngine` / `ModelStore` |
| 更强检索 | `KnowledgeRepository.search` |
| 后台索引 | Import checkpoint；WorkManager 外挂同一恢复路径 |
| Mac 同步 | **新产品阶段**，不得破坏核心路径离线 |

禁止：UI 直连 SQLite / LiteRT；prompt 里塞联网结果；hash embedding 冒充发版；OCR 绕过索引当答案。

## 7. 设备支持与模拟器

| 用途 | 环境 |
|---|---|
| 逻辑 / CI / 架构联调 | JVM 单测；**8GB 模拟器**可跑 TXT 导入与拒答（功能联调，非发版依据） |
| 选型、延迟、内存、发版数字 | **真机验证参考机**（当前开发机见 [verification.md](./verification.md)）；**无 SoC 白名单** |

模拟器结果不得写入发布报告。对外勿把参考机型号写成「仅支持列表」。

## 8. 源码地图

```text
android/
  domain/           合同
  application/      用例
  infrastructure/   适配器
  app/
    composition/    唯一允许 import infrastructure 的装配根
    serving/        KnowledgeBaseServing
    ui/             Compose + ViewModel
  docs/             本文件、development、verification
```

## 9. 架构验收清单

合并影响知识库的变更前确认：

1. 依赖方向仍是 app → application → domain ← infrastructure
2. 知识库核心路径无网络客户端；INTERNET 仅用于模型准备页下载生成权重
3. 证据不足仍拒答且不调用生成模型
4. embedding / retrieval / chunker / content_hash 变更升版本
5. UI 不直连仓储或运行时
6. 真机结论来自本端验证参考机，不复用 iPhone 数字；不把参考机型号写成仅支持列表
