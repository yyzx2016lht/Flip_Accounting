# 项目全面代码审计总结

**审计时间**: 2026-06-22
**项目**: FlipAccounting-AI
**审计范围**: 全部源码（Android App 230 文件 + Server 116 文件）

## 统计概览

| 指标 | 数量 |
|------|------|
| 总发现数 | 122 |
| 🔴 Critical | 10 |
| 🟠 High | 32 |
| 🟡 Medium | 52 |
| 🟢 Low | 28 |
| 经验证确认 | 8 |
| 被驳回 | 7 |

## 各模块发现分布

| 模块 | 发现数 |
|------|--------|
| AI & Chat | 10 |
| Activity/Service | 17 |
| 数据层 | 16 |
| 业务逻辑 | 14 |
| UI 层 | 17 |
| Tap 引擎 | 17 |
| Server | 18 |
| Prefs/工具 | 13 |

## 需要立即关注的问题

1. **[HIGH]** Memory/coroutine leak: AiAssistant.scope never cancelled — `app/src/main/java/com/taostudio/tapaccounting/AiAssistant.kt`
2. **[CRITICAL]** Fragment commitNow() after state save causes IllegalStateException — `app/src/main/java/com/taostudio/tapaccounting/MainActivity.kt`
3. **[HIGH]** Unmanaged CoroutineScope leaks in AppListActivity — `app/src/main/java/com/taostudio/tapaccounting/AppListActivity.kt`
4. **[HIGH]** Unmanaged CoroutineScope leak in LocalAsrService download/install — `app/src/main/java/com/taostudio/tapaccounting/LocalAsrService.kt`
5. **[HIGH]** BillDao.clearCategoryByName is a no-op query — `app/src/main/java/com/taostudio/tapaccounting/data/local/dao/BillDao.kt`
6. **[HIGH]** mergeRestoreFullData does not remap chat message bill references — `app/src/main/java/com/taostudio/tapaccounting/data/repository/BackupRepository.kt`
7. **[HIGH]** Gson deserialization ignores Kotlin default values, causing potential NPE — `app/src/main/java/com/taostudio/tapaccounting/data/backup/DataExportManager.kt`
8. **[CRITICAL]** handleSave has no re-entrancy guard -- double-tap or concurrent async callbacks create duplicate bills — `app/src/main/java/com/taostudio/tapaccounting/logic/AccountingFormController.kt`

## 审计报告索引

- [01-AI & Chat](01-ai-chat-bugs.md)
- [02-Activity/Service](02-activity-service-bugs.md)
- [03-数据层](03-data-layer-bugs.md)
- [04-业务逻辑](04-business-logic-bugs.md)
- [05-UI 层](05-ui-fragment-bugs.md)
- [06-Tap 引擎](06-tap-engine-bugs.md)
- [07-Server](07-server-module-bugs.md)
- [08-Prefs/工具](08-security-crypto-bugs.md)
- [09-经确认的 Bug](09-critical-confirmed.md)
