package tao.test.flipaccounting.chat.ai

import tao.test.flipaccounting.chat.time.ChatTimeFormatter
import tao.test.flipaccounting.data.local.entity.Bill
import java.util.Locale

object ChatCorrectionPromptBuilder {

    fun build(normalizedUserText: String, lastBill: Bill): String {
        return buildString {
            appendLine("这是一次修改上一笔账单的请求。请不要新增无关账单，只返回修改后的完整账单 JSON。")
            appendLine("本次允许修改的字段包括：分类(category_name)、资产/账户(asset_name)、转入账户(to_asset_name)、备注(remarks)、金额(amount)、时间(time)、类型(type)。")
            appendLine("请忽略系统提示词里的示例日期、示例金额、示例商家名，它们只是格式演示，绝不能被当成当前用户账单内容。")
            appendLine("如果用户只说“资产改为/账户改为/备注改为/分类改为”，就只修改对应字段。")
            appendLine("如果用户没有明确指定资产，就允许 asset_name 为空，不要为了凑字段强行猜一个资产。")
            appendLine("上一笔账单如下：")
            appendLine("金额=${String.format(Locale.getDefault(), "%.2f", lastBill.amount)}")
            appendLine("类型=${lastBill.type}")
            appendLine("分类=${lastBill.categoryName}")
            appendLine("账户=${lastBill.accountName}")
            appendLine("转入账户=${lastBill.toAccountName}")
            appendLine("备注=${lastBill.remark}")
            appendLine("时间=${ChatTimeFormatter.formatTime(lastBill.time)}")
            appendLine("用户这次的话：$normalizedUserText")
            append("如果用户只修改了其中一项，其余字段必须沿用上一笔账单，尤其不要丢失原有金额、账户、备注。")
        }
    }
}
