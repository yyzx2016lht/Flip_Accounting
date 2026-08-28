# 项目全面代码审计报告

**生成时间**: 2026-06-22
**项目**: FlipAccounting-AI
**范围**: 全部源码（Android App 230 文件 + Server 116 文件）
**审查方式**: 16 个 AI Agent 并行深读全部源码

## 总体统计

| 维度 | 总发现 | 🔴 Critical | 🟠 High | 🟡 Medium | 🟢 Low |
|------|--------|-------------|---------|-----------|--------|
| 代码级 Bug | 122 | 10 | 32 | 52 | 28 |
| 业务逻辑正确性 | 48 | 4 | 11 | 19 | 14 |
| 性能优化 | 21 | 0 | 5 | 11 | 5 |
| Android 最佳实践 | 22 | 0 | 7 | 11 | 4 |
| 死代码 & 重复 | 17 | 0 | 3 | 9 | 5 |
| **合计** | **230** | **14** | **58** | **102** | **56** |

## 审计报告索引

### 第一批：代码级 Bug 猎手（8 个 Agent）

| 文档 | 内容 | 发现数 |
|------|------|--------|
| [01-ai-chat-bugs.md](01-ai-chat-bugs.md) | AI 服务 & 聊天模块 | 10 |
| [02-activity-service-bugs.md](02-activity-service-bugs.md) | Activity / Service / 广播 | 13 |
| [03-data-layer-bugs.md](03-data-layer-bugs.md) | 数据层（Room DB / Repository / 备份） | 17 |
| [04-business-logic-bugs.md](04-business-logic-bugs.md) | 业务逻辑（账单 / 资产 / 货币） | 16 |
| [05-ui-fragment-bugs.md](05-ui-fragment-bugs.md) | UI 层（Fragment / ViewModel / Dialog） | 14 |
| [06-tap-engine-bugs.md](06-tap-engine-bugs.md) | Tap 检测引擎 | 14 |
| [07-server-module-bugs.md](07-server-module-bugs.md) | Server 模块 | 14 |
| [08-security-crypto-bugs.md](08-security-crypto-bugs.md) | Preferences / 工具类 / 跨模块 | 12 |
| [09-critical-confirmed.md](09-critical-confirmed.md) | 经对抗验证确认的 Critical/High 级 Bug | 8 确认 / 7 驳回 |
| [10-summary.md](10-summary.md) | Bug 审计总结 | — |

### 第二批：业务逻辑正确性审计（5 个 Agent）

| 文档 | 内容 | 发现数 |
|------|------|--------|
| [11-logic-correctness.md](11-logic-correctness.md) | 公式、计算、边界、数据流一致性 | 48 |

### 第三批：性能 / 最佳实践 / 死代码（3 个 Agent）

| 文档 | 内容 | 发现数 |
|------|------|--------|
| [12-performance.md](12-performance.md) | 性能优化 | 21 |
| [13-android-practices.md](13-android-practices.md) | Android 最佳实践 | 22 |
| [14-dead-code.md](14-dead-code.md) | 死代码 & 代码重复 | 17 |

## 🔴 需要立即关注的 Critical 级问题

| # | 来源 | 问题 | 文件 |
|---|------|------|------|
| 1 | 逻辑审计 | 快照余额推导使用实时汇率而非历史汇率 | AssetBillBalanceHistory.kt |
| 2 | 逻辑审计 | 转账目标端 delta 未四舍五入 | BillAssetImpactService.kt |
| 3 | 逻辑审计 | 编辑账单资产影响在事务外应用，崩溃导致数据损坏 | AccountingFormController.kt |
| 4 | 逻辑审计 | 缺少数据库版本 1-5 的 Room 迁移，早期用户崩溃 | AppDatabase.kt |
| 5 | Bug 猎手 | AiAssistant CoroutineScope 永不取消，Context 泄漏 | AiAssistant.kt |
| 6 | Bug 猎手 | AudioRecord 竞态条件，停止录音时崩溃 | ChatAudioRecordController.kt |
| 7 | Bug 猎手 | displayMessages 多线程访问，IndexOutOfBoundsException | ChatActivity.kt |
| 8 | Bug 猎手 | 图片压缩失败时 Bitmap 未回收，OOM | ChatMediaController.kt |
| 9 | Bug 猎手 | Streaming 解析错误丢弃有效部分内容 | AIService.kt |
| 10 | Bug 猎手 | BackupPinCrypto 4 位 PIN 仅 10000 种组合 | BackupPinCrypto.kt |
| 11 | Bug 猎手 | WebDAV 凭据明文存储在 SharedPreferences | WebDavClient.kt |
| 12 | Bug 猎手 | allowBackup=true + cleartextTraffic=true | AndroidManifest.xml |
| 13 | Bug 猎手 | POST_NOTIFICATIONS 权限未运行时请求 | AndroidManifest.xml |
| 14 | Bug 猎手 | Bill entity 字段类型不一致 | Bill.kt |
