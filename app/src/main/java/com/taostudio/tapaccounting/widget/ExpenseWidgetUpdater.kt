package com.taostudio.tapaccounting.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 统一的小组件刷新入口。
 *
 * 记账/预算相关的写操作分散在很多入口（记一笔、编辑账单、改预算……），逐一埋点刷新
 * 维护成本很高、也容易漏。这里采用更简单可靠的策略：
 * - [refreshAll] 在“用户离开 App 回到桌面”（[com.taostudio.tapaccounting.MainActivity.onPause]）时调用一次，
 *   覆盖绝大多数“改完数据 -> 回桌面看小组件”的场景；
 * - 小组件自身的 updatePeriodMillis（见 res/xml 下的 provider info）作为兜底轮询，
 *   保证跨天/跨月等没有用户操作也需要刷新的情况最终会更新。
 */
object ExpenseWidgetUpdater {

    private val providerClasses = listOf(
        WidgetSize.COMPACT to CompactExpenseWidgetProvider::class.java,
        WidgetSize.STANDARD to StandardExpenseWidgetProvider::class.java,
        WidgetSize.DETAILED to DetailedExpenseWidgetProvider::class.java
    )

    /** 遍历三种尺寸下已放置在桌面的所有小组件实例，逐一重新渲染。 */
    fun refreshAll(context: Context) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val manager = AppWidgetManager.getInstance(appContext)
            providerClasses.forEach { (size, clazz) ->
                val ids = manager.getAppWidgetIds(ComponentName(appContext, clazz))
                ids.forEach { appWidgetId -> renderOne(appContext, manager, appWidgetId, size) }
            }
        }
    }

    /** 只刷新指定的一个 widgetId（配置页保存后调用），会自动判断它属于哪种尺寸。 */
    fun refreshOne(context: Context, appWidgetId: Int) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val manager = AppWidgetManager.getInstance(appContext)
            val size = sizeOf(appContext, manager, appWidgetId) ?: return@launch
            renderOne(appContext, manager, appWidgetId, size)
        }
    }

    private suspend fun renderOne(context: Context, manager: AppWidgetManager, appWidgetId: Int, size: WidgetSize) {
        val config = WidgetConfigStore.load(context, appWidgetId) ?: WidgetConfig.default(context)
        val snapshot = ExpenseWidgetRenderer.buildSnapshot(context, config)
        ExpenseWidgetRenderer.render(context, manager, appWidgetId, size, snapshot)
    }

    fun sizeOf(context: Context, manager: AppWidgetManager, appWidgetId: Int): WidgetSize? {
        val provider = manager.getAppWidgetInfo(appWidgetId)?.provider?.className ?: return null
        return providerClasses.firstOrNull { (_, clazz) -> clazz.name == provider }?.first
    }

    /** App 内"桌面小组件"设置页需要枚举当前所有已放置的 widgetId。 */
    fun allPlacedWidgetIds(context: Context): List<Pair<Int, WidgetSize>> {
        val manager = AppWidgetManager.getInstance(context.applicationContext)
        return providerClasses.flatMap { (size, clazz) ->
            manager.getAppWidgetIds(ComponentName(context.applicationContext, clazz)).map { it to size }
        }
    }
}
