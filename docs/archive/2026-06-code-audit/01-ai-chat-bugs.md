# AI 服务 & 聊天模块 Bug 审计

**共 10 个发现**: 🔴 0 Critical | 🟠 3 High | 🟡 6 Medium | 🟢 1 Low

## 🟠 High

### 1. Memory/coroutine leak: AiAssistant.scope never cancelled

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/AiAssistant.kt`
- **行号**: 42
- **描述**: AiAssistant creates a CoroutineScope with SupervisorJob() + Dispatchers.IO that is never cancelled. The class holds a Context reference and launches network coroutines on this scope. dismiss() only cancels analyzeJob, not the scope itself, so any in-flight coroutines will leak the Context and continue running after the AiAssistant is no longer needed.
- **影响**: Context leak (Activity or Service), wasted network resources, potential crashes from callbacks on a dead context.
- **建议修复**: Add a destroy/cleanup method that calls scope.cancel(), or tie the scope to a lifecycle.

### 2. Race condition: AudioRecord null between check and read in recording thread

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ChatAudioRecordController.kt`
- **行号**: 114-133
- **描述**: writeAudioDataToFile() runs on a background thread and calls getAudioRecord()?.read() in a loop. Meanwhile, stopVoiceRecording() on the main thread calls getAudioRecord()?.stop() then setAudioRecord(null). The recording thread can see a non-null AudioRecord, then the main thread calls stop() and nulls it. Between stop() and the null assignment, the read() call may throw IllegalStateException.
- **影响**: Potential crash (IllegalStateException) during voice recording stop, or corrupted WAV file if the race causes partial writes.
- **建议修复**: Use a synchronized block around the AudioRecord lifecycle (start/stop/read), or capture a local reference to the AudioRecord before the loop.

### 3. Race condition: displayMessages list accessed from multiple threads

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ChatActivity.kt`
- **行号**: 496
- **描述**: displayMessages is a plain mutableListOf shared between the main thread (UI callbacks, adapter binds) and background coroutines (ChatMessagePersistenceController, ChatBillCorrectionService). While most access is dispatched to Main, several places mutate the list from within withContext(Dispatchers.Main) blocks launched from IO. If an IO coroutine dispatches to Main while the RecyclerView is mid-layout, IndexOutOfBoundsException can occur.
- **影响**: IndexOutOfBoundsException crashes in RecyclerView, or inconsistent UI state.
- **建议修复**: Use DiffUtil for adapter updates instead of direct list mutation, or use CopyOnWriteArrayList for the shared list.

## 🟡 Medium

### 1. Race condition: AIService cachedApi/cachedSpeechApi not synchronized

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/AIService.kt`
- **行号**: 92-119
- **描述**: cachedApi and cachedSpeechApi are @Volatile Pair<String, SiliconFlowApi>? fields, but the read-then-write pattern in getApi() and getSpeechApi() is not atomic. Two concurrent coroutines can both see a stale/null cache, create duplicate Retrofit instances, and overwrite each other. The volatile keyword only guarantees visibility of individual field writes, not atomicity of the check-then-act sequence.
- **影响**: Duplicate API client instances created under concurrent requests; wasted memory and inconsistent state if baseUrl changes mid-flight.
- **建议修复**: Replace the volatile Pair fields with a synchronized lazy-init pattern, e.g. use a lock or @Synchronized on getApi/getSpeechApi.

### 2. Race condition: ChatVoiceController.mediaPlayer accessed from multiple threads

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ChatVoiceController.kt`
- **行号**: 111-189
- **描述**: playVoiceMessage() reads and writes mediaPlayer on the calling thread, while the MediaPlayer's onCompletionListener callback (which calls stopVoicePlayback()) runs on the main thread. If the completion callback fires between the isPlaying check and the pause/start call, a null pointer or IllegalStateException can occur.
- **影响**: Crash when toggling voice playback rapidly, or when a voice message finishes playing while the user taps play/pause.
- **建议修复**: Ensure all mediaPlayer access runs on the main thread (use Handler or Dispatchers.Main), or synchronize access with a lock.

### 3. Thread-safety: imageThumbSizeCache accessed from multiple coroutines without synchronization

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ChatAdapters.kt`
- **行号**: 80-81, 248-260
- **描述**: imageThumbSizeCache (mutableMapOf) and imageThumbSizeLoading (mutableSetOf) are accessed from the main thread in bind() and from Dispatchers.IO in loadImageThumbSize(). The IO coroutine writes to the cache and removes from loading set, while the main thread reads from both. HashMap/HashSet are not thread-safe; concurrent read+write can cause ConcurrentModificationException or infinite loops in HashMap.
- **影响**: Potential crash (ConcurrentModificationException) or hang (infinite loop in HashMap) when loading image thumbnails.
- **建议修复**: Use ConcurrentHashMap.newKeySet() for the loading set and ConcurrentHashMap for the size cache, or ensure all access is on the main thread.

### 4. Bitmap not recycled on compression failure path

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ChatMediaController.kt`
- **行号**: 455-469
- **描述**: In compressImageInPlace(), the bitmap is decoded and then compressed. If compress() throws, the bitmap.recycle() call is skipped because it is not in a try-finally block. The caller uses runCatching which swallows the exception, but the bitmap leak occurs within this function.
- **影响**: Native memory leak for large bitmaps (4MB+ images), leading to OutOfMemoryError on repeated image picks.
- **建议修复**: Wrap the compress call in try-finally to ensure bitmap.recycle() is always called.

### 5. WAV header update race with file read in stopVoiceRecording

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ChatAudioRecordController.kt`
- **行号**: 75-103
- **描述**: stopVoiceRecording() sets isRecording to false, stops and releases AudioRecord, then joins the recording thread with a 1200ms timeout. If the join times out (thread still writing), the method proceeds to check the file. The recording thread's writeAudioDataToFile() updates the WAV header after the main write loop. If join times out, the WAV header may not be updated, resulting in a corrupted WAV file with incorrect length fields.
- **影响**: Corrupted voice recordings that cannot be played back or transcribed, especially on slow devices.
- **建议修复**: Increase the join timeout, or use a CountDownLatch/CompletableDeferred to wait for the WAV header update to complete before returning the file.

### 6. Streaming parse error discards valid partial content in image accounting

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/AIService.kt`
- **行号**: 1326-1355
- **描述**: In requestChatContentStreamedWithReasoning(), if a JSON parse error occurs on one SSE chunk, the loop breaks with parseError set. In requestAccountingContentStreamed() this is handled by using partial content, but in analyzeReceiptByImage() and analyzeScreenAccountingByImage(), the incomplete stream throws an exception, discarding valid partial content that was already received.
- **影响**: Valid partial AI responses are discarded when a single malformed SSE chunk arrives, causing unnecessary retries or user-visible failures.
- **建议修复**: In callers that check streamed.completed, fall back to using streamed.content when it is not blank, similar to how requestAccountingContentStreamed already handles this.

## 🟢 Low

### 1. QueryPlanner normalizeToken strips '卡' from all tokens, weakening matching

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/chat/query/QueryPlanner.kt`
- **行号**: 326-329
- **描述**: normalizeToken() applies .replace('卡', '') to all tokens. This means asset names like '公交卡' and '饭卡' are normalized to '公交' and '饭' respectively. While this helps match '信用卡' as a search term, it weakens the specificity of asset names: a search for '卡' would normalize to empty string and fail to match anything. More problematically, two distinct assets '公交卡' and '公交余额' would normalize to the same token '公交', causing ambiguous matching.
- **影响**: Incorrect or ambiguous asset matching in query planner when asset names contain the character '卡'.
- **建议修复**: Remove the blanket '卡' stripping, or only apply it as a suffix-stripping rule (e.g., strip '卡' only when preceded by known prefixes like '信用', '储蓄').

