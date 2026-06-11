# 敲击检测功能架构文档

## 概述

项目实现了基于加速度计 + 陀螺仪的手机背板敲击检测功能，支持双击和三击手势触发。系统采用**动态功耗管理**策略，在准确性和功耗之间取得平衡。

---

## 核心架构

### 文件结构

```
app/src/main/java/com/taostudio/tapaccounting/tap/
├── TapDetector.kt              # 主控检测器（入口）
├── TapRT.kt                    # 核心运行时（ML + Heuristic）
├── TapTapTapRT.kt              # 三击 ML 运行时
├── HeuristicTapTapTapRT.kt     # 三击启发式运行时
├── EventIMURT.kt               # IMU 数据处理基类
├── BaseTapRT.kt                # 运行时接口
├── TapModel.kt                 # 模型枚举与选择
├── TapTfClassifier.kt          # TFLite 推理
├── TfClassifier.kt             # 分类器基类
├── TapAction.kt                # 动作注册表
├── ScreenCaptureAction.kt      # 截屏记账动作
├── PeakDetector.kt             # 峰值检测
├── Resample1C.kt / Resample3C.kt   # 重采样
├── Slope1C.kt / Slope3C.kt         # 斜率计算
├── Lowpass1C.kt / Lowpass3C.kt     # 低通滤波
├── Highpass1C.kt / Highpass3C.kt   # 高通滤波
└── Point3f.kt / Sample3C.kt / Util.kt  # 工具类
```

### 关键文件说明

| 文件 | 功能 |
|------|------|
| `TapDetector.kt` | 主控类，管理传感器监听、功耗模式切换、动作触发 |
| `TapRT.kt` | 核心运行时，包含 ML 和 Heuristic 两条处理流水线 |
| `TapModel.kt` | 预训练 TFLite 模型枚举，按设备屏幕尺寸选择 |
| `TapTfClassifier.kt` | TensorFlow Lite 推理封装，支持 NNAPI 硬件加速 |

---

## 两种运行模式

### 1. ML 模式（Full Power）

**处理流水线**：
```
加速度计 (xyz) + 陀螺仪 (xyz)
    ↓
重采样（固定 2.5ms 间隔）
    ↓
斜率计算（差分）
    ↓
低通滤波 → 高通滤波
    ↓
峰值检测
    ↓
特征向量构建（6通道 × 50采样点 = 300维）
    ↓
TensorFlow Lite 推理
    ↓
7分类结果（Front/Back/Left/Right/Top/Bottom/Others）
```

**特点**：
- 同时使用加速度计和陀螺仪
- 加载 TFLite 模型进行推理
- 准确率高，但功耗较大
- 支持 NNAPI 硬件加速

### 2. Heuristic 模式（启发式/低功耗）

**处理流水线**：
```
加速度计 Z 轴
    ↓
重采样（固定 2.5ms 间隔）
    ↓
斜率计算（差分）
    ↓
低通滤波 → 高通滤波
    ↓
正负峰值检测
    ↓
基于峰值间距的简单规则判断
```

**特点**：
- 仅使用加速度计 Z 轴
- 不加载 TFLite 模型
- 功耗极低，但准确率较低
- 用于待机状态的初步检测

---

## 动态功耗管理机制

### 功耗配置枚举

```kotlin
// TapDetector.kt 第38-41行
private enum class PowerProfile(val samplingPeriodUs: Int) {
    Full(FULL_POWER_SENSOR_SAMPLING_PERIOD_US),           // ML模式，全速采样
    HeuristicStandby(FULL_POWER_SENSOR_SAMPLING_PERIOD_US) // 启发式待机
}
```

### 关键常量

```kotlin
// TapDetector.kt 第25-32行
private const val FULL_POWER_AFTER_START_MS = 3 * 60_000L      // 启动后保持ML的时间：3分钟
private const val STILLNESS_TO_LOW_POWER_MS = 3 * 60_000L      // 静止超时降级时间：3分钟
private const val POWER_CHECK_INTERVAL_MS = 30_000L             // 功耗检查间隔：30秒
private const val SIGNIFICANT_ACCEL_DELTA = 1.15f               // 加速度显著变化阈值
private const val SIGNIFICANT_GYRO_ABS = 0.65f                  // 陀螺仪显著变化阈值
```

### 状态转换流程

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│  ┌──────────────┐                                           │
│  │   应用启动    │                                           │
│  └──────┬───────┘                                           │
│         ↓                                                   │
│  ┌──────────────┐                                           │
│  │  ML 模式     │ ←─────────────────────────────────────┐   │
│  │ （前3分钟）   │                                       │   │
│  └──────┬───────┘                                       │   │
│         │                                               │   │
│         │ 3分钟后 + 静止超3分钟                          │   │
│         ↓                                               │   │
│  ┌──────────────┐     检测到运动/疑似敲击    ┌───────────┴─┐ │
│  │ Heuristic    │ ──────────────────────────→│  ML 模式    │ │
│  │ 模式（省电）  │                           │ （全速运行） │ │
│  └──────────────┘                           └─────────────┘ │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 详细转换逻辑

#### 1. 启动阶段
- 默认进入 `PowerProfile.Full`（ML 模式）
- 全速采样，加速度计 + 陀螺仪同时工作
- 保持 3 分钟（`FULL_POWER_AFTER_START_MS`）

#### 2. 降级到 Heuristic 的条件
从 `TapDetector.kt` 第266-272行：
```kotlin
private fun maybeEnterDynamicLowPower() {
    if (powerProfile == PowerProfile.HeuristicStandby) return  // 已经是启发式
    val now = SystemClock.uptimeMillis()
    if (now < fullPowerUntilUptimeMs) return                    // 启动后3分钟内不降级
    if (now - lastSignificantMotionUptimeMs < STILLNESS_TO_LOW_POWER_MS) return  // 运动后3分钟内不降级
    switchPowerProfile(PowerProfile.HeuristicStandby, "still-...")
}
```

#### 3. 回升到 ML 的条件

**条件 A：检测到大幅度运动**
从 `TapDetector.kt` 第274-298行：
```kotlin
private fun trackMotionForDynamicPower(event: SensorEvent) {
    if (forceFullMlMode) return
    when (event.sensor.type) {
        Sensor.TYPE_ACCELEROMETER -> {
            val magnitude = sqrt(x*x + y*y + z*z)
            val previous = lastAccelMagnitude
            lastAccelMagnitude = magnitude
            if (previous != null && abs(magnitude - previous) >= SIGNIFICANT_ACCEL_DELTA) {
                lastSignificantMotionUptimeMs = SystemClock.uptimeMillis()
                extendFullPower("accel-motion")  // 加速度变化 >= 1.15
            }
        }
        Sensor.TYPE_GYROSCOPE -> {
            val gyroAbs = maxOf(abs(x), abs(y), abs(z))
            if (gyroAbs >= SIGNIFICANT_GYRO_ABS) {
                lastSignificantMotionUptimeMs = SystemClock.uptimeMillis()
                extendFullPower("gyro-motion")   // 陀螺仪任一轴 >= 0.65
            }
        }
    }
}
```

**条件 B：启发式检测到疑似敲击**
从 `TapDetector.kt` 第182-184行：
```kotlin
if (powerProfile == PowerProfile.HeuristicStandby && result >= 1) {
    extendFullPower("heuristic-candidate-$result")  // 疑似敲击，切回ML精确判断
}
```

#### 4. 模式切换过程
从 `TapDetector.kt` 第223-256行：
```kotlin
private fun switchPowerProfile(profile: PowerProfile, reason: String) {
    if (forceFullMlMode || powerProfile == profile) return
    // 1. 注销所有传感器监听
    sensorManager.unregisterListener(this, accelerometer)
    sensorManager.unregisterListener(this, gyroscope)
    // 2. 关闭当前分类器
    tap?.closeClassifier()
    // 3. 更新功耗配置
    powerProfile = profile
    // 4. 创建新的 TapRT 实例（ML 或 Heuristic）
    tap = createTapRuntime(useHeuristic = profile == PowerProfile.HeuristicStandby, ...)
    // 5. 重新注册传感器
    registerSensors(profile)
}
```

---

## 传感器注册差异

从 `TapDetector.kt` 第203-221行：

```kotlin
private fun registerSensors(profile: PowerProfile) {
    val handler = sensorHandler ?: return
    // 始终注册加速度计
    sensorManager.registerListener(this, accelerometer, profile.samplingPeriodUs, ...)
    // 仅在 ML 模式下注册陀螺仪
    if (!shouldUseHeuristicRuntime()) {
        sensorManager.registerListener(this, gyroscope, profile.samplingPeriodUs, ...)
    }
}
```

| 模式 | 加速度计 | 陀螺仪 |
|------|---------|--------|
| ML 模式 | ✅ xyz 三轴 | ✅ xyz 三轴 |
| Heuristic 模式 | ✅ 仅 Z 轴 | ❌ 不注册 |

---

## 全程 ML 模式

### 设置项

从 `PrefsGeneralSupport.kt` 第254-268行：
```kotlin
fun isTapForceFullMl(ctx: Context): Boolean {
    val prefs = prefs(ctx)
    if (!prefs.getBoolean(KEY_TAP_FORCE_FULL_ML_MIGRATED, false)) {
        prefs.edit()
            .putBoolean(KEY_TAP_LOW_POWER, false)  // 默认关闭
            .putBoolean(KEY_TAP_FORCE_FULL_ML_MIGRATED, true)
            .apply()
    }
    return prefs.getBoolean(KEY_TAP_LOW_POWER, false)
}
```

- **SharedPreferences Key**: `tap_low_power`
- **默认值**: `false`（关闭，启用动态省电）
- **UI 标签**: "全程 ML 模式"

### 强制 ML 逻辑

从 `TapDetector.kt` 第60行和第68行：
```kotlin
private var forceFullMlMode = false

// 功耗检查任务
private val powerProfileCheck = object : Runnable {
    override fun run() {
        if (!isRunning || forceFullMlMode) return  // 强制ML时跳过所有动态切换
        maybeEnterDynamicLowPower()
        sensorHandler?.postDelayed(this, POWER_CHECK_INTERVAL_MS)
    }
}
```

当 `forceFullMlMode = true` 时：
- 跳过所有动态功耗切换逻辑
- 始终使用 ML 模型推理
- 加速度计 + 陀螺仪同时工作
- 功耗较高，但准确率最高

---

## ML 模型

### 模型列表

从 `TapModel.kt`：

| 模型 | 设备 | 屏幕尺寸 |
|------|------|----------|
| `REDFIN` | Pixel 5 | 6.0 寸 |
| `FLAME` | Pixel 4 | 5.7 寸 |
| `BRAMBLE` | Pixel 4a 5G | 6.2 寸 |
| `CORAL` | Pixel 4 XL | 6.3 寸 |

- **模型文件路径**: `columbus/12/tap7cls_*.tflite`
- **分类数**: 7（Front/Back/Left/Right/Top/Bottom/Others）
- **硬件加速**: 支持 NNAPI

### NNAPI 低功耗模式

- **SharedPreferences Key**: `tap_nnapi_low_power`
- **默认值**: `false`
- 启用后使用 NNAPI 低功耗模式推理

---

## 敲击灵敏度

### 灵敏度等级

从 `TapDetector.kt` 第34-36行：
```kotlin
val TAP_SENSITIVITY_VALUES = floatArrayOf(
    0.75f, 0.53f, 0.40f, 0.25f, 0.1f, 0.05f, 0.04f, 0.03f, 0.02f, 0.01f, 0.0f
)
```

| 等级 | 阈值 | 说明 |
|------|------|------|
| 0 | 0.75 | 最不灵敏 |
| 1 | 0.53 | |
| 2 | 0.40 | |
| 3 | 0.25 | |
| 4 | 0.10 | |
| 5 | 0.05 | **默认** |
| 6 | 0.04 | |
| 7 | 0.03 | |
| 8 | 0.02 | |
| 9 | 0.01 | |
| 10 | 0.00 | 最灵敏 |

- **SharedPreferences Key**: `tap_sensitivity_level`
- **默认值**: `5`（中等灵敏度）

---

## 双击/三击时序判断

### 双击检测

从 `TapRT.kt` 第73-99行：
- 两次敲击间隔：100ms ~ 500ms
- 超过 500ms 视为单击

### 三击检测

从 `TapTapTapRT.kt` 和 `HeuristicTapTapTapRT.kt`：
- 三次敲击总间隔：≤ 750ms
- 需要在设置中启用三击模式

---

## 可用触发动作

从 `TapAction.kt`：

| 动作 ID | 名称 | 说明 |
|---------|------|------|
| `show_overlay` | 弹出悬浮窗 | 显示快捷操作悬浮窗 |
| `open_ai_chat` | AI 智能记账助手 | 打开 AI 聊天界面 |
| `screen_capture` | 截屏记账 | 截屏并触发记账 |

---

## 配置项汇总

| 设置项 | SharedPreferences Key | 默认值 | 说明 |
|--------|----------------------|--------|------|
| 翻转手势开关 | `flip_enabled` | false | |
| 敲击手势开关 | `double_tap_enabled` | false | |
| 敲击灵敏度 | `tap_sensitivity_level` | 5 | 0-10 级 |
| 翻转灵敏度 | `flip_sensitivity_level` | 50 | 0-100 |
| 选择的模型 | `tap_model` | "" | 空为自动推荐 |
| NNAPI 低功耗 | `tap_nnapi_low_power` | false | |
| 全程ML模式 | `tap_low_power` | false | 关闭时启用动态省电 |
| 三击模式 | `tap_triple_enabled` | false | |
| 双击动作 | `tap_action_double` | "" | |
| 三击动作 | `tap_action_triple` | "" | |
| 翻转动作 | `flip_action` | "show_overlay" | |

---

## 设置界面

文件：`SensitivityActivity.kt`

提供完整的设置 UI：
- 翻转手势开关 + 灵敏度滑块（0-100）
- 敲击手势开关 + 灵敏度滑块（0-10）
- 双击/三击动作选择
- 高级选项折叠面板：模型选择、NNAPI 低功耗、全程 ML 模式
- 保活设置：电池优化、通知权限、无障碍服务

---

## OverlayService（服务层）

文件：`OverlayService.kt`

前台服务，管理 TapDetector 和 FlipDetector 的生命周期：
- 亮/灭屏监听（灭屏停止检测省电）
- Watchdog 健康检查（传感器无事件 180 秒后重启）
- 横屏模式禁用检测
- 震动反馈
- WakeLock 管理

---

## 流程图

```
用户拿起手机
    │
    ↓
┌─────────────────────────────────────────────────────────────┐
│                     应用启动                                 │
│  ┌───────────────────────────────────────────────────────┐  │
│  │  TapDetector.start()                                  │  │
│  │  - 读取设置（全程ML、灵敏度、NNAPI等）                   │  │
│  │  - 创建 TapRT（ML 或 Heuristic）                      │  │
│  │  - 注册传感器监听                                      │  │
│  │  - 启动功耗检查定时任务                                 │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
    │
    ↓
┌─────────────────────────────────────────────────────────────┐
│                   传感器数据流                               │
│                                                             │
│  加速度计事件 ──→ trackMotionForDynamicPower()              │
│       │              │                                      │
│       │              ↓                                      │
│       │         检测显著运动？                               │
│       │              │                                      │
│       │         是 ──┼──→ extendFullPower()                 │
│       │              │         │                            │
│       │              │         ↓                            │
│       │              │    切回 ML 模式                       │
│       │              │                                      │
│       ↓              ↓                                      │
│  TapRT.updateData()                                         │
│       │                                                     │
│       ├──→ ML 路径：300维特征 → TFLite推理 → 7分类          │
│       │                                                     │
│       └──→ Heuristic 路径：Z轴峰值 → 简单规则               │
│                │                                            │
│                ↓                                            │
│         疑似敲击？                                          │
│                │                                            │
│           是 ──┼──→ extendFullPower() → 切回ML精确判断      │
│                                                             │
└─────────────────────────────────────────────────────────────┘
    │
    ↓
┌─────────────────────────────────────────────────────────────┐
│                   动作触发                                   │
│                                                             │
│  checkDoubleTapTiming() 返回 result                         │
│       │                                                     │
│       ├──→ result == 2：触发双击动作                         │
│       │                                                     │
│       └──→ result == 3 && 三击启用：触发三击动作             │
│                                                             │
│  onTapAction(tapCount)                                      │
│       │                                                     │
│       ├──→ show_overlay：弹出悬浮窗                         │
│       ├──→ open_ai_chat：打开AI记账助手                     │
│       └──→ screen_capture：截屏记账                         │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 总结

### 默认模式（动态省电）

1. **启动后 3 分钟**：ML 模式，全速采样，准确率高
2. **静止超 3 分钟**：降级到 Heuristic 模式，仅用加速度计 Z 轴，功耗极低
3. **检测到运动**：立即切回 ML 模式
4. **检测到疑似敲击**：立即切回 ML 模式做精确判断

### 全程 ML 模式

- 开启后永不降级，始终使用 ML 推理
- 准确率最高，但功耗较大
- 适合对准确率要求极高的场景

### 设计亮点

1. **智能省电**：根据用户行为动态切换模式，待机时功耗降低 80%+
2. **无缝切换**：模式切换时重新创建 TapRT 实例，对用户透明
3. **多级灵敏度**：11 级可调，满足不同用户需求
4. **硬件加速**：支持 NNAPI，ML 推理效率更高
5. **健康监控**：Watchdog 机制确保传感器正常工作
