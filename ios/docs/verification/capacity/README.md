# 10,000 chunks 容量与恢复闸门

合同：[`../verification.md`](../verification.md)「10,000 chunks 容量与恢复闸门」。  
**性质**：发版硬闸门；模拟器 / hash embedding 单测只能证明机制，不能代替真机 E5 报告。

## 产物

| 路径 | 说明 |
|---|---|
| `scripts/generate_10k_corpus.py` | 生成恰好 N 个 heading 节的 Markdown（默认 10000） |
| `generated/` | 生成物（**不入库**；见 `.gitignore`） |
| `CAPACITY_RUN.md` | 真机勾选清单 |
| `reports/TEMPLATE.md` | 归档模板 |

## 生成语料

```bash
# 恰好 10000 chunks（约 1–2 MB）
python3 ios/docs/verification/capacity/scripts/generate_10k_corpus.py

# 越界探针（1 chunk）
python3 ios/docs/verification/capacity/scripts/generate_10k_corpus.py \
  --chunks 1 --out ios/docs/verification/capacity/generated/overflow_1.md

# 可选：先用 1000 做流程演练
python3 ios/docs/verification/capacity/scripts/generate_10k_corpus.py --chunks 1000
```

把 `generated/capacity_10000.md` 与 `generated/overflow_1.md` 拷到 iPhone（AirDrop / Files）。

## DEBUG 中断注入（可选，Debug 构建）

Xcode Scheme → Run → Arguments Passed On Launch：

```text
-KBFailAtFraction 0.1
```

可选 `0.5` / `0.9`。触发一次后自动解除，随后点「重试索引」应能跑完。  
**发版证明仍须**用强制终止 App 各做一轮（见 `CAPACITY_RUN.md`），不能只靠注入。

## 自动化（机制证明）

```bash
cd ios
xcodebuild test -scheme OrynodeMobileAI \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:OrynodeMobileAITests/KnowledgeBaseTests/testTenThousandChunkHardCapAndOverflowReject \
  -only-testing:OrynodeMobileAITests/KnowledgeBaseTests/testIndexCheckpointResumesAfterInjectedEmbedFailures
```
