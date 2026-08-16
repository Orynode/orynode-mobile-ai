# Orynode Mobile AI

完全运行在手机上的私人 AI 知识库：文档、索引、问题和回答都不离开设备。

- 产品定位：随身本地 AI 知识库，面向个人资料的即时检索、问答与来源核对
- 与 [Orynode Local AI](https://github.com/Orynode/orynode-local-ai) 的分工：Mac 是私有 AI 工作站与服务器；Mobile 是完全独立、随身使用的本地知识库
- 隐私承诺：无账号、无遥测、无联网检索、无云端 fallback；**模型权重可在准备页经镜像下载，进入知识库导入/索引/检索/生成路径后不联网**
- 许可证：[MIT](./LICENSE)（整仓）
- **iOS 工程入口**：[ios/README.md](./ios/README.md)
- **Android 工程入口**：[android/README.md](./android/README.md)

首版可证伪场景：**导入私人文档 → 本机建立索引 → 离线提问 → 回答附可核对来源**。证据不足时必须拒答。

## 平台状态

| 平台 | 状态 | 说明 |
|---|---|---|
| **iOS** | 已实现（Spike） | SwiftUI + Gemma 4 E2B + LiteRT-LM + SQLite FTS5/向量检索；真机基线 iPhone 16 Pro |
| **Android** | Spike 已接线（真机闸门未完成） | Compose + Gemma 4 E2B + LiteRT-LM + multilingual-e5 embedding + SQLite FTS5；PDF/OOXML/OCR 已接线。**无芯片白名单**（minSdk 26+）；建议真机 ≥12GB RAM。发版数字只认 [verification](./android/docs/verification.md) 参考真机（**尚未勾选**）。入口：[android/README.md](./android/README.md) |
| **Flutter** | 规划中 | 尚未开工；不作为首版交付路径 |

### iOS

<p align="center">
  <img src="ios/docs/images/welcome.jpg" alt="欢迎页" width="180" />
  <img src="ios/docs/images/home.jpg" alt="知识库首页" width="180" />
  <img src="ios/docs/images/chat.jpg" alt="问答" width="180" />
  <img src="ios/docs/images/setting.jpg" alt="设置" width="180" />
</p>

<p align="center"><sub>欢迎页 · 知识库 · 问答 · 设置 — 详见 [ios/README.md](./ios/README.md)</sub></p>

本 README 只说明**整个 Mobile AI 产品与多平台规划**。各端文档在对应子目录；iOS 见 [ios/README.md](./ios/README.md)（`docs/` + `NOTICE`）。

## 仓库结构（当前）

```text
.
├── LICENSE              # 整仓 MIT
├── README.md            # 本文件：产品与平台规划
├── ios/                 # 已落地原生 iOS（docs/ · NOTICE · Sources/ …）
├── android/             # 原生 Android Spike → 见 android/README.md
└── flutter/             # 占位 → 见 flutter/README.md
```

各端开工后自行维护子目录 README / docs / NOTICE；根目录不混写端侧细节。
