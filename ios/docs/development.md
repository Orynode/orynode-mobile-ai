# 开发指南

当前产品目标是严格离线、知识库优先 MVP。本文同时保留现有工程启动方式，并补充目标能力的开发约束；目标架构不等于当前代码已经实现。产品合同见 [product.md](./product.md)，架构见 [architecture.md](./architecture.md)，发布闸门见 [verification.md](./verification.md)。

## 环境

- macOS + Xcode 26 或更新版本（当前工程以 Xcode 26 工具链验证）
- iOS 17+
- 真机（首台验证设备：iPhone 16 Pro；其他 8 GB 级设备可自行尝试）
- [XcodeGen](https://github.com/yonaskolb/XcodeGen)
- Gemma 4 E2B instruction-tuned `.litertlm` 模型（文件名默认 `gemma-4-E2B-it.litertlm`）

## 生成工程

```bash
brew install xcodegen
cd ios
./scripts/bootstrap-litertlm.sh
xcodegen generate
open OrynodeMobileAI.xcodeproj
```

`project.yml` 是工程配置的唯一事实来源。修改 target、构建设置或 Swift Package 依赖后重新生成工程，不手工维护 `.pbxproj`。

LiteRT-LM 以 **本地 path** 依赖接入（`packages.LiteRTLM.path: .tools/LiteRT-LM`）。该目录被 gitignore；克隆仓库后必须先跑 bootstrap。当前钉扎 tag：**`v0.15.0`**（与 checkout 内 `Package.swift` 的 `CLiteRTLM` binary URL 一致）。

## 首次真机运行

1. 在 Xcode Signing & Capabilities 中选择自己的开发团队。
2. 将 `.litertlm` 模型保存到 iCloud Drive 或通过 Finder 传入手机“文件”。
3. 启动 App，导入模型并等待 Metal 初始化。
4. 首页导入 TXT、Markdown、含文本层的 PDF 或 Office Open XML，等待索引后开始提问。

不要把模型文件提交到 Git，也不要加入应用安装包。

`VisionTextRecognizer` 已用于扫描 PDF 空页/稀疏文字层 OCR；拍照入库产品入口仍未启用。

## 系统启动图

冷启动由系统通过 `LaunchScreen.storyboard` 绘制，直接复用 `BrandLogo`，并以静态文本显示「Orynode Mobile AI」和「本地AI知识库」，尺寸与应用内导入页的 `OnboardingBrandHeader` 对齐。

改启动图内容后若删装仍不变，需更换 storyboard 名称或重启手机，以清除 SplashBoard 缓存。

「本地AI知识库」应用内文案仍由 SwiftUI `OnboardingBrandHeader` 绘制；系统封面只认合成 PNG。

## 知识库开发约束

- 核心运行路径禁止网络依赖、云端 fallback、联网搜索和遥测。开发工具下载依赖不等于 App 运行时可以联网。
- 生产 target 不得以“默认关闭”的方式保留核心功能可调用的网络实现。
- 文件必须先安全复制进 App sandbox，再解析；不长期依赖 security-scoped 外部 URL。
- 原文件、数据库、模型、benchmark 语料和用户导入资料均不得提交到 Git。
- SQLite schema、解析器、chunker、embedding 模型、维度、融合参数和拒答阈值都必须有显式版本。

### 生产 Embedding

Debug 在缺少模型时允许使用 `DeterministicHashEmbedding` 做架构联调；Release 禁止回退，必须在 App bundle 中提供：

- `multilingual-e5-small.mlmodelc`
- `multilingual-e5-small-tokenizer/`（`tokenizer.json` + `tokenizer_config.json`）

运行 `ios/scripts/prepare-embedding-model.sh` 会从上游下载模型，在开发机转换为 FP16 Core ML 并做 INT8 权重量化，然后把编译模型与离线 tokenizer 放进 App 资源目录。Release 构建会执行 `validate-embedding-assets.sh`，缺少任一资源直接失败；App 运行时绝不下载。

`CoreMLTextEmbedding` 使用上游 SentencePiece tokenizer、E5 的 `query:` / `passage:` 前缀、attention-mask mean pooling 与 L2 normalize，输出 384 维向量。模型 ID、版本、维度和 tokenizer 版本共同组成 `embedding_index_version`；任一项变化都会拒绝读取旧向量，必须显式重建知识库索引。
- 10,000 chunks 是首版硬容量合同；所有实现和测试均以 chunks 而非文档数计量。

## 解析与切块

支持范围只包括 UTF-8 TXT、Markdown 和可提取文本的 PDF：

1. TXT 规范化换行与 Unicode，不擅自改写正文。
2. Markdown 保留标题路径作为 chunk 元数据；代码块和列表不得被无提示丢弃。
3. PDF 保留页码与字符范围；扫描件、加密或损坏文件明确失败。
4. 切块必须确定性：同一输入、同一 parser/chunker 版本产生相同稳定 ID。
5. 用内容哈希去重；参数变化必须通过迁移或重建任务处理，不能混用不同版本向量。

解析与切块改动必须附 golden tests，覆盖空文件、超长段落、混合语言、重复标题、复杂换行和失败 PDF。

## SQLite 与混合检索

- SQLite 是知识库唯一事实来源；文档元数据、chunks、FTS5、向量、任务 checkpoint 在一致的事务边界内更新。
- 词法检索使用 FTS5/BM25；向量检索在 10,000 chunks 上做精确相似度，不提前引入 ANN。
- 向量以带模型/维度/版本的记录存储；读取和打分分批进行，禁止在 UI 层复制全库向量。
- 混合融合、top-k 和拒答阈值集中配置并版本化，不散落 magic numbers。
- FTS 或向量一路错误时返回可观察错误，不静默退化后继续生成。

涉及 schema 的变更必须提供前向迁移、失败回滚和从旧 checkpoint 恢复测试。不要在开发阶段靠删除数据库掩盖迁移问题。

## embedding 选型流程

不要在实现前把某个 embedding 模型写死为产品结论。每个候选建立记录：

- 名称、来源、许可证、文件 SHA-256、维度、量化、tokenizer、最大输入；
- 本地运行时和模型体积；
- 预注册题集的 Recall@5、MRR@10 与无答案分数分布；
- iPhone 16 Pro 上索引吞吐、查询 p50/p95、冷启动、常驻与峰值内存；
- 与生成模型交替运行的稳定性。

按 [verification.md](./verification.md) 的同机同题流程比较 FTS-only、vector-only 与 hybrid。闸门完成后再锁定模型描述符和重建版本；更换模型或维度必须触发可恢复重建。

## 可恢复索引开发规则

- 索引任务按 copy、parse、chunk、embed、publish 分阶段持久化。
- 批次写入使用事务；checkpoint 只在数据提交成功后推进。
- 恢复与重试必须幂等；同一文档版本不能并行启动两个任务。
- 后台时间结束、取消和内存警告走同一安全暂停路径。
- `publish` 前不得让半索引文档参与查询；上一完整版本可继续服务。
- 失败信息必须可诊断，并允许用户重试或删除；不得要求重新选择原文件才能恢复。

开发时提供可注入故障点，至少能在 10%、50%、90% 进度模拟终止，并校验恢复后的行数、哈希和检索结果。  
Debug 启动参数：`-KBFailAtFraction 0.1`（亦支持 `0.5` / `0.9`）。真机发版清单见 [verification/capacity/CAPACITY_RUN.md](./verification/capacity/CAPACITY_RUN.md)。

## 引用与拒答规则

- 生成输入只能包含本轮选中的证据及必要会话指代。
- 输出 citation ID 必须属于本轮 evidence；UI 从持久元数据解析文件、页码/标题和原文范围。
- 证据不足或资料外问题应拒答；是否在正文写 `[n]` 由模型决定，系统不做重叠强制贴标。
- 确定性校验只覆盖封闭证据集：非法编号丢弃、合法编号格式归一；禁止用后处理代替模型的引用判断。
- 删除文档后，历史引用必须显示来源已删除，不能继续伪装为可核验。

相关改动必须同时有正例、改写问题、关键词稀疏问题、无答案和冲突证据测试。

## 真机性能工作流

性能结论只采信 release 构建真机数据：

1. 固定设备、系统、commit、模型哈希、schema/chunker 与题集版本。
2. 从冷启动分别测空库、1,000 chunks、10,000 chunks。
3. 使用 signpost/MetricKit 或 Instruments 分段记录解析、embedding、FTS、精确向量、融合和生成。
4. 记录 p50/p95、吞吐、常驻/峰值内存、温升、耗电、jetsam 和崩溃。
5. 将原始结果与摘要存入不含用户私密资料的 benchmark 记录。

在首个可发布基线形成后冻结预算；后续 p95 或峰值内存回退超过 10% 必须说明。模拟器只用于功能调试，不能用于模型选型或发布性能结论。

## 升级 LiteRT-LM

工程通过 **vendored path checkout** 固定版本，不是 `project.yml` 里的 `exactVersion`。

1. 确认目标 release tag（例如 `v0.16.0`），且其 `Package.swift` 中 binary URL / checksum 完整。
2. `LITERT_TAG=v0.16.0 ./scripts/bootstrap-litertlm.sh`
3. 核对 `ios/.tools/LiteRT-LM/Package.swift` 的 binary 版本与 tag 一致。
4. `xcodegen generate`，解析 SPM，执行单元测试。
5. 在 iPhone 16 Pro 重跑准确率、性能、连续推理和飞行模式测试。
6. 将结果记录到 benchmark 文档；同步更新本文件与 bootstrap 脚本中的默认 `LITERT_TAG` 后再合并。

## 架构合同

改 Domain / Application、存储、索引、检索、生成或 OCR 边界前先读 [architecture.md](./architecture.md)。

## 版本与 Changelog

发版或合并用户可见变更时：

1. 更新 `project.yml` 里 `OrynodeMobileAI` 的 `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION`（构建号只增不减）。
2. 在 [CHANGELOG.md](../CHANGELOG.md) **顶部**追加对应版本与中文变更说明。
3. `xcodegen generate` 后真机或单测确认。

`0.x` 为 Spike；约定见 Changelog 文首。

## 提交原则

- Domain 和 Application 改动必须带单元测试。
- LiteRT-LM API 只能出现在 Infrastructure。
- UI 不得直接读取模型文件或创建 Engine。
- 知识库核心路径不得新增网络请求。
- OCR 只负责文本提取；未经统一索引流水线不得注入知识库回答。
- schema、chunker、embedding 或检索参数变更必须带迁移/重建策略和真机基准。
- 不提交真实用户照片、模型权重、签名证书和密钥。
