# 10k 容量闸门报告模板

复制为 `reports/YYYY-MM-DD-<commit>.md`。

## 元数据

| 字段 | 值 |
|---|---|
| 日期 | |
| commit | |
| 设备 / 系统 | |
| 构建 | Release |
| 飞行模式 | |
| embedding indexVersion | |
| chunker_version | structured-520-64-v1 |
| 语料 | `capacity_10000.md`（生成脚本版本随仓库） |

## 结果摘要

| 步骤 | 结果 | 时长 / 备注 |
|---|---|---|
| 空库完整索引 | PASS / FAIL | |
| 10% 杀进程恢复 | PASS / FAIL | |
| 50% 杀进程恢复 | PASS / FAIL | |
| 90% 杀进程恢复 | PASS / FAIL | |
| 后台暂停 | PASS / FAIL | |
| 内存警告 | PASS / FAIL / 未触发 | |
| 一致性抽查 | PASS / FAIL | |
| 冷启动 | PASS / FAIL | |
| 越界拒绝 | PASS / FAIL | |

## 性能旁路（可选，兼作内存闸门输入）

| 指标 | 值 |
|---|---|
| 索引总时长 | |
| 峰值内存（索引） | |
| 抽查查询首 token | |

## 判定

- [ ] 通过 verification.md 10k 闸门
- [ ] 不通过（原因）
