# 业务逻辑正确性审计报告

**审计时间**: 2026-06-22
**审计范围**: 货币换算、投资利息、账单 CRUD、查询规划、数据迁移

## 统计概览

| 指标 | 数量 |
|------|------|
| 总发现数 | 48 |
| 🔴 Critical | 4 |
| 🟠 High | 11 |
| 🟡 Medium | 19 |
| 🟢 Low | 14 |

## 各领域分布

| 领域 | 发现数 |
|------|--------|
| Snapshot Consistency | 2 |
| Rounding | 4 |
| Currency Conversion | 2 |
| Exchange Rate Caching | 2 |
| Interest Formula / Negative Rates | 1 |
| Day Counting / Time Zone | 1 |
| Worker Scheduling | 1 |
| Data Integrity / Atomicity | 2 |
| Data Integrity / Balance Correctness | 2 |
| Data Integrity / Referential Integrity | 1 |
| Business Logic / Category | 1 |
| Data Integrity / Timezone | 1 |
| Data Integrity / Floating Point | 1 |
| Query aggregation | 2 |
| Query planning | 4 |
| Query aggregation / Pagination | 1 |
| Time range parsing | 1 |
| Bill parsing | 1 |
| Pagination | 1 |
| Multi-account queries / Navigation | 1 |
| Room Migration | 4 |
| AI Rule Matching | 2 |
| AI Rule Management | 1 |
| CSV Import | 1 |
| CSV Export/Import | 1 |
| Backup/Restore | 3 |
| Category Management | 1 |
| CSV Export | 1 |
| Category Normalization | 1 |
| WebDAV Sync | 1 |

## 🔴 Critical

### 1. 快照余额推导使用实时汇率而非历史汇率

- **领域**: Snapshot Consistency
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/AssetBillBalanceHistory.kt`
- **行号**: 81-83
- **期望行为**: 当 AssetBillBalanceHistory.computeBalanceAfterByBillId 从当前余额向后推导时，signedBalanceDelta 应该使用账单影响原始应用时的汇率，确保推导出的历史余额与实际运行余额匹配。
- **实际行为**: signedBalanceDelta 调用 convert()，后者委托给 BillAssetImpactService.convertAmountBetweenCurrencies，使用的是 CurrencyManager.getRate(code) — 即推导时的实时汇率。如果汇率在账单记录后发生变化，向后推导的余额就会偏离实际应用的值。例如：100 美元支出按汇率 7.0 应用到人民币（delta=-714.29 CNY）。如果汇率变为 7.5，快照重新计算 delta 为 -666.67 CNY，产生错误的历史余额。
- **影响**: 每次汇率变化并重建快照时，所有多币种账单的 historical balance-after 值都会变得不正确。误差会在多个多币种账单上累积。用户在资产详情时间线上看到错误的余额历史。
- **建议修复**: 在账单应用时存储有效汇率或转换后的资产货币 delta。在快照重新计算时使用存储值而非实时汇率。或者，在账单创建时存储 accountBalanceAfter/toAccountBalanceAfter，仅将向后推导作为旧数据的后备。

### 2. 转账目标端 delta 未四舍五入而源端已四舍五入

- **领域**: Rounding
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/BillAssetImpactService.kt`
- **行号**: 292-304
- **期望行为**: 转账的两端应一致地四舍五入。目标端 delta 应该像源端 delta 通过 convertAmountBetweenCurrencies 一样经过 roundMoney()。
- **实际行为**: sourceDeltaInCurrency（第 296-304 行）调用 convertAmountBetweenCurrencies，后者调用 MoneyConversionService.roundMoney。但 targetDeltaInCurrency（第 292-294 行）返回 bill.amount * bill.exchangeRate，**没有四舍五入**。AssetBillBalanceHistory.signedBalanceDelta（第 63-73 行）也存在同样的不对称。
- **影响**: 每次跨币种转账，源资产获得正确四舍五入的 delta，而目标资产获得未四舍五入的 delta。浮点误差在目标端累积。100 美元按汇率 7.123456 转账，目标端 delta 为 712.3456（未四舍五入），而源端已正确四舍五入。每次转账最多 0.005 的差异。
- **建议修复**: 对 targetDeltaInCurrency 结果应用 roundMoney：`return BillAssetImpactService.roundMoney(bill.amount * bill.exchangeRate)`。在 AssetBillBalanceHistory.signedBalanceDelta 的目标端分支应用相同修复。

### 3. 编辑账单：资产影响在事务外应用，崩溃窗口导致数据损坏

- **领域**: Data Integrity / Atomicity
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/AccountingFormController.kt`
- **行号**: 1704-1775
- **期望行为**: 编辑账单（用新账单替换旧账单）时，旧账单的余额撤销和新账单的余额应用应在单个原子事务中完成。如果任一步骤失败，两者都不应持久化。
- **实际行为**: AccountingFormController.handleSave() 调用 BillMutationService.replaceBill() 时传入 applyAssetImpact=false（第 1706 行）。在 replaceBill() 中，旧账单的余额在 db.withTransaction 块内被撤销（BillMutationService 第 153 行），但新账单的余额**不在那里应用**。然后，在**任何事务之外**（AccountingFormController 第 1774 行），BillAssetImpactService.applyBillBalanceImpact() 被单独调用。如果应用在事务提交和余额应用之间崩溃或抛出异常，资产余额将不正确：旧账单的影响已被撤销但新账单的影响未被应用。
- **影响**: **严重**：如果应用在编辑账单期间崩溃，资产余额可能永久不正确。旧账单的余额撤销已提交到数据库，但新账单的余额应用可能永远不会执行。这使资产余额少计了新账单的金额。没有恢复机制 — 账单记录已更新，重新编辑会双重撤销（已撤销的）旧影响。
- **建议修复**: 在 AccountingFormController.handleSave() 中，将第 1706 行的调用改为传入 applyAssetImpact=true，使撤销和应用在 replaceBill() 的 withTransaction 块内原子性地完成。或者，将整个编辑流程（replaceBill + applyBillBalanceImpact）包装在单个 db.withTransaction 调用中。

### 4. 缺少数据库版本 1 到 5 的 Room 迁移

- **领域**: Room Migration
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/local/AppDatabase.kt`
- **行号**: 338-358
- **期望行为**: 从版本 1 到 24 的所有数据库版本都应有迁移路径。第一个定义的迁移是 MIGRATION_5_6，因此版本 1-5 必须有相应的迁移（MIGRATION_1_2、MIGRATION_2_3、MIGRATION_3_4、MIGRATION_4_5）或后备策略。
- **实际行为**: 只注册了从 v5 到 v24 的迁移。没有 fallbackToDestructiveMigration() 调用。任何在数据库版本为 1、2、3 或 4 时安装应用然后升级到当前构建的用户将遇到 Room 的 IllegalStateException：'A migration from X to 24 was not found.' 这是致命崩溃。
- **影响**: 早期用户的数据完全丢失。Room 无法打开数据库，应用将在启动时崩溃。用户需要清除应用数据才能恢复，丢失所有财务记录。
- **建议修复**: 添加缺失的 MIGRATION_1_2 到 MIGRATION_4_5 定义，或者作为实用措施，在数据库构建器上调用 .fallbackToDestructiveMigration() 以允许干净重建。记录从非常旧版本升级的用户将丢失数据。

## 🟠 High

### 1. MoneyConversionService 静默地将零汇率视为 1:1 转换

- **领域**: Currency Conversion
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/MoneyConversionService.kt`
- **行号**: 47-48
- **期望行为**: 当汇率为 0.0 时应视为无效/缺失，与 CurrencyManager.convertToCny 对 rate==0.0 返回 NaN 一致。
- **实际行为**: 第 48 行：`if (fromRate == 0.0) amount else amount / fromRate`。当汇率为 0.0 时，方法返回原始金额（如同货币是人民币）。CurrencyManager.convertToCny（第 68 行）对 rate==0.0 返回 NaN。requireCurrenciesAvailable 检查（第 42 行）仅验证非空，因此 0.0 通过了守卫。
- **影响**: 如果某种货币的汇率为 0.0（API 数据损坏、退市货币），转换会将金额视为人民币。1000 日元在汇率 0.0 时变成 1000 元 — 大约 20 倍的高估。这是一个静默数据损坏向量。
- **建议修复**: 添加零汇率检查：当汇率为 0.0 时抛出 MissingCurrencyRateException 或返回 NaN。在 requireCurrenciesAvailable 中添加零汇率验证。

### 2. roundMoney 始终使用 2 位小数，对日元/韩元/越南盾等零小数货币错误

- **领域**: Rounding
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/MoneyConversionService.kt`
- **行号**: 82-84
- **期望行为**: 金额四舍五入应尊重货币的原生小数位数。日元/韩元/越南盾/福林/智利比索/冰岛克朗使用 0 位小数。CurrencyUtils.decimalPlaces() 已有此映射。
- **实际行为**: roundMoney(amount) 始终通过 BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP) 四舍五入到 2 位小数。日元金额 1234.7 变成 1234.70 而不是 1235。CurrencyUtils.formatAmount 正确使用 0 位小数显示，造成内部/显示不一致。
- **影响**: 对于零小数货币，余额计算使用不应存在的小数金额。999.7 日元的支出被存储为 999.70 而不是 1000。资产余额在多次交易后累积日元/韩元/越南盾的幽灵分数。
- **建议修复**: 添加货币感知的重载：`fun roundMoney(amount: Double, currencyCode: String)` 使用 CurrencyUtils.decimalPlaces()。更新调用者传递货币。确保 BillAssetImpactService 将货币传递给四舍五入函数。

### 3. 负利率下无 remainingPrincipal 下限保护

- **领域**: Interest Formula / Negative Rates
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/InvestmentInterestService.kt`
- **行号**: 180
- **期望行为**: 当负利息将本金减少到零以下时，本金应以 0.0 为下限，仅收取可用金额作为利息支出。
- **实际行为**: remainingPrincipal = roundMoney(remainingPrincipal + interest) 没有下限。如果本金为 0.05，-5% 的日利息为 -0.01（四舍五入后），本金变为 0.04 然后 0.03，等等。但如果本金变为负数（例如 -0.01），下一次迭代计算 (-0.01) * 负利率 = 正利息，导致当前结算运行内的反向复利。DAO 过滤器（remainingPrincipal > 0.0）防止传播到未来运行。
- **影响**: 在负利率环境下，一个 lot 的结算可能在被过滤掉之前产生单个不正确的收入账单。在当前中国市场不太可能，但在架构上不安全。
- **建议修复**: 在第 180 行本金更新后添加 `if (workingLot.remainingPrincipal <= 0.0) break`，并限制利息扣除：`val maxExpense = workingLot.remainingPrincipal; val cappedInterest = if (interest < 0) -minOf(abs(interest), maxExpense) else interest`。

### 4. startOfDay / plusDays 算术中的夏令时 Bug

- **领域**: Day Counting / Time Zone
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/InvestmentInterestService.kt`
- **行号**: 214
- **期望行为**: 日期算术应感知夏令时。向午夜时间戳添加一个日历日应落在下一个日历日的午夜，无论夏令时转换如何。
- **实际行为**: startOfDay() 使用本地时间的 Calendar.getInstance()（第 205 行），但 plusDays() 在第 214 行添加固定的 MILLIS_PER_DAY（86,400,000 毫秒）。在春季前进期间，向午夜添加 86,400,000 毫秒落在同一天的 23:00（当天只有 23 小时），因此该结果的 startOfDay 返回同一天 — 在结算循环中跳过一个日历日。在秋季回退期间，添加 86,400,000 毫秒落在第二天的 01:00，但 startOfDay 规范化回午夜，可能双重处理一天。
- **影响**: 每个夏令时转换损失（春季前进）或获得（秋季回退）一天的利息。在 1,000,000 元 3% 年利率下，约 82 元/受影响天。目前因中国不实行夏令时而缓解，但任何在夏令时地区的用户或未来扩展都会触发此问题。
- **建议修复**: 用基于 Calendar 的日期算术替换 plusDays：`Calendar.getInstance().apply { timeInMillis = dayStartMillis; add(Calendar.DATE, days) }.apply { set(Calendar.HOUR_OF_DAY, 0); ... }.timeInMillis`。或对所有日期算术使用 java.time.LocalDate。

### 5. 删除后恢复账单：资产余额因退款金额偏差

- **领域**: Data Integrity / Balance Correctness
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/BillRestoreHelper.kt`
- **行号**: 46-53
- **期望行为**: 删除账单然后恢复应使资产余额恢复到原始状态。删除-恢复循环应是完美的往返。
- **实际行为**: 对于有退款的支出账单，删除和恢复之间存在不对称。删除期间，BillAssetImpactService.revertBillBalanceImpact() 对支出使用 baseOriginalAmount（max(originalAmount, amount)），这是退款前的完整原始支出金额。恢复期间，BillAssetImpactService.applyBillBalanceImpact() 使用 bill.amount，这是退款后的减少金额。示例追踪：支出=100，退款=30，因此 expense.amount=70，expense.originalAmount=100。删除：撤销退款（-30 收入撤销），撤销支出（+100 使用 baseOriginalAmount）。净值：+70（正确）。恢复：应用支出（-70 使用 bill.amount），应用退款（+30）。净值：-40。期望净值：-70。差异：+30（恰好是退款金额）。
- **影响**: **高**：删除有退款的支出账单并恢复两者后，资产余额将多计退款金额。对于 100 支出 30 退款，余额将比应有值高 30。每次删除-恢复循环都会累积。
- **建议修复**: 在 BillRestoreHelper.restoreBills() 中，恢复有退款的支出账单时，应临时将 amount 设置为 baseOriginalAmount 用于应用余额影响，或恢复时使用 baseOriginalAmount 应用支出影响以匹配删除行为。或者，添加恢复后调整步骤，根据恢复的退款调整源支出金额。

### 6. 范围删除：部分退款删除与支出导致余额损坏

- **领域**: Data Integrity / Balance Correctness
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/BillDeleteHelper.kt`
- **行号**: 144-164
- **期望行为**: 删除支出账单时，如果只有部分退款账单在删除范围内，余额撤销应考虑剩余（未删除的）退款。
- **实际行为**: 在 deleteBillAndRevertBalanceInternal 中，当 scopeBillIds 过滤要删除的退款时，支出的余额始终使用 baseOriginalAmount 撤销（第 117-119 行，然后第 137 行）。这假设所有退款都被撤销。但如果 scopeBillIds 排除了一些退款，那些退款的余额影响仍然有效。示例：支出=100，退款A=20，退款B=30。如果只有退款A 在范围内：退款A 撤销（+20），支出使用 baseOriginalAmount 撤销（+100）。退款B 影响仍有效（+30 收入）。净值变化：+120。正确净值应为：+20 + (100-30) = +90。差异：+30（未删除退款的金额）。
- **影响**: **高**：当 deleteBillsAndRevertBalanceScoped 用于删除支出及其部分退款时，资产余额将多计未删除退款的金额总和。未删除的退款也变为孤立（其源账单已删除），使未来的操作未定义。
- **建议修复**: 删除有部分退款的支出时，余额撤销应使用 (baseOriginalAmount - 剩余退款金额总和) 而非 baseOriginalAmount。或者，通过要求删除支出时所有相关退款都在范围内来防止部分退款删除。

### 7. renderExistenceReply 中退款金额膨胀总计

- **领域**: Query aggregation
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/chat/query/QueryExecutor.kt`
- **行号**: 145-146
- **期望行为**: 计算存在性查询的总计时，退款（SUBTYPE_REFUND）应从支出总和中排除（或减去），与 renderBillsReply 和 renderAssetStatsReply 一致（两者都排除退款）。
- **实际行为**: renderExistenceReply 过滤 `it.type == Bill.TYPE_EXPENSE || it.subType == Bill.SUBTYPE_REFUND` 并对所有金额求和。退款金额在数据库中为正数，因此被加到支出总计中。例如，100 元支出加 30 元退款显示总计 130 而非期望的 70。
- **影响**: 用户询问"是否存在 X"查询（例如"有没有星巴克消费？"）看到膨胀的总计，将退款金额作为额外支出。
- **建议修复**: 将第 145-146 行改为：`val expenseBills = bills.filter { it.type == Bill.TYPE_EXPENSE && it.subType != Bill.SUBTYPE_REFUND }`，匹配 renderAssetStatsReply（第 159 行）使用的过滤器。

### 8. validateOrClarify 对非资产查询触发虚假资产澄清

- **领域**: Query planning
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/chat/query/QueryPlanner.kt`
- **行号**: 179-189
- **期望行为**: 资产消歧澄清应仅在查询涉及特定资产且用户文本在多个资产间真正模糊时触发。
- **实际行为**: validateOrClarify 对**所有意图**的**完整用户文本**运行 `resolveAsset(null, userText, context)` 和 `findAssetCandidates`，包括类别查询和一般账单查询。如果用户文本恰好包含匹配 2+ 资产名称的子字符串，即使用户明确询问类别，也会强制触发 CLARIFY 意图。例如，如果资产包含"交通"和"交通银行"，查询"本月交通花了多少"会触发资产消歧提示而非回答类别统计问题。
- **影响**: 当查询文本恰好匹配多个资产名称时，用户会被不必要的澄清问题打断，即使他们问的是类别或一般账单查询。
- **建议修复**: 仅在解析的意图是资产相关时（QUERY_ASSET_STATS 或 OPEN_ASSET_STATS_PAGE）运行多资产澄清检查。对于其他意图，跳过资产消歧逻辑。

### 9. AI 规则冲突：多条匹配规则无确定性优先级地应用

- **领域**: AI Rule Matching
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/AIService.kt`
- **行号**: 1180-1194
- **期望行为**: 当多条规则匹配账单文本时，应有确定性的优先级机制（例如最具体的关键词获胜，或用户定义的优先级顺序）。只有获胜规则应被应用。
- **实际行为**: 代码使用 allRules.filter 查找所有匹配规则，然后用 forEach 迭代。每条匹配规则覆盖账单 JSON 上的 type、category_name、asset_name 和 to_asset_name。列表中的最后一条规则获胜。没有优先级、具体性检查或冲突解决。规则应用顺序取决于数据库插入顺序（getAllRulesList 的 ORDER BY id DESC）。
- **影响**: 如果用户有一条宽泛规则关键词"花"（设置类别为"购物"）和一条具体规则关键词"花呗"（设置类别为"还款"），备注包含"花呗"时两条规则都匹配。最后迭代的那条获胜，从用户角度看是非确定性的。账单可能被错误分类。
- **建议修复**: 添加规则优先级字段，或使用关键词具体性（更长的关键词 = 更高优先级），或仅应用第一条匹配规则而非所有匹配。

### 10. RuleDialogHelper.split 关键词编辑删除原始规则并可能创建重复

- **领域**: AI Rule Management
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/RuleDialogHelper.kt`
- **行号**: 192-205
- **期望行为**: 编辑现有规则并拆分其关键词为多个（例如"苹果,牛奶"）时，每个结果关键词应创建单独的新规则，原始规则应被正确处理（更新第一个关键词或删除）。
- **实际行为**: 当 keywords.size > 1 且原始规则的 id > 0 时：第一个关键词获得 rule.id（因此 UPDATE 原始规则），其余关键词获得 id=0（INSERT 新规则）。然而，查看 AiRuleManageActivity.saveRuleWithKeywordConflictPrompt（第 301 行）：当 toSave.id > 0 时，调用 dao.updateRule(toSave)，就地更新现有行。其他关键词作为新规则插入。这意味着：(1) 原始规则的关键词变为仅第一个关键词，(2) 为其余创建新规则，(3) 旧关键词被有效覆盖。但旧关键词文本丢失 — 原始规则是"苹果,牛奶"现在变为仅"苹果"。
- **影响**: 编辑规则拆分关键词时数据丢失。原始关键词文本被永久更改。如果用户打算保留原始组合关键词，它已消失。
- **建议修复**: 拆分关键词时，要么：(a) 始终全部插入为新规则（id=0）并删除原始规则，或 (b) 更新第一个关键词并插入其余，但警告用户原始关键词将被修改。

### 11. CsvManager.isRefundLikeRemark 的子字符串匹配过于宽泛

- **领域**: CSV Import
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/backup/CsvManager.kt`
- **行号**: 279-286
- **期望行为**: 退款检测应足够具体以避免误报。只有实际退款的账单才应被分类为 SUBTYPE_REFUND。
- **实际行为**: 检查 `text.contains('退款')` 匹配任何备注中任何位置包含子字符串"退款"的记录，包括"不是退款"（不是退款）、"退款申请被拒"（退款申请被拒绝）或恰好包含"退款"的类别名称。此检查仅在 rawType 为 INCOME 且 subType 为 NORMAL 时运行，但宽泛的子字符串匹配意味着非退款收入账单可能被错误重新分类为退款子类型。
- **影响**: 备注中包含"退款"的非退款收入记录在 CSV 导入期间会被错误标记为 SUBTYPE_REFUND。这影响统计显示和可能显示虚假退款条目的退款跟踪功能。
- **建议修复**: 使检查更具体：要求"退款"出现在备注开头（startsWith），或要求前面有括号类字符，或使用词边界匹配。移除与 `text.startsWith('退款')` 重复的冗余 `text.contains('退款')`。

## 🟡 Medium

### 1. 过期汇率因地图仅合并不清理而持续存在

- **领域**: Exchange Rate Caching
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/CurrencyManager.kt`
- **行号**: 191
- **期望行为**: 当从 API 更新汇率时，本地汇率图应精确反映 API 响应。旧的或弃用的汇率应被移除。
- **实际行为**: 第 191-193 行：rates.putAll(newRates) 将新汇率合并到现有地图但从不移除条目。如果某种货币在之前的 API 响应中存在但在新响应中缺失，旧的过期汇率会无限期持续。DEFAULT_RATES 也充当永久后备。
- **影响**: 过期汇率静默持续并用于转换。如果 API 停止提供某种汇率，最后已知汇率继续使用而无任何指示它是过期的。对波动性货币尤其危险。
- **建议修复**: 完全替换汇率图：rates.clear(); rates.putAll(newRates); rates.put(CNY, 1.0)。或每次使用从 API 响应构建的新 ConcurrentHashMap。

### 2. 任何单个账单缺失汇率时快照重建完全崩溃

- **领域**: Snapshot Consistency
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/BillBalanceSnapshotService.kt`
- **行号**: 19-39
- **期望行为**: 重建快照时，如果所需汇率缺失，重建应优雅跳过受影响的账单并继续处理其他账单。
- **实际行为**: rebuildSnapshotsForAsset 调用 computeBalanceAfterByBillId，后者调用 signedBalanceDelta，再调用 convertAmountBetweenCurrencies，后者抛出 MissingCurrencyRateException。调用链中任何位置都没有 try-catch。单个缺失汇率的账单会崩溃该资产的整个重建。
- **影响**: 如果任何汇率不可用，整个快照重建失败。该资产的所有其他账单丢失其计算的 balance-after 值。用户看不到该资产的任何余额历史。
- **建议修复**: 在 signedBalanceDelta 或 computeBalanceAfterByBillId 中用 try-catch 包装转换，MissingCurrencyRateException 时返回 0.0 delta（跳过该账单）。继续处理剩余账单。

### 3. estimateExchangeRateToCny 对缺失/零汇率返回 1.0，掩盖失败

- **领域**: Currency Conversion
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/MoneyConversionService.kt`
- **行号**: 71-79
- **期望行为**: 当汇率不可用或为零时，应指示失败（NaN 或抛出），与 CurrencyManager 行为一致。
- **实际行为**: 第 78 行：rateProvider(normalized) ?: return 1.0。第 79 行：if (rateToCurrency != 0.0) roundRate(1.0 / rateToCurrency) else 1.0。null 和 0.0 汇率都返回 1.0（恒等汇率）。UI 无法区分人民币平价和汇率不可用。
- **影响**: 汇率缺失时 UI 显示 1.0 的汇率，暗示与人民币 1:1 平价。用户可能不知道转换失败且余额受影响。掩盖了 MissingCurrencyRateException。
- **建议修复**: 当汇率为 null/0.0 时返回 Double.NaN 或抛出。更新 UI 调用者处理 NaN 显示汇率不可用消息。

### 4. Worker 无初始延迟或弹性窗口；Doze 模式可延迟结算数小时

- **领域**: Worker Scheduling
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/InvestmentInterestWorker.kt`
- **行号**: 31
- **期望行为**: 周期性 worker 应每天接近一致的时间运行，理想情况下有弹性窗口以允许 OS 优化电池。
- **实际行为**: PeriodicWorkRequestBuilder<InvestmentInterestWorker>(1, TimeUnit.DAYS).build() 使用所有默认值：无 setInitialDelay，无 setFlexTimeInterval。在 Android Doze 模式下，周期性工作可被延迟数小时。调度后的首次运行立即触发而非在合理时间。
- **影响**: 用户可见的利息计提可能延迟数小时。不是数学正确性问题（批处理结算处理间隔），但与标注"自动结算"的账单备注暗示的每日精度不一致。
- **建议修复**: 添加 .setFlexTimeInterval(30, TimeUnit.MINUTES) 以允许电池优化，并考虑 setInitialDelay 将首次运行对齐到合理小时（例如凌晨 2 点）。

### 5. 恢复账单：退款源账单金额在恢复期间未调整

- **领域**: Data Integrity / Referential Integrity
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/BillRestoreHelper.kt`
- **行号**: 33-56
- **期望行为**: 恢复退款账单时，源支出账单的金额应减少退款金额（以镜像退款创建时发生的情况）。deleted_bills 中的支出账单存储减少后的金额（例如 100 支出 30 退款为 70），但恢复后退款再次被应用而不调整源。
- **实际行为**: BillRestoreHelper 独立恢复支出和退款账单。它对每个调用 applyBillBalanceImpact，但不调整源支出账单的金额字段以反映恢复的退款。支出账单以 amount=70 恢复（删除前的减少值），退款以 amount=30 恢复。支出金额保持 70，即使退款现在已激活。这与退款创建流程（saveRefundBill）不一致，后者明确更新源支出的金额。
- **影响**: 恢复有退款的支出-退款对后，支出账单的金额字段与实际退款状态不一致。显示可能显示不正确的剩余金额，未来对此支出的退款操作可能行为不正确（例如允许过度退款，因为金额看起来低于真实可用余额）。
- **建议修复**: 在 BillRestoreHelper.restoreBills() 中，恢复退款账单后，将源支出账单的金额更新为 (sourceAmount - refundAmount)，镜像 saveRefundBill() 中的逻辑。应在同一事务内完成。

### 6. 批量删除：相关账单在单独事务中删除，非原子

- **领域**: Data Integrity / Atomicity
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/BillDeleteHelper.kt`
- **行号**: 68-92
- **期望行为**: 删除一批相关账单（例如支出及其退款）时，所有删除应在单个原子事务中发生。
- **实际行为**: deleteBillsAndRevertBalanceInternal 迭代 uniqueBills 并对每个账单单独调用 deleteBillAndRevertBalanceInternal。每个单独调用打开自己的 db.withTransaction 块。如果批次包含支出及其退款，它们在单独的事务中删除。如果应用在事务之间崩溃，一些账单可能被删除而其他账单保留。
- **影响**: 部分批次删除可使数据库处于不一致状态。例如，如果退款被删除（及其源支出的金额已恢复）但支出删除尚未执行，支出存在但金额不正确。虽然这是仅崩溃场景，但没有恢复机制。
- **建议修复**: 将整个批次删除包装在单个 db.withTransaction 调用中，而非对每个账单使用单独事务。确保整个批次的原子性。

### 7. looksLikeWrite 误报阻止合法查询

- **领域**: Query planning
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/chat/query/QueryPlanner.kt`
- **行号**: 320-324
- **期望行为**: 只有实际的写入/修改请求应被阻止；恰好在只读上下文中包含"记账"或"修改"等关键词的查询应继续。
- **实际行为**: 函数检查用户文本中任何位置是否出现任何写入关键词。"记账"出现在有效查询短语中，如"我的记账情况"（我的记账情况）、"记账习惯"（记账习惯）、"记了几笔账"（记录了多少账单）。类似地，"修改"出现在"修改了几笔"（修改了多少）。这些查询被错误分类为 UNSUPPORTED 并被拒绝。
- **影响**: 询问恰好包含"记账"或"修改"等单词的只读问题的用户会收到错误响应"此请求涉及写入操作，我只能帮助查询和导航"而非实际答案。
- **建议修复**: 使用更精确的匹配：检查写入意图的句子模式而非裸关键词包含。例如，检查"记账"是否出现在句首（命令形式）而非文本中的任何位置，或与"帮我"或"来一笔"等祈使标记配对。

### 8. LATEST 聚合仅加载 50 条账单，可能遗漏实际最新

- **领域**: Query aggregation / Pagination
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/chat/query/QueryExecutor.kt`
- **行号**: 76-78
- **期望行为**: 当用户询问特定类型的最新账单（例如"最后一笔还款"）时，系统应找到实际最近的匹配账单，无论存在多少其他账单。
- **实际行为**: 对于 LATEST 聚合，代码从数据库加载 50 条最近账单，然后应用内存中的 billType、asset、category 和 keyword 过滤。如果超过 50 条账单且实际最新的匹配账单在最近 50 条之外，它将不会被找到。例如，如果用户询问"最后一笔还款"且最近的还款是时间顺序上的第 52 条账单，它将被遗漏，因为只加载了最近 50 条账单（其中大多数是常规支出）。
- **影响**: 询问特定类型最新账单的用户可能得到"未找到最近账单"，即使此类账单存在，如果它们不在最近 50 条总体账单中。
- **建议修复**: 将 billType 过滤器推入数据库查询（为 type/subType 添加 WHERE 子句），使 50 限制适用于已过滤结果，或将限制增加到更安全的值如 500。

### 9. 不支持"最近30天"等相对时间范围

- **领域**: Time range parsing
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/chat/ai/AiTimeRangeParser.kt`
- **行号**: 15-43
- **期望行为**: 常见的相对时间表达如"最近30天"、"过去7天"、"最近3个月"、"过去一周"应被解析为正确的时间范围。
- **实际行为**: 解析器仅处理固定的日历周期短语：今天/今日、昨天、本周/这周、上周、本月/这个月、上个月/上月、今年/本年。任何相对范围表达（例如"最近30天"、"过去一周"、"last N days"）返回 null，落入澄清提示要求用户指定时间范围。
- **影响**: 以"最近N天/周/月"表达时间范围的用户被要求使用精确日历周期术语重新指定，造成令人沮丧的交互循环。
- **建议修复**: 为"最近N天/天"、"过去N周/周"、"最近N个月/个月"添加正则表达式模式，并在 when 块中使用适当的日期算术。

### 10. renderCategoryStatsReply 混合所有账单类型

- **领域**: Query aggregation
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/chat/query/QueryExecutor.kt`
- **行号**: 170-183
- **期望行为**: 类别统计应区分支出和收入类别，或至少注明总计包含混合类型。
- **实际行为**: renderCategoryStatsReply 按类别名称分组所有账单并对金额求和，不按账单类型过滤。如果类别名称在支出和收入间共享（例如"红包"可以是两者），或 billType 过滤器为 ANY（默认），支出和收入金额被加在一起。用户询问"类别 X 多少钱"会看到支出和收入的无意义聚合。
- **影响**: 当用户未指定账单类型时，类别统计可能显示误导性总计，因为支出和收入被合并为每类别单一总和。
- **建议修复**: 当 billType 为 ANY 时，默认仅过滤支出账单（因为类别统计最常关于支出），或分别呈现支出和收入细分。

### 11. shouldPreferLocal 可能用错误的本地意图覆盖正确的模型意图

- **领域**: Query planning
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/chat/query/QueryPlanner.kt`
- **行号**: 369-373
- **期望行为**: 当 AI 模型返回格式良好、高置信度的响应时，本地后备启发式不应覆盖它，除非有明确的缺陷。
- **实际行为**: 第 369-373 行包含通用偏好规则（不限于特定意图对），可覆盖任何模型响应。例如，如果模型正确返回高置信度的 QUERY_CATEGORY_STATS 但本地后备解析了 assetId（因为用户文本包含资产名称子字符串），第 369 行（`modelAction.slots.assetId == null && localAction.slots.assetId != null`）会偏好本地的 QUERY_ASSET_STATS 响应。这将意图从正确的类别查询切换为不正确的资产查询。
- **影响**: AI 模型正确识别意图但本地后备恰好解析了更多实体的复杂查询可被错误覆盖，导致执行错误类型的查询。
- **建议修复**: 将 assetId/categoryId/keyword 偏好规则限定为仅在本地和模型意图兼容时应用（例如两者都是 QUERY_BILLS 或都是 QUERY_ASSET_STATS）。如果意图不同，偏好模型的更高级意图分类。

### 12. ChatBillMessageParser.parseBillIds 在任何单个格式错误条目上丢失所有 ID

- **领域**: Bill parsing
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ChatBillMessageParser.kt`
- **行号**: 12-20
- **期望行为**: 如果 JSON 数组中的一个账单 ID 格式错误，解析器应跳过该条目并仍返回有效 ID。
- **实际行为**: parseBillIds 映射所有数组条目 `arr.getString(it).toLong()`。如果任何单条目抛出异常（例如非数字字符串、null 或缺失），整个函数捕获异常并返回 emptyList()，丢弃所有成功解析的 ID。
- **影响**: 账单 ID JSON 数组中的单个损坏或意外条目导致所有账单 ID 丢失。这可能阻止账单弃用跟踪工作，可能导致重复账单显示或不正确的编辑历史。
- **建议修复**: 使用 mapNotNull 替代 map：`(0 until arr.length()).mapNotNull { runCatching { arr.getString(it).toLong() }.getOrNull() }`。

### 13. CsvManager.export 对金额字段使用原始 Double.toString()

- **领域**: CSV Export/Import
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/backup/CsvManager.kt`
- **行号**: 62-79
- **期望行为**: CSV 中的金额值应以始终可解析回相同 Double 值的格式化，避免科学计数法。
- **实际行为**: Kotlin 的 Double.toString() 可对非常大或小的值产生科学计数法（例如 1.23E8、5.0E-4）。虽然导入解析器使用 toDoubleOrNull() 处理科学计数法，但导出格式可能混淆在 Excel 等电子表格应用中打开 CSV 的用户，这些应用可能误解或自动转换这些值。此外，浮点精度伪影如 0.30000000000000004 可能出现在导出数据中。
- **影响**: 导出的 CSV 文件在电子表格应用中可能显示意外值。往返导出-导入应安全（因 toDoubleOrNull() 处理），但用户编辑的 CSV 文件可能引入解析错误。
- **建议修复**: 使用固定精度格式化器如 String.format(Locale.US, "%.2f", amount) 或 DecimalFormat 确保一致的小数表示。

### 14. MIGRATION_12_13 为所有资产设置 interestLastSettledAt 为迁移时间

- **领域**: Room Migration
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/local/AppDatabase.kt`
- **行号**: 127-133
- **期望行为**: interestLastSettledAt 应反映合理的值，如每个资产的 createTime，以便利息计算有准确的基线。
- **实际行为**: 迁移使用 System.currentTimeMillis() 作为新列的 DEFAULT。由于 ALTER TABLE 执行一次，所有现有资产获得完全相同的时间戳（用户升级应用的时刻），而非每个资产的有意义时间。
- **影响**: 对于 annualInterestRate > 0 的投资/账户资产，利息计算将认为从应用创建到现在的所有累积利息为零（因为 lastSettledAt 刚设为现在）。升级前应累积的任何利息丢失。这是轻微的数据准确性问题，因为该功能是新增的。
- **建议修复**: 从每个资产的 createTime 回填 interestLastSettledAt：`UPDATE assets SET interestLastSettledAt = createTime WHERE interestLastSettledAt = {migrationTimestamp}`。

### 15. MIGRATION_20_21 为无账单资产留下 billBalanceFromTime=0

- **领域**: Room Migration
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/local/AppDatabase.kt`
- **行号**: 276-285
- **期望行为**: billBalanceFromTime=0 的资产应回填到 createTime 作为合理默认值（从创建起显示余额历史）。
- **实际行为**: MIGRATION_21_22（第 282 行）回填：`UPDATE assets SET billBalanceFromTime = createTime WHERE billBalanceFromTime = 0`。然而 MIGRATION_22_23 仅更新 `WHERE billBalanceFromTime = createTime AND createTime > MIN(bill.time)` 的资产。对于无账单资产，MIN(bill.time) 返回 NULL，因此 WHERE 条件为假且行被跳过。这些资产保留 billBalanceFromTime = 0 或 createTime（来自 v21_22），这是正确的。但如果资产的 createTime 设置不正确（例如恢复期间），v21_22 回填可能设置错误的 billBalanceFromTime。
- **影响**: 无账单资产显示正确行为。有账单但 createTime 过新的资产如果未被 v22_23 修复可能有不正确的余额历史开始日期。
- **建议修复**: 当前逻辑基本正确。v22_23 迁移提供安全网。考虑添加用户面向的选项手动重置每资产的 billBalanceFromTime。

### 16. BackupRepository.mergeRestoreFullData 忽略 deletedBills 和 investmentLots

- **领域**: Backup/Restore
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/repository/BackupRepository.kt`
- **行号**: 161-312
- **期望行为**: 合并恢复应处理备份中存在的所有数据类型，包括已删除账单和投资 lot，以确保完整数据恢复。
- **实际行为**: mergeRestoreFullData 函数签名仅接受 assets、bills、categories、rules 和 chatMessages。它不接受 deletedBills 或 investmentLots 参数。当合并恢复使用包含这些的备份调用时，它们被静默丢弃。
- **影响**: 投资 lot 记录（包含本金金额、结算日期等）和已删除账单审计跟踪在合并恢复期间永久丢失。这对依赖 lot 记录进行利息计算的投资跟踪功能尤其有害。
- **建议修复**: 向 mergeRestoreFullData 添加 deletedBills 和 investmentLots 参数。实现投资 lot 的去重逻辑（例如按 sourceBillId）并使用类似的 time+amount 去重插入已删除账单。

### 17. CategoryRepository.deleteById 删除子类别时不迁移其账单

- **领域**: Category Management
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/repository/CategoryRepository.kt`
- **行号**: 134-139
- **期望行为**: 删除父类别时，要么：(a) 如果存在子类别则阻止删除，或 (b) 级联删除子类别并正确处理其关联账单（迁移或置空）。
- **实际行为**: deleteById 加载所有类别，按 parentId 过滤子类别，用 categoryDao.deleteById 删除它们（按 id 简单 DELETE），然后删除父类别。它不检查或处理与子类别关联的账单。由于 ForeignKey SET_NULL 在 bills.categoryId 上，categoryId 将被 SQLite 自动设为 NULL。然而，这些账单上的 categoryName 文本字段未更新，创建了 categoryId 为 null 但 categoryName 仍引用已删除类别的不一致。
- **影响**: 已删除子类别下的账单将有 categoryId=null 但其 categoryName 字段仍引用旧类别名称。这可导致账单在 UI 中显示孤立类别名称，基于类别的搜索/计费可能产生不一致结果。
- **建议修复**: 删除子类别前，调用 billDao 清除或更新受影响账单的 categoryName 字段，或使用 deleteCategoryAndMigrateBills 替代原始 deleteById 路径。

### 18. 多条规则可覆盖 type/category 字段而用户不知情

- **领域**: AI Rule Matching
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/AIService.kt`
- **行号**: 1183-1194
- **期望行为**: 当规则覆盖 AI 结果时，覆盖应透明且仅最具体/最相关的规则应应用。
- **实际行为**: forEach 循环顺序迭代所有匹配规则。每条规则无条件覆盖 type、category_name、asset_name 和 to_asset_name。仅指定 targetCategory 但未指定 targetType 的规则不会设置 targetType（由于 takeIf null 检查），但仍会覆盖 category_name。相反，指定 targetType=3（还款）的规则将强制设置 subType=1 和 category_name='还款'，即使后续规则设置了不同的 targetCategory — 因为还款块先执行并设置 category_name='还款'，然后后续规则的 targetCategory 检查覆盖它。多条规则间的交互不可预测。
- **影响**: 当多条规则匹配时，账单的最终状态取决于规则迭代顺序（即数据库行顺序）。用户无法预测或控制哪条规则对重叠关键词"获胜"。
- **建议修复**: 仅应用第一条匹配规则（forEach 首次迭代后 break），或实现评分/优先级系统。

### 19. 合并恢复 relatedBillId 解析可能对现有账单失败

- **领域**: Backup/Restore
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/repository/BackupRepository.kt`
- **行号**: 266-286
- **期望行为**: 合并恢复期间解析 relatedBillId 时，代码应找到正确的相关账单，无论是刚插入的还是本地已存在的。
- **实际行为**: 第 269 行代码计算 `match = db.billDao().countDuplicateBills(...)` 但结果存储在变量 `match` 中**从未使用**。代码然后查询 `getBillsBetweenTimesList(oldRelatedBill.time, oldRelatedBill.time)` 并按 amount+type+accountName 找到第一个候选。如果存在多个相同 time/amount/type/account 的账单（合并场景中可能），可能链接到错误的账单作为相关账单。此外，countDuplicateBills 使用 LIMIT 1 返回 Int，只能返回 0 或 1，使其成为合并场景的差劣去重检查。
- **影响**: 合并恢复中，退款-原始账单链接可能指向错误账单（如果存在多个相同 time/amount/type/account 的账单）。未使用的 `match` 变量表明未完成或放弃的重构。
- **建议修复**: 移除未使用的 `match` 变量。使用更具体的标识符（例如账单备注或 id）解析相关账单引用，或使用完整恢复路径中的 billIdMap。

## 🟢 Low

### 1. addBalanceDelta 使用未四舍五入的 SQL 算术，累积浮点漂移

- **领域**: Rounding
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/local/dao/AssetDao.kt`
- **行号**: 120-121
- **期望行为**: 通过 delta 更新资产余额后，结果余额应四舍五入到货币适当的小数位。
- **实际行为**: SQL `UPDATE assets SET balance = balance + :delta` 直接执行浮点加法。虽然 delta 已四舍五入，但现有余额可能从之前的加法累积了 IEEE 754 表示误差（例如 100.0 + 0.1 + 0.2 = 100.30000000000001）。
- **影响**: 数百次交易后，资产余额漂移几分之一分。显示显示 1000.0000000001 而非 1000.00。轻微美观问题但导致显示不一致。
- **建议修复**: 添加周期性余额规范化，在批量操作后四舍五入余额，或在 SQL 中使用 ROUND()：`UPDATE assets SET balance = ROUND(balance + :delta, 2)`。

### 2. API 获取从未成功时 DEFAULT_RATES 无限期使用

- **领域**: Exchange Rate Caching
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/CurrencyManager.kt`
- **行号**: 23-30
- **期望行为**: 默认后备汇率应明确标记为近似值。如果真实汇率从未获取，应用应突出警告用户余额是近似的。
- **实际行为**: DEFAULT_RATES 是初始汇率图（第 32 行）。如果首次 API 获取失败，这些硬编码汇率无限期持续。getRateStatusSummary 仅在 lastUpdate<=0 时报告未初始化状态，但汇率仍静默用于所有转换。
- **影响**: 首次无网络启动时，所有转换使用可能过期的近似汇率。用户看到显著错误的余额，仅有微妙的状态消息。
- **建议修复**: 使用 DEFAULT_RATES 时（lastUpdateTime==0）添加突出的警告横幅。考虑在至少获取一次真实汇率前拒绝转换，或明确标记所有计算值为近似值。

### 3. 双重四舍五入：利息和本金均每次迭代四舍五入

- **领域**: Rounding
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/InvestmentInterestService.kt`
- **行号**: 160
- **期望行为**: 利息应仅在记录账单金额时四舍五入一次。
- **实际行为**: 第 160 行将日利息四舍五入到 2 位小数（roundMoney），然后第 180 行再次将更新后的本金四舍五入到 2 位小数（再次 roundMoney）。双重四舍五入可引入每天最多 0.005 的累积误差。
- **影响**: 每个 lot 每年最多约 1.83 元累积误差。实际可忽略但技术上不正确的会计。
- **建议修复**: 仅对利息值四舍五入（第 160 行）。对于第 180 行的本金更新，存储未四舍五入的值或使用更高精度（例如 4 位小数）进行内部本金跟踪，仅在生成账单时四舍五入。

### 4. 类别更改对资产余额无影响（正确，已记录）

- **领域**: Business Logic / Category
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/BillMutationService.kt`
- **行号**: 97-175
- **期望行为**: 更改账单类别不应影响资产余额，因为类别纯粹用于分类和显示。
- **实际行为**: 类别更改仅更新账单上的 categoryName 字段。未触发余额重新计算。这是正确行为 — 类别不携带财务语义。
- **影响**: 无。这是正确行为。
- **建议修复**: 无需修复。

### 5. 日期/时间处理使用 SimpleDateFormat，非时区安全

- **领域**: Data Integrity / Timezone
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/AccountingFormController.kt`
- **行号**: 484, 491-498
- **期望行为**: 账单时间戳应一致存储和解析，无论设备时区或夏令时变化。
- **实际行为**: 应用使用带 Locale.getDefault() 且无显式时区的 SimpleDateFormat 解析和格式化账单时间。时间字段存储为自纪元以来的毫秒数（UTC），这是明确的。然而，用户显示时间（yyyy-MM-dd HH:mm:ss 格式）的解析使用设备默认时区。如果用户更改时区或跨越夏令时边界，相同的显示时间字符串可解析为不同的毫秒值。BillRestoreHelper 使用 SimpleDateFormat('MM-dd HH:mm') 进行内容格式化，在夏令时回退期间可能产生模糊时间。
- **影响**: 低：正常使用中不太可能造成问题，因为用户在当前时区看到并确认时间。夏令时转换周围的边缘情况（例如重复的凌晨 1:00-2:00 小时）理论上可导致账单存储在错误时间，但对余额的影响为零，因为余额不依赖时间。
- **建议修复**: 考虑使用 java.time API（LocalDateTime、ZonedDateTime）正确处理夏令时，或如果需要一致的 UTC 存储，显式设置 SimpleDateFormat 实例的时区为 TimeZone.getTimeZone("UTC")。

### 6. 退款金额验证使用不一致的浮点 epsilon

- **领域**: Data Integrity / Floating Point
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/BillMutationService.kt`
- **行号**: 202
- **期望行为**: 退款金额验证应使用一致的 epsilon 进行跨所有相关操作的浮点比较。
- **实际行为**: 在 saveRefundBill 中，退款 delta 验证使用：`require(delta <= latestOriginal.amount + 1e-9)`。epsilon 1e-9 适合 Double 精度。然而，代码库中的其他比较使用不同方法：coerceIn 操作使用精确比较，BillDao 中的账单去重查询使用 `ABS(amount - :amount) < 0.001`（大得多的 epsilon）。这种不一致可能导致边缘情况，退款通过验证但因浮点算术导致支出金额略微为负。
- **影响**: 低：退款验证中的 1e-9 epsilon 足够紧凑，不太可能造成实际问题。第 207 行的 coerceIn(0.0, baseOriginalAmount) 提供安全网。然而，重复操作（退款、编辑、再退款）可累积浮点误差。
- **建议修复**: 对关键金额计算使用 BigDecimal，或在所有金额算术操作后应用一致的四舍五入（例如 MoneyConversionService.roundMoney）。

### 7. normalizeToken 剥离"卡"字符可导致虚假资产匹配

- **领域**: Query planning
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/chat/query/QueryPlanner.kt`
- **行号**: 326-330
- **期望行为**: 资产名称规范化应在保留有意义字符的同时移除噪声用于匹配目的。
- **实际行为**: normalizeToken 从输入和资产名称中移除所有"卡"字符出现。这使不同资产坍缩："招商银行"和"招商银行卡"都规范化为"招商银行"。此外，"信用卡"（信用卡）规范化为"信用"，可能部分匹配包含"信用"的不相关资产（例如"信用社"）。
- **影响**: 在少数情况下，名称仅因"卡"的存在而不同的资产变得不可区分，包含"卡"的查询可能匹配意外资产。影响有限，因为大多数资产名称足够不同。
- **建议修复**: 考虑使用更针对性的规范化，仅将"卡"作为后缀剥离（例如替换尾部的"卡"或"信用卡"）而非移除所有出现，或使用基于字典的停用词列表。

### 8. QueryExecutor 中大时间范围查询无分页

- **领域**: Pagination
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/chat/query/QueryExecutor.kt`
- **行号**: 79-83
- **期望行为**: 大时间范围查询（例如"今年"）应有某种形式的分页或限制以防止过度内存使用。
- **实际行为**: 对于非 LATEST 聚合，loadAndFilterBills 加载时间范围内的所有账单无限制。"今年"查询可加载数千条账单到内存，在内存中应用过滤器，然后将所有格式化为单个文本响应。
- **影响**: 对于高账单量用户，年度范围查询可能导致慢响应时间或内存压力。结果文本回复也可能过长难以阅读。然而，实际影响有限，因为回复文本自然截断且 Android 处理适度内存使用优雅。
- **建议修复**: 向 loadBetween 查询添加合理限制（例如 500），并在结果被截断时在回复中提及。或者，将聚合（分组、求和）推入数据库查询（通过支持 GROUP BY 的 DAO 方法）。

### 9. QueryNavigator 不向统计页面传递 billType 过滤器

- **领域**: Multi-account queries / Navigation
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/chat/query/QueryNavigator.kt`
- **行号**: 12-31
- **期望行为**: 从按账单类型过滤的查询导航到统计页面时，统计页面应预过滤以匹配。
- **实际行为**: openStatsPage 发布包含 timeRange、bookName 和 currency 的 StatsExternalQueryFilter，但**不包含 billType**。openAssetStatsPage 确实通过 intent extra 传递 billType。因此对于非资产统计导航，账单类型过滤器丢失。
- **影响**: 用户询问"本月支出统计"且系统导航到统计页面时，仅支出过滤器未应用。用户在统计页面看到所有账单类型，必须手动重新过滤。
- **建议修复**: 向 openStatsPage 发布的 StatsExternalQueryFilter 添加 billType，或如果统计页面支持则作为 intent extra 传递。

### 10. restoreFullData 在重新解析前设置 relatedBillId=null

- **领域**: Backup/Restore
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/repository/BackupRepository.kt`
- **行号**: 82-109
- **期望行为**: 完整恢复期间，relatedBillId 引用应正确从旧 ID 映射到新 ID。
- **实际行为**: 代码正确在插入时设置 relatedBillId=null，收集待处理引用，然后重新解析它们。然而 pendingRelated.forEach 块对每个账单重新查询数据库（getBillById）以更新它，这是 N+1 查询模式。对于大型备份（数千条有退款引用的账单），这导致显著 I/O。
- **影响**: 恢复期间对有许多退款链接账单的数据库性能下降。不是正确性问题，但恢复时间可能明显缓慢。
- **建议修复**: 批量处理 relatedBillId 更新：收集所有 (newBillId, newRelatedId) 对并执行单个批量更新。

### 11. 导出的 CSV 浮点值可能不完全往返

- **领域**: CSV Export
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/backup/CsvManager.kt`
- **行号**: 62-79
- **期望行为**: 所有数值字段应以足够的精度导出以在往返（导出然后导入）中生存而不改变值。
- **实际行为**: 对 exchangeRate（Double）使用原始 toString() 可产生如 7.123456789012345 的值，虽然可被 toDoubleOrNull() 解析，但在电子表格应用中可能显示不同。amount 和 originalAmount 字段类似使用原始 toString()。Fee 也使用 toString()。
- **影响**: 对大多数实际值低风险，但高精度汇率或非常小费用的边缘情况在通过电子表格编辑器往返后可能显示精度差异。
- **建议修复**: 对所有浮点字段使用 DecimalFormat 并足够精度（例如 15 位有效数字）。

### 12. CategoryNameNormalizer 分隔符正则不匹配其他地方使用的所有分隔符

- **领域**: Category Normalization
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/CategoryNameNormalizer.kt`
- **行号**: 8
- **期望行为**: 规范化器应识别代码库中使用的所有分隔符变体并一致规范化为 ' - '。
- **实际行为**: 规范化器的正则是：`/\s*(/:::/|/::/|>|/|\\|\\||::|:|·)\s*/`。然而 CategoryIconHelper.findCategoryIcon（第 18-24 行）也处理 ' > '（带空格的 >）。虽然规范化器的正则正确处理 ' > '（因为它剥离分隔符周围的空格），但代码库不一致地使用 ' > ' 作为分隔符（CategoryRepository.findCategoryByDisplayName 第 47 行：将 ' > ' 替换为 '/::/'）。这意味着存储为 ' - ' 分隔符的类别可能无法被 findCategoryByDisplayName 找到（如果搜索文本使用 ' > '）。
- **影响**: 某些分隔符样式的类别图标查找可能失败，导致回退到默认图标。无数据丢失，但视觉不一致。
- **建议修复**: 在 findCategoryByDisplayName 中规范化所有分隔符变体后再查找，或确保规范化器始终产生单一规范分隔符。

### 13. WebDAV 备份文件名与共享设备名称的碰撞风险

- **领域**: WebDAV Sync
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/backup/WebDavClient.kt`
- **行号**: 54-69
- **期望行为**: 备份文件名应每设备唯一，以防止多设备同步到同一 WebDAV 文件夹时意外覆盖。
- **实际行为**: 文件名格式为 'backup_{deviceName}_{mode}_{timestamp}.bak'。如果两台设备有相同型号名称（例如都是 'Pixel 7'），它们的 deviceName 将相同（因为 CloudBackupActivity 默认为 Build.MODEL）。如果它们碰巧在同一秒创建备份，文件名碰撞且后面的 PUT 覆盖前面的备份。cleanupBackups 函数按模式（lite/full）计算备份数并删除旧的，如果两者共享目录可能删除错误设备的备份。
- **影响**: 使用相同手机型号的多设备用户的低概率数据丢失场景。一个设备的备份可能覆盖另一个的，或清理可能删除错误设备的备份。
- **建议修复**: 向文件名附加随机后缀或设备特定 UUID，或将 Android Settings.Secure.ANDROID_ID 作为设备名称的一部分以确保唯一性。

### 14. MIGRATION_22_23 在源代码中在 MIGRATION_23_24 之前运行但在注册顺序中正确

- **领域**: Room Migration
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/local/AppDatabase.kt`
- **行号**: 287-328
- **期望行为**: 迁移源代码应顺序排列以提高可读性，即使注册顺序决定执行。
- **实际行为**: MIGRATION_23_24 定义在第 287 行（在第 302 行的 MIGRATION_22_23 之前）。这仅是代码可读性问题。第 338-358 行的 addMigrations() 调用正确按顺序列出：MIGRATION_22_23、MIGRATION_23_24。
- **影响**: 无运行时影响。Room 使用迁移注册顺序而非源代码顺序。然而，反转的源顺序使代码更难阅读和维护。
- **建议修复**: 重新排列源代码使 MIGRATION_22_23 出现在 MIGRATION_23_24 之前。
