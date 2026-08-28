# FlipAccounting（敲敲记账）

FlipAccounting 是一款 AI 驱动的 Android 记账应用，支持自然语言、语音、图片和截图记账，并在本地完成规则校验、账单处理与 Room 数据持久化。

## 当前架构

仓库唯一参与构建的产品模块是 Gradle `:app`。它同时包含 Android UI、业务逻辑、本地数据和外部 AI Provider 接入；当前没有独立部署的后端。

`archive/legacy-server/` 是从旧工程保留下来的 Android 内嵌服务器源码，从未被当前 `settings.gradle.kts` 引入，不参与 App 构建或运行。

## 主要功能

- 自然语言、语音、OCR 和截图记账
- 多账本、预算、资产、退款和周期账单
- AI 查询与对话式账单修正
- 本地备份、WebDAV 与共享账本同步
- 敲击/翻转手势和悬浮窗快速记账

## 目录

```text
app/                         当前 Android 产品模块
  src/main/                  生产源码与 Android 资源
  src/test/                  JVM 单元测试
  schemas/                   Room schema 历史

docs/
  operations/                发布与设备运维说明
  archive/                   仅供追溯的旧需求、计划和审计

tools/android/kernelsu/      可选的 Root/KernelSU 保活工具
store-assets/                应用商店素材，不进入 APK
archive/legacy-server/       不参与构建的旧服务器源码
.agents/                     本仓库使用的 Agent skills
gradle/                      Gradle wrapper 与版本目录
```

## 构建与验证

Windows PowerShell：

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

文档入口见 [`docs/README.md`](docs/README.md)。领域术语见 [`CONTEXT.md`](CONTEXT.md)。

## 本地文件

`local.properties`、签名配置、API 配置和 `.tmp_adb_stats/` 真机数据均属于本地文件，不应提交到 Git。
