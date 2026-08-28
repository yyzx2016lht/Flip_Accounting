# TapTap 双击背板检测功能移植指南

## 概述

[TapTap](https://github.com/KieronQuinn/TapTap) 是一个 Android 应用，移植了 Google Pixel 的双击背板手势检测功能（内部代号 "Columbus"）。本文档记录了如何将 TapTap 的核心检测逻辑移植到任意 Android 项目中。

**核心发现**：TapTap 实际运行在一个"混合模式"下——使用启发式模式的信号处理参数，配合 ML 模式的推理逻辑。这个细节在源码中并不明显，但对功能正常运行至关重要。

---

## 架构总览

```
传感器事件 (加速度计 + 陀螺仪)
    ↓
重采样器 (Resample3C) — 将不规则传感器数据插值为固定间隔
    ↓
信号处理链 (Slope → Lowpass → Highpass)
    ↓
峰值检测器 (PeakDetector) — 检测敲击产生的信号峰值
    ↓
特征提取 (300维向量: 6轴 × 50采样点)
    ↓
TFLite 模型推理 — 7分类 (Front/Back/Left/Right/Top/Bottom/Others)
    ↓
双击判定 — 两次 "Back" 检测间隔 100-500ms
```

---

## 需要移植的文件

### 核心信号处理（来自 `columbus` 模块）

从 `columbus/src/main/java/com/google/android/columbus/sensors/` 复制：

| 文件 | 作用 |
|------|------|
| `EventIMURT.kt` | IMU 运行时基类，持有所有滤波器和缓冲区 |
| `TapRT.kt` | 手势识别核心，包含 ML 和启发式两条路径 |
| `GestureSensor.kt` | 传感器抽象基类 |
| `TfClassifier.kt` | TFLite 分类器基类 |
| `PeakDetector.kt` | 峰值检测器 |
| `Resample1C.kt` | 单通道重采样器 |
| `Resample3C.kt` | 三轴重采样器 |
| `Slope1C.kt` | 单通道斜率（微分） |
| `Slope3C.kt` | 三轴斜率 |
| `Lowpass1C.kt` | 单通道低通滤波器 |
| `Lowpass3C.kt` | 三轴低通滤波器 |
| `Highpass1C.kt` | 单通道高通滤波器 |
| `Highpass3C.kt` | 三轴高通滤波器 |
| `Point3f.kt` | 三维点数据类 |
| `Sample3C.kt` | 采样点数据类 |
| `Util.kt` | 工具类（argmax） |

### 分类器实现（来自 `app` 模块）

从 `app/src/main/java/com/kieronquinn/app/taptap/components/columbus/sensors/` 复制并简化：

| 文件 | 作用 |
|------|------|
| `TfClassifier.kt` | 包含 `predict11()` 和 `predict12()` 方法 |
| `TapTfClassifier.kt` | 从 assets 加载模型并推理 |

### 模型文件

从 `columbus/src/main/assets/columbus/` 复制到你的 `app/src/main/assets/columbus/`：

```
assets/
  columbus/
    12/
      tap7cls_coral.tflite     (12KB) — Pixel 4 XL
      tap7cls_flame.tflite     (12KB) — Pixel 4
      tap7cls_redfin.tflite    (13KB) — Pixel 5
      tap7cls_bramble.tflite   (13KB) — Pixel 4a (5G)
```

这些小模型（12-13KB）在大多数设备上都能工作，不局限于 Pixel。

### 依赖

```gradle
implementation("org.tensorflow:tensorflow-lite:2.14.0")
```

---

## 关键配置参数（混合模式）

这是移植成功的核心。TapTap 使用了一种不直观的"混合模式"：

**初始化参数**（来自启发式模式 `startListening(true)`）：
```kotlin
lowpassKey.setPara(0.2f)          // 关键信号低通滤波 alpha
highpassKey.setPara(0.2f)         // 关键信号高通滤波 alpha
positivePeakDetector.setMinNoiseTolerate(0.05f)  // 峰值噪声容忍度
positivePeakDetector.setWindowSize(64)            // 峰值检测窗口大小
```

**处理模式**（使用 ML 模式 `isHeuristic=false`）：
```kotlin
updateData(sensorType, x, y, z, timestamp, interval, false)
// isHeuristic=false → 调用 updateML() → recognizeTapML() → TFLite 推理
```

**其他固定参数**：
```kotlin
sizeWindowNs = 160000000L    // 信号处理窗口 160ms
samplingIntervalNs = 2500000L // 重采样间隔 2.5ms (400Hz)
sensorDelay = 0               // SENSOR_DELAY_FASTEST
sizeFeatureWindow = 50         // 每轴特征采样点数
numberFeature = 300            // 总特征维度 (6轴 × 50)
```

### 为什么是混合模式？

原始 `GestureSensorImpl.startListening()` 有两个分支：

```kotlin
if (heuristicMode) {
    // 启发式模式：windowSize=64, alpha=0.2f, noise=0.05f
    // 传感器延迟: SENSOR_DELAY_FASTEST (0)
    // reset(false) — 特征向量初始化为 300 个零
} else {
    // ML 模式：windowSize=8, alpha=1.0/0.3f, noise=0.02f
    // 传感器延迟: 21000us (~48Hz)
    // reset(true) — 特征向量清空
}
```

但 `updateData()` 的 `isHeuristic` 参数是独立的。TapTap 的 `TapTapGestureSensorImpl` 调用 `super.startListening(heuristicMode)` 时传入的 `heuristicMode` 值，与传感器事件中的 `isRunningInLowSamplingRate`（始终为 false）无关。

通过调试日志确认，原始 TapTap 在设备上实际运行时：
- `peakId` 从 63 开始递减（说明 `windowSize=64`，即启发式模式参数）
- 推理在 `peakId=12` 时触发（ML 模式的 `recognizeTapML()` 逻辑）
- 模型输出置信度 ~0.999

**结论**：必须使用启发式模式的参数初始化，配合 ML 模式的处理逻辑。

---

## 传感器配置

```kotlin
// 注册加速度计和陀螺仪
val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)  // type=1
val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)          // type=4

// 使用 SENSOR_DELAY_FASTEST (0)，让系统以最高频率投递事件
sensorManager.registerListener(listener, accelerometer, 0, handler)
sensorManager.registerListener(listener, gyroscope, 0, handler)

// 重采样间隔固定为 2.5ms (400Hz)，与实际传感器频率无关
val SAMPLING_INTERVAL_NS = 2500000L
```

重采样器会将不规则的传感器数据插值为固定 400Hz 的采样点，确保信号处理的一致性。

---

## 信号处理流程

### 1. 重采样 (Resample3C)

将传感器数据线性插值到固定 2.5ms 间隔。每次传感器事件可能产出 0-N 个重采样点。

### 2. 斜率计算 (Slope3C)

计算信号的离散微分：`delta = value * scale - lastValue`，其中 `scale = 2500000 / actualInterval`。

### 3. 低通 + 高通滤波

- **低通滤波器**：`y = alpha * x + (1-alpha) * y_prev`
- **高通滤波器**：`y = alpha * (x - x_prev) + alpha * y_prev`

两者的 `alpha` 参数都设为 `0.2f`，用于提取敲击产生的瞬态信号。

### 4. 峰值检测 (PeakDetector)

在滑动窗口内跟踪最大峰值及其位置。当新值超过当前最大峰值（且超过噪声容忍度）时，记录新峰值。

- `windowSize = 64`：峰值位置从 63 开始递减
- `minNoiseTolerate = 0.05f`：自适应噪声容忍度 `max(0.05, amplitude/5)`

### 5. ML 推理 (recognizeTapML)

当 `peakId` 递减到 12 时触发推理：

```kotlin
if (adjustedMajorPeakId >= 0     // peakId >= 6
    && adjustedT >= 0             // 时间对齐
    && peakId + 50 < zAccSize     // 缓冲区足够大
    && 50 + adjustedT < zAccSize  // 陀螺仪数据足够
    && _wasPeakApproaching        // 峰值正在逼近
    && peakId <= 12) {            // 在对齐窗口内
    
    // 构建 300 维特征向量
    // 调用 TFLite 推理
    // result = argmax(prediction)
}
```

`_wasPeakApproaching` 标志确保推理只在峰值递减过程中触发，避免重复推理。

### 6. 双击判定 (checkDoubleTapTiming)

维护一个时间戳队列 `_tBackTapTimestamps`：

```kotlin
// 两次 "Back" 检测间隔 100ms-500ms = 双击
mMinTimeGapNs = 100000000L  // 100ms
mMaxTimeGapNs = 500000000L  // 500ms
```

---

## 常见问题

### Q: 缓冲区只有 7 个样本，不增长

**原因**：使用了 ML 模式的初始化参数（`windowSize=8`），导致 `sizeWindow` 计算为很小的值。

**解决**：使用启发式模式的参数（`windowSize=64`）。

### Q: peakId 始终为负数，推理从不触发

**原因**：峰值检测器没有找到超过噪声容忍度的峰值。

**解决**：将 `minNoiseTolerate` 设为 `0.05f`（而非 ML 模式的 `0.02f`）。

### Q: 推理只运行一次，之后不再触发

**原因**：`_wasPeakApproaching` 标志在第一次推理后被设为 `false`，且永远不会被重置为 `true`（因为 `peakId > 12` 的条件在 `windowSize=8` 时永远不满足）。

**解决**：使用 `windowSize=64`，让 `peakId` 可以达到 63，从而满足 `peakId > 12` 的重置条件。

### Q: 模型推理置信度很低

**原因**：使用了不匹配的模型或错误的滤波参数。

**解决**：使用 `coral`/`flame`/`redfin`/`bramble` 小模型（12-13KB），配合 `alpha=0.2f` 的滤波参数。

---

## 最终配置速查

```kotlin
class TapDetector(context: Context, sensorManager: SensorManager, onDoubleTap: () -> Unit) {

    fun start() {
        val classifier = TapTfClassifier(context.assets, "columbus/12/tap7cls_coral.tflite")
        
        val tap = TapRT(160000000L).apply {
            setClassifier(classifier)
            getLowpassKey().setPara(0.2f)                    // 关键！不是 1.0
            getHighpassKey().setPara(0.2f)                   // 关键！不是 0.3
            getPositivePeakDetector().setMinNoiseTolerate(0.05f)  // 关键！不是 0.02
            getPositivePeakDetector().setWindowSize(64)            // 关键！不是 8
            reset(false)  // 关键！不是 true
        }
        
        // SENSOR_DELAY_FASTEST (0)
        sensorManager.registerListener(listener, accelerometer, 0, handler)
        sensorManager.registerListener(listener, gyroscope, 0, handler)
    }

    fun onSensorChanged(event: SensorEvent) {
        tap.updateData(event.sensor.type, x, y, z, event.timestamp, 2500000L, false)
        // isHeuristic=false → ML 模式处理
        
        if (tap.checkDoubleTapTiming(event.timestamp) == 2) {
            onDoubleTap()
        }
    }
}
```

---

## 调试建议

如果检测不工作，按以下顺序排查：

1. **确认传感器事件频率**：加速度计和陀螺仪都应有数据
2. **确认重采样器工作**：`_zsAcc.size` 应增长到 64
3. **确认峰值检测**：`peakId` 应从 63 开始递减
4. **确认推理触发**：`peakId` 递减到 12 时应触发推理
5. **确认模型输出**：`Back` 类的置信度应接近 1.0

在 `processAccAndKeySignal()` 和 `recognizeTapML()` 中添加日志即可追踪整个流程。

---

## 参考

- TapTap 源码：https://github.com/KieronQuinn/TapTap
- Columbus 模块路径：`columbus/src/main/java/com/google/android/columbus/sensors/`
- 模型路径：`columbus/src/main/assets/columbus/12/`
- TensorFlow Lite：https://www.tensorflow.org/lite
