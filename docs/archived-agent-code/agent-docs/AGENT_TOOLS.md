# Agent 工具清单

> 最后更新：2026-06-11

## 工具总览

- **原有工具：** 23 个
- **第一批新增：** 41 个
- **第二批新增：** 10 个
- **当前总数：** 74 个（含 1 个向后兼容的 `nav.open_stats`）

## 生产链路状态

| 能力 | 状态 | 说明 |
|------|------|------|
| Room 20→21 迁移 | ✅ 已实现 | Asset 新增 billBalanceFromTime/showBillBalanceAfter，Bill 新增 accountBalanceAfter/toAccountBalanceAfter |
| Agent 开关路由 | ✅ 已实现 | 按 conversationId 前缀判断模式，agent_ 走 Agent，conv_ 走原记账 |
| 确认流程闭环 | ✅ 已实现 | PendingAgentAction 保存→确认执行→取消清除→过期自动失效 |
| validate() 接入 | ✅ 已实现 | orchestrator 在 execute 前统一调用 validate |
| Skill 动态注入 | ✅ 已实现 | AgentSkillRouter 选择 Skill → AgentSkillRegistry 获取工具 → 动态生成 prompt |
| 对话历史传递 | ✅ 已实现 | 从 DB 读取最近 ChatTurn 作为结构化消息传给 LLM |
| 多步执行 | ✅ 已实现 | 最多 5 步，每步校验+确认，遇危险步骤暂停 |
| 事实防篡改 | ✅ 已实现 | 回复后校验关键数字，失败回退 userMessage |

---

## 按 Skill 分类

### general（通用）

| tool_id | 风险 | 说明 | 执行方式 |
|---------|------|------|----------|
| `chat.reply` | READ | 纯闲聊/解释功能 | 直接返回 LLM 回复 |
| `agent.clarify` | READ | 向用户追问 | 直接返回问题 |
| `agent.list_capabilities` | READ | 列出能做什么 | 直接返回 |
| `agent.cancel` | READ | 取消当前待确认操作 | 清除 pending action |
| `agent.unsupported` | READ | 请求的软件能力不可用时明确告知尚未实现 | 直接返回 |
| `nav.open_stats` | NAV | 打开统计页面 | 打开页面 |
| `nav.open_page` | NAV | 打开指定页面（白名单） | 打开页面 |

### bill（账单）

| tool_id | 风险 | 说明 | 用户示例 |
|---------|------|------|----------|
| `bill.list_recent` | READ | 最近 N 笔账单 | "最近三笔账单" |
| `bill.list_by_date` | READ | 按日期列账单 | "昨天买了什么" |
| `bill.search` | READ | 关键词搜索账单 | "有没有买过咖啡" |
| `bill.get_detail` | READ | 查单笔详情 | "那笔账单详情" |
| `bill.create_from_text` | WRITE | 文本记账 | "午饭花了35" |
| `bill.create_manual` | NAV | 打开手动记账页 | "手动记一笔" |
| `bill.create_transfer` | WRITE | 创建转账 | "从微信转100到支付宝" |
| `bill.modify_by_instruction` | WRITE | 自然语言改账 | "刚才那笔改成40" |
| `bill.edit` | NAV | 打开账单编辑页 | "编辑那笔账单" |
| `bill.delete` | DESTRUCTIVE | 删除单笔账单 | "删除上一笔" |
| `bill.delete_batch` | DESTRUCTIVE | 批量删除账单 | "删除最近5笔" |
| `bill.move_to_book` | WRITE | 移动账单到其他账本 | "把那笔转到旅行账本" |
| `bill.toggle_exclude_stats` | WRITE | 切换是否计入统计 | "那笔不计入统计" |
| `bill.restore_from_bin` | WRITE | 从回收站恢复 | "恢复刚才删的账单" |
| `bill.refund` | WRITE | 对支出进行退款 | "退款那笔咖啡" |
| `bill.permanent_delete` | DESTRUCTIVE | 从回收站永久删除 | "永久删除那笔账单" |

### stats（统计）

| tool_id | 风险 | 说明 | 用户示例 |
|---------|------|------|----------|
| `stats.query_category` | READ | 查询某分类花销 | "本月餐饮花了多少" |
| `stats.query_spending` | READ | 通用花销查询 | "这个月总花销" |
| `stats.query_month_summary` | READ | 本月收支总览 | "本月收支情况" |
| `stats.query_year_summary` | READ | 本年收支总览 | "本年收支总览" |
| `stats.query_existence` | READ | 有没有买过 X | "法国账本有没有买过红酒" |
| `stats.query_latest_bill` | READ | 最近一笔账单 | "最近一笔是什么" |
| `stats.query_compare_period` | READ | 两个时间段比较 | "和上个月比呢" |
| `stats.query_top_categories` | READ | 支出分类排行 | "支出最多的分类" |
| `stats.open_page` | NAV | 打开统计页（可筛选） | "打开统计页看餐饮" |
| `stats.open_asset_page` | NAV | 打开资产统计页 | "打开资产统计" |
| `calendar.query_day` | READ | 查询某日账单 | "昨天买了什么" |
| `calendar.open` | NAV | 打开日历视图 | "打开日历" |
| `book.query_overview` | READ | 各账本收支概览 | "各账本收支情况" |
| `bill.search` | READ | 搜索账单（跨 Skill） | — |
| `bill.list_by_date` | READ | 按日期列账单（跨 Skill） | — |

### asset_book（资产账本）

| tool_id | 风险 | 说明 | 用户示例 |
|---------|------|------|----------|
| `asset.list` | READ | 列出所有资产 | "我有哪些账户" |
| `asset.get_balance` | READ | 某资产余额 | "微信还有多少钱" |
| `asset.count` | READ | 资产数量 | "有几个账户" |
| `asset.get_net_worth` | READ | 净资产 | "净资产多少" |
| `asset.create` | NAV | 打开新建资产页 | "新建一个资产" |
| `asset.archive` | WRITE | 收纳资产 | "收纳那个不用的账户" |
| `asset.unarchive` | WRITE | 取消收纳 | "恢复那个账户" |
| `asset.adjust_balance` | NAV | 打开平账页 | "微信平一下账" |
| `asset.open_detail` | NAV | 打开资产详情 | "看看微信详情" |
| `asset.delete` | DESTRUCTIVE | 删除资产 | "删除那个不用的账户" |
| `book.get_current` | READ | 当前账本 | "当前是什么账本" |
| `book.list` | READ | 列出所有账本 | "有哪些账本" |
| `book.switch` | WRITE | 切换账本 | "切换到旅行账本" |
| `book.create` | WRITE | 创建新账本 | "创建一个新账本" |
| `book.rename` | WRITE | 重命名账本 | "把日常账本改成生活账本" |
| `book.set_default` | WRITE | 设默认账本 | "把旅行账本设为默认" |
| `book.delete` | NAV | 打开账本管理页删除账本 | "删除旅行账本" |

### category（分类）

| tool_id | 风险 | 说明 | 用户示例 |
|---------|------|------|----------|
| `category.list` | READ | 列出支出/收入分类 | "有哪些支出分类" |
| `category.open_manage` | NAV | 打开分类管理页 | "打开分类管理" |
| `category.rename` | WRITE | 重命名分类 | "把餐饮改成美食" |
| `category.delete` | DESTRUCTIVE | 删除分类 | "删除娱乐分类" |

### settings（设置）

| tool_id | 风险 | 说明 | 用户示例 |
|---------|------|------|----------|
| `pref.get` | READ | 读取设置项 | "AI模型是什么" |
| `pref.set` | WRITE | 修改设置项（白名单） | "关闭震动" |

### backup（备份）

| tool_id | 风险 | 说明 | 用户示例 |
|---------|------|------|----------|
| `backup.list_modules` | READ | 可备份模块列表 | "备份了哪些模块" |
| `backup.export_full` | NAV | 打开全量备份页 | "备份数据" |
| `backup.export_csv` | NAV | 打开 CSV 导出页 | "导出CSV" |
| `backup.import` | NAV | 打开备份恢复页 | "导入备份" |
| `backup.import_csv` | NAV | 打开 CSV 导入页 | "导入CSV" |
| `cloud.get_config` | READ | 云备份配置状态 | "云备份配置了吗" |
| `cloud.open_settings` | NAV | 打开云备份设置 | "打开云备份设置" |
| `cloud.set_config` | NAV | 打开云备份配置页 | "配置WebDAV" |

### navigation（导航）

| tool_id | 风险 | 说明 | 用户示例 |
|---------|------|------|----------|
| `nav.open_page` | NAV | 打开指定页面 | "打开首页" |
| `nav.open_stats` | NAV | 打开统计页 | "打开统计页" |
| `stats.open_page` | NAV | 打开统计页（带筛选） | — |
| `stats.open_asset_page` | NAV | 打开资产页 | — |
| `calendar.open` | NAV | 打开日历 | "打开日历" |
| `asset.open_detail` | NAV | 打开资产详情 | — |
| `asset.create` | NAV | 打开新建资产页 | — |
| `category.open_manage` | NAV | 打开分类管理 | — |
| `bill.edit` | NAV | 打开账单编辑页 | — |
| `bill.create_manual` | NAV | 打开手动记账页 | — |
| `backup.export_full` | NAV | 打开备份页 | — |
| `backup.export_csv` | NAV | 打开 CSV 导出页 | — |
| `cloud.open_settings` | NAV | 打开云备份设置 | — |
| `cloud.set_config` | NAV | 打开云备份配置 | — |
| `storage.open` | NAV | 打开存储管理 | — |
| `storage.cleanup` | NAV | 打开存储清理 | — |
| `backup.import` | NAV | 打开备份恢复页 | — |
| `backup.import_csv` | NAV | 打开 CSV 导入页 | — |
| `ai.set_api_key` | NAV | 打开 AI 配置页 | — |

### system（系统）

| tool_id | 风险 | 说明 | 用户示例 |
|---------|------|------|----------|
| `perm.get_status` | READ | 权限状态查询 | "权限状态" |
| `gesture.get_status` | READ | 手势功能状态 | "手势功能开启了吗" |
| `gesture.list_actions` | READ | 可用手势动作列表 | "有哪些手势动作" |
| `storage.get_usage` | READ | 存储占用查询 | "存储空间还有多少" |
| `storage.open` | NAV | 打开存储管理页 | "打开存储管理" |
| `storage.cleanup` | NAV | 打开存储清理页 | "清理缓存" |

### AI 设置

| tool_id | 风险 | 说明 | 用户示例 |
|---------|------|------|----------|
| `ai.set_api_key` | NAV | 打开 AI 配置页 | "设置 API Key" |

---

## 风险等级说明

| 等级 | 含义 | 处理方式 |
|------|------|----------|
| READ | 只读查询 | 直接执行 |
| NAV | 打开页面 | 直接执行（白名单） |
| WRITE | 新增/修改 | 确认后执行 |
| DESTRUCTIVE | 删除/恢复覆盖 | 预览影响 + 二次确认 |
| SENSITIVE | API Key 等敏感信息 | 禁止明文回显 |
| SYSTEM | 系统权限 | 确认或跳转系统授权页 |

---

## 已实现（第二批，NAV/强确认方式）

| 能力 | 实现方式 | 说明 |
|------|----------|------|
| `backup.import` | NAV | 打开备份恢复页，用户手动选择文件并确认 |
| `backup.import_csv` | NAV | 打开 CSV 导入页，用户手动选择文件并确认 |
| `bill.permanent_delete` | DESTRUCTIVE | 从回收站永久删除，需二次确认 |
| `storage.cleanup` | NAV | 打开存储清理页，用户手动选择清理项 |
| `asset.delete` | DESTRUCTIVE | 删除资产，需二次确认 |
| `book.delete` | NAV | 打开账本管理页，用户手动选择删除方式并确认 |
| `category.delete` | DESTRUCTIVE | 删除分类及子分类，需二次确认 |
| `category.rename` | WRITE | 重命名分类，同步更新关联账单，需确认 |
| `ai.set_api_key` | NAV | 打开 AI 配置页，用户手动设置 |
| `cloud.set_config` | NAV | 打开云备份配置页，用户手动设置 |

## 仍为 unsupported 的能力

| 能力 | 原因 |
|------|------|
| `gesture.enable_*` / `gesture.set_*` | 手势开关涉及系统服务，需通过 pref.set 或导航到设置页 |
| `overlay.*` | 悬浮窗操作涉及系统服务 |
| `perm.request_*` | 系统权限跳转，需通过导航到系统设置 |
| `ai.*` 其他设置类 | AI 配置复杂，通过 pref.get/set 覆盖 |

---

## Skill 路由规则

用户输入 → 关键词匹配 → 选择 1-3 个 Skill → 注入对应工具

| 关键词示例 | 路由到 |
|-----------|--------|
| 记账、花了、买了、删除 | bill |
| 花了多少、统计、比较、日历 | stats |
| 余额、资产、账本、切换 | asset_book |
| 分类、支出分类 | category |
| 设置、震动、模型 | settings |
| 备份、导出、CSV | backup |
| 打开、跳转、进入 | navigation |
| 权限、手势、存储 | system |
| 你好、能做什么 | general |

---

## 测试覆盖

测试文件：
- `AgentNewToolsTest.kt` — 第一批工具测试
- `AgentBatch2ToolsTest.kt` — 第二批工具测试

覆盖场景：
- 注册表幂等性（同 ID 注册两次）
- 风险等级确认流程（READ/NAV/WRITE/DESTRUCTIVE/SENSITIVE/SYSTEM）
- NAV 工具白名单验证
- Skill 注册完整性（9 个 Skill）
- Skill 工具分配正确性
- Skill 路由关键词匹配
- 工具总数验证
- 新增 DESTRUCTIVE 工具确认流程（asset.delete, category.delete, bill.permanent_delete）
- 新增 WRITE 工具确认流程（category.rename）
- 新增 NAV 工具无需确认（backup.import, backup.import_csv, storage.cleanup, cloud.set_config, ai.set_api_key, book.delete）
- unsupported 工具格式化消息
- unsupported 能力不在任何 Skill 中（perm.request_*, gesture.enable_*, overlay.*）
- Skill 路由新关键词（删除资产、删除账本、重命名分类、删除分类、永久删除、导入备份、导入CSV、清理存储、API Key）
