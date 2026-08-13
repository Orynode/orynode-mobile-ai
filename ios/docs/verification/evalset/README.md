# 预注册题集（evalset）

路径：`ios/docs/verification/evalset/`  
合同：[`verification.md`](../verification.md)  
版本：见 `MANIFEST.json` 的 `version`（当前为 **0.2.0-seed**，含 4 个文本 PDF，`frozen=false`）。

## 用途

在改 embedding、切块、融合权重或拒答阈值之前，用**同一套冻结资料 + 问题**做真机/同机构建对比：

- FTS-only / vector-only / hybrid
- Recall@5、MRR@10、无答案最高分分布
- 引用能否回到标注的文件与页/标题附近
- 无答案与冲突题必须拒答

人工 smoke ≠ 本题集闸门。

## 目录

```text
evalset/
  MANIFEST.json              # 版本、目标数量、语料清单
  README.md                  # 本文
  schema/question.schema.json
  corpus/
    txt/                     # 3
    markdown/                # 3
    pdf/                     # 4 文本层 PDF + sources/ + 生成脚本说明
    failures/                # 待补扫描/加密/损坏样本
  questions/
    answered.jsonl           # 目标 ≥30；seed 12
    paraphrase_sparse.jsonl  # 目标 ≥15；seed 7
    no_answer_conflict.jsonl # 目标 ≥15；seed 5
  reports/                   # 跑分结果归档（模板见 TEMPLATE.md）
  scripts/
    validate_evalset.py
    generate_text_pdfs.swift
```

## 快速检查

```bash
python3 ios/docs/verification/evalset/scripts/validate_evalset.py
```

通过条件：JSON 合法、引用文件存在、seed 数量与 `MANIFEST.seed_counts` 一致。  
**达不到** `targets` 时脚本以警告退出码 `0` 并打印缺口；加 `--strict` 则按发布目标硬失败。

## Seed 试跑

见 [`SEED_RUN.md`](./SEED_RUN.md)：

1. 自动检索：`EvalsetSeedRetrievalTests`（模拟器 / hash embedding Hit@5；含 TXT/MD/PDF）
2. 真机手工：导入 10 个语料后按表提问（生成 + 拒答）

自动通过 ≠ 发版闸门；真机应用的是 Core ML E5 + Gemma。

## 出题约定

每条题一行 JSON（JSONL），字段见 `schema/question.schema.json`。

| `kind` | 含义 | 期望 `expect.behavior` |
|---|---|---|
| `answered` | 资料内有明确答案 | `answer` |
| `paraphrase` | 同义改写 | `answer` |
| `sparse` | 关键词稀疏 | `answer` |
| `no_answer` | 资料外 | `refuse` |
| `conflict` | 资料内冲突且无法裁决 | `refuse` |

有答案题必须写 `expect.evidence[]`：`document` + `must_contain`（至少一处原文锚点）。PDF 题再补 `locator.page`（文档序页）。

## 跑一轮（人工 / 半自动）

1. Release 真机，记录 commit、系统版本、模型文件 SHA-256、`embedding_index_version`。
2. 清空知识库，仅导入本 evalset 的 corpus（含日后补齐的 PDF）。
3. 按 `questions/*.jsonl` 提问；检索阶段可先只评 hit@5（不依赖生成质量）。
4. 生成阶段再评：是否拒答、citation 是否落在 `allowed_documents`、摘录是否含 `must_contain`。
5. 把指标写入 `reports/<date>-<commit>.md`（复制 `reports/TEMPLATE.md`）。

## 扩到发布规模

按 `MANIFEST.targets` 补满后：

1. 更新 `seed_counts` → 实际 counts，设 `frozen: true`，升 `version`（如 `1.0.0`）。
2. 补齐 `corpus/pdf` 与 `corpus/failures`。
3. 全量重跑并归档报告；之后改融合/切分必须升索引版本并重跑。

## 不要

- 把用户私人文档放进本目录
- 只贴成功样例当报告
- 在 `frozen=true` 后静默改题干或锚点（应升版本）
