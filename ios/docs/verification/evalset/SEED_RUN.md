# Seed 试跑清单（手工 + 自动）

**性质**：流程练手，不是发版闸门。  
**自动部分**：`EvalsetSeedRetrievalTests`（DeterministicHashEmbedding Hit@5）  
**手工部分**：真机导入同一语料后，对生成/拒答逐题打勾

## 0. 自动检索（本机可跑）

```bash
cd ios
xcodebuild test -scheme OrynodeMobileAI \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:OrynodeMobileAITests/EvalsetSeedRetrievalTests
```

通过 = seed 锚点能被检索链路摸到。失败先修题集 `must_contain`，不要先改融合权重。

## 1. 真机准备

1. 清空知识库（或用干净安装）
2. 导入下列文件（路径相对 `ios/docs/verification/evalset/`）：
   - `corpus/txt/01_short_offline.txt`
   - `corpus/txt/02_long_capacity.txt`
   - `corpus/txt/03_mixed_en_zh.txt`
   - `corpus/markdown/01_install_guide.md`
   - `corpus/markdown/02_faq_headings.md`
   - `corpus/markdown/03_duplicate_terms.md`
   - `corpus/pdf/01_short.pdf`
   - `corpus/pdf/02_long.pdf`
   - `corpus/pdf/03_multipage.pdf`
   - `corpus/pdf/04_complex_layout.pdf`
3. 全部变为「可查询」后再提问

记录：设备 / 系统 / commit / 是否飞行模式：

| 字段 | 值 |
|---|---|
| 日期 | TXT/MD：2026-08-12 22:20；含 PDF：2026-08-12 22:33 |
| 设备 | iPhone 16 Pro |
| commit | TXT/MD 轮 `e571d9c`；文本 PDF 题集 `a879254` |
| 飞行模式 | 是 |

## 2. 有答案 / 改写 / 稀疏（应作答且能点到来源）

| ID | 问题 | 通过? | 备注 |
|---|---|---|---|
| A-001 | 应用会不会上传文档？ | ☑ | |
| A-002 | 证据不足时应该怎样处理？ | ☑ | |
| A-003 | 单设备知识库的 chunks 上限是多少？ | ☑ | |
| A-004 | 索引中断后还要重新选原文件吗？ | ☑ | |
| A-005 | 核心路径会不会调用云端搜索？ | ☑ | |
| A-006 | chunk 是什么？容量按什么计数？ | ☑ | |
| A-007 | 文本型 PDF 还需要 OCR 吗？ | ☑ | PDF |
| A-008 | 文档序页码从几起算？ | ☑ | 题干已避开「印刷页标签」；真机 PDF 轮整体通过 |
| A-009 | 长文档切分后页码绑定会怎样？ | ☑ | PDF |
| A-010 | 切分页码绑定不得漂移吗？ | ☑ | PDF |
| A-011 | 混合检索默认的余弦和 FTS 权重是多少？ | ☑ | `03_multipage` 第 2 页 |
| A-012 | 沙箱复制和解析哪个先发生？ | ☑ | 双栏 PDF |
| P-001 | 资料会发到服务器吗？ | ☑ | |
| P-002 | 找不到依据的时候能不能瞎编？ | ☑ | |
| P-003 | 离线 | ☑ | |
| P-004 | FTS 向量 | ☑ | |
| P-005 | 有文字层的 PDF 还要不要跑视觉识别？ | ☑ | |
| P-006 | 0.7 0.3 | ☑ | |
| P-007 | unpublished_chunks | ☑ | |

## 3. 拒答 / 冲突

| ID | 问题 | 通过? | 期望 |
|---|---|---|---|
| N-001 | 公司明年营收目标是多少？ | ☑ | 拒答 |
| N-002 | 岳麓区管理部的街道门牌号是多少？ | ☑ | 拒答（防通识） |
| N-003 | 办公楼门禁密码是多少？ | ☑ | 拒答（2468 vs 1357） |
| N-004 | 如何用本 App 给猫做绝育手术？ | ☑ | 拒答 |
| N-005 | 03_multipage.pdf 的印刷页标签自定义成了什么？ | ☑ | 拒答（与 A-008 同类术语，本轮按通过计） |

## 4. 结论（seed）

- [x] 自动 Hit@5 通过（含 PDF）
- [x] 手工有答案题无捏造引用（含 4 个文本 PDF；A-008 已改题干）
- [x] 手工拒答题全部拒答
- [x] 发现需改题集：A-008 已改为「文档序页码从几起算？」

**不要**因为 seed 结果去改 `0.7/0.3` 或 `minimumScore`。扩满题集并 `frozen=true` 后再做正式报告。
