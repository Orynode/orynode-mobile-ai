# 开发约束（Android）

产品合同服从仓库根 README 与 iOS [product.md](../../ios/docs/product.md)。架构见 [architecture.md](./architecture.md)。发布闸门见 [verification.md](./verification.md)。

## 环境

- JDK 17
- Android Studio / AGP **9.3**、Kotlin **2.3**（LiteRT-LM Maven 工件要求 Kotlin metadata ≥ 2.3；版本以 `gradle/libs.versions.toml` 为准）
- `minSdk 26`，`compileSdk 35`
- 不在默认安装包中放入 `.litertlm` 生成权重

## 构建

在 `android/` 目录，按顺序：

1. 准备 embedding assets（需网络与较大磁盘；权重不进 Git；**Release 必做**）：

```text
./scripts/prepare-embedding-model.sh
```

2. 写入本机 `local.properties`（已 gitignore），至少包含 `sdk.dir=…`（Android Studio 通常自动生成）。
3. 跑单测与 Debug 包：

```text
./gradlew :domain:test :application:test :infrastructure:test
./gradlew :app:assembleDebug
```

CI（`.github/workflows/android-jvm.yml`）覆盖上述 JVM/Robolectric 单测，**不等于** [verification.md](./verification.md) 真机闸门。

Android SDK 路径不要提交。
- 默认仓库含阿里云 Maven 镜像（本机访问 Maven Central 可能 TLS 失败）；可按网络环境调整 `settings.gradle.kts`。
- Infrastructure 单测用 Robolectric；其 SQLite 可能无 FTS5，仓储会回退词法扫描。真机 / 正式模拟器系统 SQLite 走 FTS5。

## 模型与 embedding

- 生产 embedding：`multilingual-e5-small` LiteRT（descriptor `multilingual-e5-small-litert` / tokenizer `xlmr-unigram-v1`）。
- 发版机准备 assets：

```text
./scripts/prepare-embedding-model.sh
```

  产出写入 `app/src/main/assets/embedding/`（`*.tflite` 与 tokenizer 已 gitignore）。
- **Debug**：assets 缺失时装配根允许 hash 回退联调（设置页会显示 `deterministic-feature-hash-fallback`，**语义检索已降级**）；**Release** 禁止 hash，缺 assets 直接失败。
- Gemma `.litertlm` 由用户导入，或在模型准备页从 hf-mirror 下载（可取消、断点续传；**下载需网络**）；`ModelDescriptor` 的 SHA/字节 pin 现为可选（默认 null，不假装已做完整性校验）；不进 Git、不进默认 APK；LiteRT-LM 运行时 pin **0.15.0**（与 iOS `bootstrap-litertlm.sh` 同 tag）。
- Tokenizer / embedding 权重运行时不得走网络下载；生成权重仅允许在模型准备页下载，导入后的知识库链路仍离线。
- 文档类型：UTF-8 TXT/Markdown、文本层 PDF（PdfBox）、OOXML（docx/xlsx/pptx→Markdown）；扫描/稀疏 PDF 页经 **ML Kit 本机 OCR** 后进入同一索引流水线。老 `.doc`/`.xls`/`.ppt` 不支持。
- OCR 不得绕过索引直接成为答案；拍照入库产品入口仍未启用。

## 索引

- 切分默认 520 / 64，与 iOS `KnowledgeIndexContract` 对齐。
- 融合 `0.7 * cosine + 0.3 * normalized_fts`；改公式先升 `retrieval_version`。
- 内容去重 SHA-256；已有索引遇到版本不匹配必须拒绝打开并重建。

## 日志

不得记录正文、问题或回答。
