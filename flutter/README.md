# Flutter

Orynode Mobile AI 的 **Flutter** 客户端占位目录。

## 状态

**规划中，尚未实现。** 不作为首版交付路径。

当前已落地的原生端：[iOS](../ios/README.md)（Spike，真机闸门已有记录）、[Android](../android/README.md)（Spike 已接线，真机闸门未完成）。产品级平台规划见仓库根目录 [README.md](../README.md)。

## 后续实现时预期放入

- 工程入口 README（环境、构建、真机）
- `docs/`：与 iOS 对齐的产品能力说明（本机知识库、离线检索与引用、模型边界等）
- `NOTICE`：本端第三方组件说明
- Dart / 插件与平台通道相关源码

若最终选择 Flutter，需单独评估端侧推理与 OCR 的插件/原生桥接方案；在此之前以原生 iOS / Android 为参考实现。
