package tao.test.flipaccounting

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.MigrationManager
import tao.test.flipaccounting.data.repository.AssetRepository
import tao.test.flipaccounting.data.repository.BillRepository
import tao.test.flipaccounting.data.repository.CategoryRepository
import tao.test.flipaccounting.logic.CurrencyManager
import tao.test.flipaccounting.logic.InvestmentInterestService
import tao.test.flipaccounting.ui.main.SharedYearMonthSession

class FlipApplication : Application() {

    companion object {
        @Volatile
        private var instance: FlipApplication? = null

        fun app(): FlipApplication =
            instance ?: error("FlipApplication has not been initialized yet")
    }

    // 懒加载数据库和 Repository
    val database by lazy { AppDatabase.getDatabase(this) }
    val billRepository by lazy { BillRepository(database.billDao()) }
    val assetRepository by lazy { AssetRepository(database.assetDao()) }
    val categoryRepository by lazy { CategoryRepository(database.categoryDao()) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        SharedYearMonthSession.resetToCurrentMonth()
        installCrashHandler()
        CurrencyManager.init(this)

        // 启动时在后台协程检查并执行数据迁移
        CoroutineScope(Dispatchers.IO).launch {
            MigrationManager.migrateIfNecessary(this@FlipApplication, database)
            InvestmentInterestService.settleDueInterest(database)
        }

        // 后台预热分类图标缓存，确保断网时也能显示
        CoroutineScope(Dispatchers.IO).launch {
            CategoryIconPreloader.preloadAll(this@FlipApplication)
        }
    }

    /**
     * 注册全局未捕获异常处理器。
     * 崩溃堆栈会写入 crash_logs.txt（与 app_logs.txt 同目录），
     * 可在 App 内"日志查看器"页面查看和分享。
     * 写完后仍调用系统默认处理器，保证系统崩溃对话框正常弹出。
     */
    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Logger.crash(applicationContext, thread, throwable)
            } catch (_: Exception) {
                // 崩溃处理本身不能再抛异常
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
