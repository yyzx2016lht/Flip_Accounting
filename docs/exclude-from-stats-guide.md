# "不计入统计"功能接入指南

## 功能概述

"不计入统计"功能允许用户将特定账单排除在统计计算之外。被标记的账单仍会正常显示在账单列表中，但不会被计入收支统计、分类统计等数据。

---

## 数据库层

### 字段定义

**文件**: `app/src/main/java/tao/test/flipaccounting/data/local/entity/Bill.kt`

```kotlin
data class Bill(
    // ... 其他字段 ...
    
    // 是否不计入统计
    val excludeFromStats: Boolean = false
)
```

### 数据库迁移

**文件**: `app/src/main/java/tao/test/flipaccounting/data/local/AppDatabase.kt`

版本 15 → 16 的迁移已包含此字段：

```kotlin
private val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE bills ADD COLUMN excludeFromStats INTEGER NOT NULL DEFAULT 0")
    }
}
```

---

## DAO 层

**文件**: `app/src/main/java/tao/test/flipaccounting/data/local/dao/BillDao.kt`

### 单条更新

```kotlin
/** 更新单条账单的不计入统计状态 */
@Query("UPDATE bills SET excludeFromStats = :exclude WHERE id = :billId")
suspend fun updateExcludeStats(billId: Long, exclude: Boolean)
```

### 批量更新

```kotlin
/** 批量更新账单的不计入统计状态 */
@Query("UPDATE bills SET excludeFromStats = :exclude WHERE id IN (:billIds)")
suspend fun updateExcludeStatsForBills(billIds: List<Long>, exclude: Boolean)
```

---

## 统计过滤

**文件**: `app/src/main/java/tao/test/flipaccounting/ui/main/stats/StatsViewModel.kt`

在 `processData()` 方法中，遍历账单时需要跳过 `excludeFromStats = true` 的账单：

```kotlin
bills.forEach { bill ->
    // 跳过不计入统计的账单
    if (bill.excludeFromStats) return@forEach
    
    val amount = statsAmountOf(bill, state.selectedCurrency)
    // ... 后续统计逻辑 ...
}
```

---

## UI 入口建议

### 方案 A：账单详情弹窗

在 `layout_bill_detail_bottom_sheet.xml` 的账本行下方添加开关：

```xml
<View
    android:layout_width="match_parent"
    android:layout_height="0.5dp"
    android:background="@color/dialog_divider" />

<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:gravity="center_vertical"
    android:orientation="horizontal"
    android:paddingVertical="10dp">

    <TextView
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:text="不计入统计"
        android:textColor="@color/text_tertiary"
        android:textSize="@dimen/text_size_13" />

    <androidx.appcompat.widget.SwitchCompat
        android:id="@+id/switch_exclude_stats"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:scaleX="0.85"
        android:scaleY="0.85" />
</LinearLayout>
```

在 `BillDetailSheetHelper.kt` 中绑定事件：

```kotlin
val switchExcludeStats = view.findViewById<SwitchCompat>(R.id.switch_exclude_stats)

// 设置初始状态
switchExcludeStats.isChecked = bill.excludeFromStats

// 监听变化
switchExcludeStats.setOnCheckedChangeListener { _, isChecked ->
    lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context)
        db.billDao().updateExcludeStats(bill.id, isChecked)
    }
}
```

需要添加 import：
```kotlin
import androidx.appcompat.widget.SwitchCompat
```

### 方案 B：多选操作栏

在 `fragment_home.xml` 的多选操作栏中添加按钮：

```xml
<TextView
    android:id="@+id/btn_ms_exclude_stats"
    android:layout_width="0dp"
    android:layout_height="48dp"
    android:layout_marginStart="8dp"
    android:layout_weight="1.2"
    android:background="@drawable/shape_button_outline"
    android:gravity="center"
    android:text="不计统计"
    android:textColor="#FF9800"
    android:textSize="@dimen/text_size_16" />
```

在 `HomeMultiSelectController.kt` 中添加参数和事件处理：

```kotlin
internal class HomeMultiSelectController(
    // ... 其他参数 ...
    private val btnMsExcludeStats: View,
    // ...
) {
    fun setupMultiSelectActions() {
        // ... 其他按钮 ...
        
        btnMsExcludeStats.setOnClickListener {
            val billsToExclude = getHomeAdapter().selectedBills.toList()
            if (billsToExclude.isEmpty()) return@setOnClickListener

            val db = AppDatabase.getDatabase(fragment.requireContext())
            val billIds = billsToExclude.map { it.id }
            val allExcluded = billsToExclude.all { it.excludeFromStats }
            val newExcludeState = !allExcluded

            fragment.lifecycleScope.launch(Dispatchers.IO) {
                db.billDao().updateExcludeStatsForBills(billIds, newExcludeState)
                withContext(Dispatchers.Main) {
                    getHomeAdapter().clearSelection()
                    val msg = if (newExcludeState) {
                        "已将 ${billsToExclude.size} 条账单设为不计入统计"
                    } else {
                        "已将 ${billsToExclude.size} 条账单恢复计入统计"
                    }
                    Toast.makeText(fragment.context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
```

### 方案 C：长按菜单

在账单列表项长按时，弹出包含"不计入统计"选项的菜单。

---

## 注意事项

1. **统计一致性**: 确保所有统计相关的代码都检查 `excludeFromStats` 字段
2. **UI 反馈**: 在账单列表中可以考虑用不同样式显示被排除的账单（如灰色文字）
3. **批量操作**: 支持批量设置/取消"不计入统计"，方便用户管理大量账单
4. **数据导出**: 导出功能应考虑是否需要导出被排除的账单

---

## 相关文件清单

| 文件 | 说明 |
|------|------|
| `data/local/entity/Bill.kt` | 账单实体，包含 `excludeFromStats` 字段 |
| `data/local/dao/BillDao.kt` | DAO，包含更新方法 |
| `data/local/AppDatabase.kt` | 数据库，包含迁移 |
| `ui/main/stats/StatsViewModel.kt` | 统计 ViewModel，包含过滤逻辑 |
| `ui/main/home/BillDetailSheetHelper.kt` | 账单详情弹窗 |
| `ui/main/home/HomeMultiSelectController.kt` | 多选操作控制器 |
| `res/layout/layout_bill_detail_bottom_sheet.xml` | 详情弹窗布局 |
| `res/layout/fragment_home.xml` | 首页布局（含多选栏） |
