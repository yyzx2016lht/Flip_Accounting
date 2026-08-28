# Tap 检测引擎 Bug 审计

**共 17 个发现**: 🔴 3 Critical | 🟠 5 High | 🟡 6 Medium | 🟢 3 Low

## 🔴 Critical

### 1. TapRT.checkDoubleTapTiming: logic inversion causes double-tap to be detected on single tap, clears all state preventing triple-tap detection

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/tap/TapRT.kt`
- **行号**: 86-98
- **描述**: The second pass in checkDoubleTapTiming iterates timestamps and checks if (last - current) <= minTimeGapNs. When true it `continue`s (skips), when false it clears the deque and returns 2. This means: if ALL remaining taps are within minTimeGapNs of the last tap, the loop completes and returns 1 (pending). If ANY tap is farther than minTimeGapNs from the last tap, it immediately returns 2 (double-tap). The semantics are inverted -- it treats 'taps far apart' as confirmation of double-tap and 'taps close together' as pending, which is backwards. Additionally, clearing _tBackTapTimestamps unconditionally on double-tap detection destroys the history needed for triple-tap detection in subclasses that call super.checkDoubleTapTiming (HeuristicTapTapTapRT when isTripleTapEnabled=false).
- **影响**: Double-tap may fire prematurely when there is just one old stale tap in the buffer. Triple-tap detection is broken because state is cleared on double-tap. Users experience erratic tap detection behavior.
- **建议修复**: Rewrite the second-pass logic: instead of checking distance-from-last, check whether there exists a pair of consecutive taps separated by >= minTimeGapNs. Only clear the taps involved in the recognized gesture, not the entire deque.

### 2. TapTapTapRT.checkDoubleTapTiming: tapCount logic counts taps relative to last tap instead of consecutive pairs, causing triple-tap misclassification

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/tap/TapTapTapRT.kt`
- **行号**: 39-57
- **描述**: tapCount counts how many past taps have (last - past) > minTimeGapNs. For a triple-tap at timestamps [100, 200, 400] with minTimeGapNs=150: the gap from 100 to 400 is 300 (>150, counted), the gap from 200 to 400 is 200 (>150, counted), so tapCount=2, returning 3 (correct). But for [100, 200, 300] with minTimeGapNs=150: gap from 100 to 300 is 200 (>150, counted), gap from 200 to 300 is 100 (<150, NOT counted), tapCount=1, returning 2 (WRONG -- user did 3 taps but only 2 are recognized). The algorithm should check consecutive gaps, not distance-from-last. Furthermore, the early-clear condition `tapCount >= 3 || timeNow - first > mMaxTimeGapTripleNs` will clear the buffer and return early when only 2 taps are present if the timeout fires, returning a stale tapCount=1 which gets mapped to result=2, triggering a false double-tap.
- **影响**: Triple taps are frequently misdetected as double taps. Users who tap quickly with intervals near the minTimeGapNs threshold will never get triple-tap recognition. Conversely, the timeout path can trigger false double-taps from a single old tap.
- **建议修复**: Count taps by iterating consecutive pairs and checking gap between each consecutive pair. Add a minimum total tap count check before returning 2 or 3.

### 3. HeuristicTapTapTapRT.checkDoubleTapTiming: same broken tap-counting logic as TapTapTapRT

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/tap/HeuristicTapTapTapRT.kt`
- **行号**: 28-43
- **描述**: Identical copy-paste of the flawed tapCount logic from TapTapTapRT. Counts distance-from-last-tap instead of consecutive-pair gaps. Same misclassification of fast triple-taps as double-taps.
- **影响**: Same as TapTapTapRT bug: triple-tap misclassification in heuristic power-saving mode.
- **建议修复**: Same fix as TapTapTapRT -- use consecutive-pair gap checking.

## 🟠 High

### 1. recognizeTapML: resampleT calculated with integer division of potentially non-integer ratio, causing misalignment of gyro/acc feature windows

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/tap/TapRT.kt`
- **行号**: 173-174
- **描述**: val resampleT = ((_resampleAcc.results.t - _resampleGyro.results.t) / resampleInterval).toInt() -- resampleInterval is a Long (nanoseconds). If the acc and gyro resampled timestamps differ by less than one interval, the integer division truncates to 0. If they differ by a negative amount (gyro ahead of acc), the sign is preserved but truncation still occurs. The resulting adjustedT = adjustedMajorPeakId - resampleT can become unexpectedly large or negative, causing the feature window bounds check to fail (silently skipping a tap) or to pass with wrong alignment (feeding misaligned gyro data into the classifier).
- **影响**: ML classifier receives misaligned accelerometer and gyroscope feature windows, reducing classification accuracy and potentially causing false positives/negatives in tap detection.
- **建议修复**: Use floating-point division and proper rounding instead of integer truncation. Consider using (value / resampleInterval.toFloat()).roundToInt() for correct nearest-integer alignment.

### 2. recognizeTapML: only acc deque size is checked but gyro deque size may be smaller, risking NoSuchElementException from ArrayDeque

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/tap/TapRT.kt`
- **行号**: 183-190
- **描述**: The bounds check on line 183 verifies adjustedMajorPeakId + _sizeFeatureWindow < _zsAcc.size and _sizeFeatureWindow + adjustedT < _zsAcc.size. However, the gyro deques (_xsGyro, _ysGyro, _zsGyro) are only populated when gyro sensor events arrive and are trimmed to a window based on _resampleGyro.getInterval(). If acc and gyro resampling rates differ, or if gyro data arrived later (due to sensor initialization order), the gyro deques may be shorter than the acc deques. When addToFeatureVector(_xsGyro, adjustedT, ...) is called with adjustedT as the skip count, the iterator may run out of elements before copying _sizeFeatureWindow values, leaving stale zeros from initialization in the feature vector.
- **影响**: Feature vector may contain stale zero-padded data from initialization instead of actual gyro signal, degrading classifier accuracy. In the worst case, if gyro deque is empty, addToFeatureVector silently returns and the feature vector retains garbage from previous calls.
- **建议修复**: Add explicit bounds checks for gyro deque sizes: _sizeFeatureWindow + adjustedT < _xsGyro.size (and ys/zs). If insufficient, skip this classification cycle entirely.

### 3. processKeySignalHeuristic calls _slopeAcc.update redundantly after it was already called in processAccAndKeySignal, corrupting the slope state

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/tap/TapRT.kt`
- **行号**: 117-137 vs 139-161
- **描述**: In ML mode, processAccAndKeySignal() calls _slopeAcc.update(resamplePoint, resampleInterval) to compute slope. Then processKeySignalHeursitic() also calls _slopeAcc.update(resamplePoint, scaledInterval) with the SAME resamplePoint but a DIFFERENT d parameter (scaledInterval vs resampleInterval). Since _slopeAcc maintains internal state (xRawLast), the second call computes slope of the already-scaled value, producing a doubly-transformed result. This corrupts all downstream key signal processing (lowpassKey, highpassKey, peak detection). Note: although these two methods are on different code paths (ML vs heuristic), if the power profile switches between ML and heuristic mode without resetting _slopeAcc, the corrupted state persists.
- **影响**: After a power profile switch from ML to heuristic mode, the slope filter state is corrupted, causing incorrect peak detection and degraded tap recognition until a full reset occurs.
- **建议修复**: Either use separate Slope1C instances for the key signal path, or ensure _slopeAcc is re-initialized during power profile transitions.

### 4. EventIMURT.processGyro: _resampleGyro.getInterval() can return 0 before initialization causing division by zero

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/tap/EventIMURT.kt`
- **行号**: 30-36
- **描述**: processGyro() calls _resampleGyro.getInterval().toFloat() and _sizeWindowNs / _resampleGyro.getInterval(). If _resampleGyro has not been initialized (getInterval() returns 0), line 30 produces 2500000.0f / 0.0f = Float.POSITIVE_INFINITY, and line 36 produces division by zero (Long / 0L) which throws ArithmeticException. In the normal ML flow, _resampleGyro is initialized before processGyro is called (guarded by _gotGyro flag). However, if the power profile switches to heuristic and back, the reset may clear _gotGyro but leave _resampleGyro with stale interval=0.
- **影响**: ArithmeticException crash in the sensor processing pipeline, causing the entire tap detection to reset via the catch block in onSensorChanged.
- **建议修复**: Guard processGyro with a check that _resampleGyro.getInterval() > 0 before proceeding. Or initialize _resampleGyro with a default non-zero interval.

### 5. TapTfClassifier: FileChannel and MappedByteBuffer leak -- FileInputStream is never closed

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/tap/TapTfClassifier.kt`
- **行号**: 42-58
- **描述**: The interpreter is created from a memory-mapped file via FileInputStream(it.fileDescriptor).channel.map(...). The FileInputStream itself is never closed -- it's used inline in a let/lambda and the reference is discarded. While the MappedByteBuffer keeps the file mapping alive, the FileInputStream's FileDescriptor is leaked. On Android this means the file descriptor count grows each time a TapTfClassifier is created (which happens on every power profile switch). Over time this can exhaust the per-process FD limit (typically 1024), causing 'too many open files' errors.
- **影响**: File descriptor leak on every power profile switch. After ~500 switches, the process exhausts FDs and subsequent file operations (including model loading) fail silently or crash.
- **建议修复**: Store the FileInputStream in a field and close it in the close() method, or use a try-with-resources pattern: assetManager.openFd(modelPath).use { fd -> FileInputStream(fd.fileDescriptor).use { fis -> ... } }

## 🟡 Medium

### 1. TapDetector.stop: tap reference set to null after closeClassifier, creating race window for onSensorChanged

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/tap/TapDetector.kt`
- **行号**: 137-153
- **描述**: In stop(), isRunning is set to false, then unregisterListener is called, then closeClassifier runs, then tap is set to null. If onSensorChanged was already dispatched to the sensor handler before unregisterListener took effect, it could execute during closeClassifier. The method captures currentTap = tap early (line 165), so it has a reference to the TapRT object, but updateData would be called on an object whose classifier is being closed concurrently. The same race exists in switchPowerProfile where closeClassifier and tap recreation happen on the handler thread while sensor events may still be queued.
- **影响**: Possible use-after-close of the TFLite interpreter, leading to native crashes or corrupted predictions during power profile transitions.
- **建议修复**: Set tap = null BEFORE calling closeClassifier in both stop() and switchPowerProfile. Then onSensorChanged's early null-check (val currentTap = tap ?: return) will skip the event safely.

### 2. TapRT.updateML: gyro resampler never initialized when acc sensor event arrives first

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/tap/TapRT.kt`
- **行号**: 236-288
- **描述**: When type==1 (acc) arrives first: _gotAcc is set to true, _resampleAcc.init is called. Then the check `if (!_gotGyro) return` exits early. On the NEXT type==1 event, _gotAcc is already true, _resampleAcc.init is NOT called again, _gotGyro is still false, so it returns again. This continues until a type==4 (gyro) event arrives, which calls _resampleGyro.init. Only then does _syncTime get set and all filters initialized. However, during the interim, _syncTime remains 0, so no processing occurs. This is mostly safe but means the first few acc events are silently discarded. More importantly, if gyro events are delayed (e.g., sensor batching), a burst of acc data is lost, potentially missing the first tap.
- **影响**: First tap may be missed if it occurs before the gyroscope sensor starts delivering events, particularly after a cold start or sensor re-registration.
- **建议修复**: Initialize _resampleGyro with a default interval when the first acc event arrives, or buffer acc events until gyro is ready.

### 3. PeakDetector: _idMajorPeak initialized to -1, decremented every update, can underflow for long idle periods

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/tap/PeakDetector.kt`
- **行号**: 6, 20-21
- **描述**: _idMajorPeak starts at -1 and is decremented by 1 on every update() call. After N calls without a peak, _idMajorPeak = -1 - N. This value is returned by getIdMajorPeak() and used in recognizeTapML() where adjustedMajorPeakId = majorPeakId - 6. For large negative values, adjustedMajorPeakId will be very negative, and the bounds check adjustedMajorPeakId >= 0 will fail, so no tap is detected. This is functionally safe but wastes processing. More importantly, in recognizeTapHeuristic(), positiveIdMajorPeak is checked for == 4. After a peak at window position 4, if 5 more updates occur, positiveIdMajorPeak = 4-5 = -1, which correctly prevents re-detection. But if _windowSize is set to 0 (the default), _idMajorPeak is set to -1 on peak detection, meaning peaks are immediately forgotten.
- **影响**: If setWindowSize is never called (or called with 0), peaks are immediately forgotten and tap detection never triggers. The default _windowSize=0 means PeakDetector is non-functional until explicitly configured.
- **建议修复**: Initialize _idMajorPeak to a sentinel value like Int.MIN_VALUE instead of -1, and skip the decrement when it's already at the sentinel. Or add a guard: require _windowSize > 0 in update().

### 4. Util.getMaxId: returns 0 on empty input, causing ArrayIndexOutOfBoundsException in caller

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/tap/Util.kt`
- **行号**: 4-13
- **描述**: getMaxId initializes id=0 and iterates the input. If input is empty, the loop never executes and id=0 is returned. The caller (_tflite.predict(featureVector, 7).first()) passes the result to getMaxId. If the model fails to load (interpreter is null), predict returns ArrayList() (empty), and .first() would throw NoSuchElementException. Even if .first() returns, getMaxId would return 0, which is used as a TapClass ordinal. While TapClass ordinal 0 is 'Front', the classifier returning an empty result indicates failure, not a 'Front' tap classification.
- **影响**: If the TFLite model fails to load (missing asset, wrong path), every sensor event triggers an exception, which is caught by the onSensorChanged catch block, resetting state on every event -- a tight error loop that wastes CPU.
- **建议修复**: Add a check: if predict returns an empty result, return TapClass.Others.ordinal instead of calling getMaxId. Also add a model-load-failure flag that disables classification entirely.

### 5. TapTfClassifier.predict uses hardcoded input shape [1, input.size, 1, 1] that may mismatch the actual TFLite model

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/tap/TapTfClassifier.kt`
- **行号**: 60-63 / TfClassifier.kt line 28-47
- **描述**: predict() always calls predict12() which shapes the input as [1, input.size, 1, 1] (4D). If the TFLite model expects a different input shape (e.g., [1, 300] flat, or [1, 50, 6] for windowed channels), the interpreter will throw an IllegalArgumentException. There is no validation of model input/output tensor shapes against the expected dimensions.
- **影响**: If the model file is updated with a different architecture, or if a wrong model file is shipped, the app crashes on every tap detection attempt with an unhelpful error.
- **建议修复**: After creating the interpreter, validate inputTensor.shape and outputTensor.shape match expectations. Log a clear error if they don't match.

### 6. TapDetector.switchPowerProfile: closeClassifier called on the old tap while sensor events may still reference it via captured local variable

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/tap/TapDetector.kt`
- **行号**: 226-256
- **描述**: switchPowerProfile posts work to sensorHandler. Inside the posted Runnable, it calls tap?.closeClassifier(), then creates a new tap. Between closeClassifier and the new tap assignment, if a sensor event was already dispatched and captured currentTap = tap (the old one), it will call updateData on the old TapRT whose TFLite interpreter has been closed. The updateData -> recognizeTapML -> _tflite.predict path would call interpreter.run on a closed interpreter, causing IllegalStateException.
- **影响**: Native crash or IllegalStateException during power profile transitions, causing tap detection to reset and potentially lose a tap event.
- **建议修复**: Set tap = null before closeClassifier. Use a lock or atomic reference to ensure the old tap is fully detached before cleanup.

## 🟢 Low

### 1. TapTapTapRT/HeuristicTapTapTapRT: checkDoubleTapTiming returns 1 for all non-empty non-qualifying states, conflicting with TapRT semantics

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/tap/TapTapTapRT.kt`
- **行号**: 39-59
- **描述**: When tapCount < 3 and the timeout hasn't fired, the function returns 1 (single-tap pending). But this return value means 'one tap detected, waiting' in the base TapRT contract. For triple-tap mode, the semantics should distinguish between 'one tap pending' and 'two taps pending (waiting for third)'. Currently both states return 1, so the caller cannot display appropriate UI feedback (e.g., 'tap once more' vs 'tap twice more').
- **影响**: No functional bug, but the caller cannot provide differentiated UI feedback for pending double-tap vs pending triple-tap states.
- **建议修复**: Return tapCount + 1 (i.e., 1 for single pending, 2 for double pending) instead of always returning 1 when the gesture is still in progress.

### 2. TapDetector: Prefs.isTapTripleEnabled called on every sensor event (up to 400Hz) causing SharedPreferences I/O overhead

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/tap/TapDetector.kt`
- **行号**: 170
- **描述**: val isTripleEnabled = Prefs.isTapTripleEnabled(context) is called inside onSensorChanged, which fires at the sensor sampling rate (up to 400Hz). SharedPreferences reads involve disk I/O and synchronization. While Android caches SP in memory after first load, the synchronized access and potential GC pressure from repeated lookups adds unnecessary overhead in the hot path.
- **影响**: Unnecessary CPU and GC overhead in the real-time sensor processing pipeline. At 400Hz this means ~400 synchronized map lookups per second.
- **建议修复**: Cache isTripleEnabled as a field in TapDetector, updated only when preferences change (e.g., via a preference change listener or at start/restart time).

### 3. TapRT.init sets _lowpassAcc and _lowpassGyro para to 1.0 (no filtering), then configureCommonFilters overrides key filters but not acc/gyro lowpass

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/tap/TapRT.kt`
- **行号**: 43-48
- **描述**: In init{}, _lowpassAcc.setPara(1f) and _lowpassGyro.setPara(1f) set the lowpass filter alpha to 1.0, which means no filtering (output = input). The highpass filters for acc/gyro are set to 0.05. Later, configureCommonFilters sets _lowpassKey and _highpassKey to 0.2. The acc/gyro lowpass at 1.0 is likely intentional (pass-through) but is never documented or configurable. If someone later changes the init para values without understanding this, the entire signal chain breaks.
- **影响**: No immediate bug, but the signal processing chain has undocumented coupling between init parameters and runtime behavior.
- **建议修复**: Document the filter parameter choices with comments explaining why 1.0 is used for acc/gyro lowpass (i.e., intentional pass-through).

