package com.taostudio.tapaccounting.data.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 所有共享账本共用同一扇门，避免后台任务、页面刷新和手动同步互相重叠。
 * 账本数量很少，串行执行比并发打满 WebDAV 更合适。
 */
class SharedSyncGate {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }

    companion object {
        val global = SharedSyncGate()
    }
}

object SharedSyncPolicy {
    /** 后台完整远端轮询间隔；本地待上传内容不受此间隔影响。 */
    const val BACKGROUND_QUIET_PERIOD_MS = 5 * 60_000L
    const val SERVER_BUSY_COOLDOWN_MS = 30_000L
    const val RATE_LIMIT_COOLDOWN_MS = 60_000L
    private const val MIN_COOLDOWN_MS = 5_000L
    private const val MAX_COOLDOWN_MS = 5 * 60_000L

    enum class BackgroundMode { SKIP, UPLOAD_ONLY, FULL }

    fun backgroundMode(
        pendingUploadCount: Int,
        lastSyncTime: Long,
        now: Long,
        forceFull: Boolean = false
    ): BackgroundMode = when {
        forceFull -> BackgroundMode.FULL
        lastSyncTime <= 0L || now - lastSyncTime >= BACKGROUND_QUIET_PERIOD_MS -> BackgroundMode.FULL
        pendingUploadCount > 0 -> BackgroundMode.UPLOAD_ONLY
        else -> BackgroundMode.SKIP
    }

    fun shouldRunBackgroundSync(pendingUploadCount: Int, lastSyncTime: Long, now: Long): Boolean =
        backgroundMode(pendingUploadCount, lastSyncTime, now) != BackgroundMode.SKIP

    fun cooldownMillis(statusCode: Int, retryAfterMillis: Long?): Long? {
        val fallback = when (statusCode) {
            429 -> RATE_LIMIT_COOLDOWN_MS
            503 -> SERVER_BUSY_COOLDOWN_MS
            else -> return null
        }
        return (retryAfterMillis ?: fallback).coerceIn(MIN_COOLDOWN_MS, MAX_COOLDOWN_MS)
    }
}

object RemoteOperationPlanner {
    fun pendingFiles(
        remoteFiles: List<String>,
        knownOperationIds: Set<String>,
        processedBundles: Set<String>
    ): List<String> = remoteFiles.distinct().filter { relative ->
        if (relative.endsWith(".json.gz", ignoreCase = true)) {
            relative !in processedBundles
        } else {
            relative.substringAfterLast('/').removeSuffix(".json") !in knownOperationIds
        }
    }
}
