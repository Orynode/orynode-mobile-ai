# Android

Orynode Mobile AI 的 **Android** 客户端：严格离线的本机 RAG 知识库。

## 状态

**Spike 已接线，真机闸门未完成。** 当前可：TXT/MD/PDF/OOXML 导入、multilingual-e5 LiteRT embedding、SQLite+FTS5 混合检索、证据不足拒答、LiteRT-LM（Gemma）流式生成与封闭集引用。知识库路径严格离线；**仅模型准备页可从镜像下载生成权重**。发版数字只认 [verification.md](./docs/verification.md) 基线真机（闸门尚未勾选）。`versionName` 为 `0.1.0-spike`，非 Play 上架制品。

产品规划见仓库根 [README.md](../README.md)。架构合同见 **[docs/architecture.md](./docs/architecture.md)**。

## 架构摘要

- Gradle 模块强制单向依赖：`:app` → `:application` → `:domain` ← `:infrastructure`
- 与 iOS 对齐：离线、拒答、封闭集引用、版本化索引、万级 chunks
- Infrastructure 用 Android 等价栈：LiteRT / LiteRT-LM、SQLite/FTS5、SAF、ML Kit、PdfBox（不强制与 iOS 同构 API）

## 文档

| 文档 | 说明 |
|---|---|
| [docs/architecture.md](./docs/architecture.md) | 现行架构合同、模块边界、主链路 |
| [docs/development.md](./docs/development.md) | 构建、模型、索引规则 |
| [docs/verification.md](./docs/verification.md) | 真机闸门；无芯片白名单；发版数字只认验证参考真机 |
| [NOTICE](./NOTICE) | 第三方说明 |

## 构建

需要 JDK 17 与 Android SDK。在本目录：

```text
./scripts/prepare-embedding-model.sh   # 首次：产出 embedding assets（权重不进 Git）
./gradlew :application:test :infrastructure:test
./gradlew :app:assembleDebug
```

日常功能联调在 **8GB 模拟器**上进行（TXT/Markdown 导入与拒答等）；性能与发版数字只认验证参考真机（见 verification），**不是**「只支持某一颗 SoC」，也**不是**「真机 8GB 已验证」。

侧载 Debug APK（`*.apk` 不进 Git，经 GitHub Release 分发）：

```text
./scripts/prepare-embedding-model.sh   # 若尚无 e5 assets
./scripts/build-test-apk.sh            # → android/dist/OrynodeMobileAI-*-debug.apk
```

包名 `ai.orynode.mobile.debug`。APK 含 embedding assets，不含 Gemma（首次需导入或下载 `.litertlm`）。
