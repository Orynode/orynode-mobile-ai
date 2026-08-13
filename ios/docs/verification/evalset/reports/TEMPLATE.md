# Evalset 报告模板

复制为 `YYYY-MM-DD-<shortsha>.md` 后填写。不要写入用户私人文档内容。

## 元数据

| 字段 | 值 |
|---|---|
| 日期 | |
| 设备 | iPhone 16 Pro / 其他： |
| 系统版本 | |
| App 营销版本 / 构建号 | |
| Git commit | |
| 构建类型 | Release / Debug（发布只认 Release） |
| evalset 版本 | 见 MANIFEST.json |
| embedding 名 / indexVersion / 文件 SHA-256 | |
| 生成模型 / 文件 SHA-256 | |
| retrieval_version | hybrid-cosine0.7-fts0.3-v1 |
| chunker_version | |
| 拒答阈值 minimumScore | |

## 语料

- 导入文档数：
- 总 chunks：
- 失败样本是否按预期失败：

## 检索指标

| 模式 | Recall@5 | MRR@10 | 无答案最高分 p50/p95 | 备注 |
|---|---|---|---|---|
| FTS-only | | | | |
| vector-only | | | | |
| hybrid | | | | |

## 生成 / 引用（人工抽查）

| 集合 | 题数 | 通过 | 失败 ID |
|---|---|---|---|
| answered | | | |
| paraphrase/sparse | | | |
| no_answer/conflict 拒答 | | | |

失败说明（捏造引用、资料外事实、无答案强答均为阻断）：

## 性能抽记（可选，完整见内存闸门）

- 冷启动：
- 单查询 p50/p95：
- 峰值内存：

## 结论

- [ ] hybrid 不低于单路基线
- [ ] 拒答题全部拒答
- [ ] 可进入下一版对比 / 不可发版（说明原因）

批准人：
