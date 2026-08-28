# 预算管理优化与 AI 增强 PRD

> 日期：2026-07-06  
> 状态：待实施  
> 背景：当前预算管理已经支持按月总预算/分类预算、进度展示、编辑删除和历史均值推荐，但整体体验仍偏“金额清单 + 进度条”，还不能很好地帮助用户判断消费节奏、解释超支原因和规划下月预算。

## Problem Statement

用户想通过预算管理控制本月支出，但当前功能给出的信息太少：只知道预算用了多少、是否达到固定阈值，却不知道这个进度相对本月时间是否正常、剩余每天还能花多少、哪些分类正在拖累预算、为什么会超支、下个月应该如何调整。

预算功能如果只做 CRUD 和百分比展示，用户仍然需要自己分析账单、推断风险和制定计划。这个过程麻烦，导致预算功能存在感低，也不容易形成持续使用习惯。

## Solution

将预算管理从“预算记录器”升级为“本月消费节奏面板”。

第一阶段以本地规则为主，增强预算页和统计页入口：

- 展示本月已用、剩余、剩余天数、日均可用金额。
- 引入时间进度判断，区分“花得快/正常/还有余量”。
- 按风险排序分类预算，优先展示超支和节奏偏快的分类。
- 对未设置预算但支出较高的分类给出本地建议。
- 统计页预算入口显示更有用的摘要，例如“剩 ¥1200 · 每天 ¥60”或“餐饮超支 ¥80”。

第二阶段加入本地推荐：

- 基于最近 3-6 个月支出生成预算建议。
- 区分保守、正常、宽松方案。
- 结合周期账单预留固定支出。
- 支持复制上月预算到本月/下月。

第三阶段加入 AI 增强，但 AI 不默认接管预算功能：

- 用户主动点击 AI 按钮才调用模型。
- AI 只基于本地聚合数据做解释和建议。
- AI 返回预算草稿，必须经用户确认后才写入预算表。
- 无 API Key、网络失败或 AI 关闭时，预算功能仍完整可用。

## User Stories

1. As a budget user, I want to see how much of my monthly budget is used, so that I can understand my current spending status.
2. As a budget user, I want to see how much budget remains, so that I know the actual amount I can still spend.
3. As a budget user, I want to see how many days remain in the month, so that I can plan the rest of the month.
4. As a budget user, I want to see the average amount I can spend per remaining day, so that I have a concrete daily reference.
5. As a budget user, I want the app to compare spending progress with calendar progress, so that I know whether I am spending too fast.
6. As a budget user, I want budget status to distinguish normal, warning, and exceeded states, so that risk is easy to scan.
7. As a budget user, I want category budgets sorted by risk, so that the most important problems appear first.
8. As a budget user, I want to see which category is causing the biggest budget pressure, so that I can act on the right area.
9. As a budget user, I want category cards to show used amount, remaining amount, percent used, and spending pace, so that each card is actionable.
10. As a budget user, I want the total budget summary to remain visible above the list, so that I always know the overall month status.
11. As a budget user, I want the statistics page budget entry to show a useful short summary, so that I can understand budget status without opening the budget page.
12. As a budget user, I want the budget page to work without AI configuration, so that budgeting remains reliable offline.
13. As a budget user, I want local rules to produce budget recommendations from history, so that I can create budgets without manual calculation.
14. As a budget user, I want to compare suggested budgets against recent spending history, so that I understand why a recommendation exists.
15. As a budget user, I want conservative, normal, and loose budget suggestions, so that I can pick a plan matching my intent.
16. As a budget user, I want recurring bills to be considered in budget planning, so that fixed expenses do not surprise me later.
17. As a budget user, I want to copy last month budgets into this month or next month, so that repeated monthly setup is fast.
18. As a budget user, I want the app to suggest budgets for high-spend categories that do not have budgets, so that missing categories are easy to catch.
19. As a budget user, I want AI to explain why I am over budget, so that I can understand the real drivers without manually reading every bill.
20. As a budget user, I want AI to summarize my current budget situation in plain language, so that the page feels easier to understand.
21. As a budget user, I want AI to generate a budget draft, so that I can quickly start from a reasonable plan.
22. As a budget user, I want to review AI budget drafts before applying them, so that AI never changes my budget unexpectedly.
23. As a budget user, I want AI suggestions to cite the local facts they used, so that I can trust and verify the recommendation.
24. As a budget user, I want AI failure to degrade gracefully, so that budget management does not break when the model is unavailable.
25. As a privacy-conscious user, I want AI to receive only summarized budget data and a small number of relevant bill examples, so that raw transaction exposure is minimized.

## Implementation Decisions

- Local budget rules are the source of truth. AI is optional and never required for budget calculation.
- AI is exposed as explicit actions, such as “AI 分析预算”, “解释超支”, and “生成预算草稿”.
- AI must not directly write budgets. It returns suggestions or drafts; the user confirms before persistence.
- Existing budget storage remains monthly and expense-focused. Avoid implementing envelope budgeting or complex cross-period allocation in this iteration.
- Extend the budget calculation model to include calendar progress, remaining days, daily remaining allowance, pace ratio, and risk reason.
- Budget risk should consider both percent used and time progress. Example: spending 70% of budget on day 5 should be riskier than spending 70% near month end.
- Keep total budget and category budget independent. Category budgets do not need to sum to total budget.
- Preserve current support for current book and all-books scope behavior.
- AI payloads should use aggregated local data: budget progress, monthly totals, category summaries, recent history, recurring bill summaries, and a short list of notable transactions.
- AI response should be parsed as structured JSON with fields for summary, risk level, category insights, daily suggestion, and budget drafts.
- If JSON parsing fails, show a safe fallback message rather than applying partial model output.
- Budget recommendations should be available locally before AI is added.
- The first implementation should focus on improving the existing budget page rather than creating a separate AI chat experience.

## Testing Decisions

- Test the calculation layer first, because it is the durable behavior underneath both UI and AI.
- Add unit coverage around budget progress, time progress, remaining days, daily allowance, risk status, and pace-based warning logic.
- Add unit coverage for history-based suggestions, including sparse months, zero-spend months, over-budget months, and rounding behavior.
- Add tests proving refunds/excluded bills remain consistent with existing budget spend behavior.
- Add tests for AI payload construction that verify only summarized data is included.
- Add tests for AI response parsing, including valid JSON, malformed JSON, missing fields, and unsafe drafts.
- UI verification should cover empty state, normal budget, warning budget, exceeded budget, no AI key, AI loading, AI success, and AI failure.
- Prior art exists in the repo around logic service tests, such as credit card cycle and recurring bill detector tests. Budget logic should follow that style.

## Out of Scope

- No AI automatic budget mutation.
- No always-on AI analysis.
- No complex envelope budgeting.
- No daily budget table that stores per-day allocations.
- No multi-currency budget conversion beyond the current budget currency behavior.
- No investment or income budgeting.
- No notification system in the first pass.
- No full conversational budget agent in the first pass.
- No server-side budget AI route unless the app-side AI integration proves insufficient.

## Phased Delivery Plan

### Phase 1: Local Budget Intelligence

- Add time progress and remaining-day calculations.
- Add daily remaining allowance.
- Add pace-based risk state and reason.
- Sort budget list by risk.
- Improve total budget summary.
- Improve statistics page budget entry text.

### Phase 2: Local Budget Recommendations

- Upgrade historical recommendation from a single 3-month average to multiple plan options.
- Add high-spend unbudgeted category suggestions.
- Add copy previous month budget action.
- Include recurring bill reservation in recommendations where available.

### Phase 3: AI Budget Assistant

- Build budget AI payload from local aggregates.
- Add structured prompt and result parser.
- Add budget page AI actions.
- Display AI analysis and budget drafts in a bottom sheet.
- Require explicit user confirmation before applying drafts.

## Acceptance Criteria

- Budget page is useful with AI disabled or no API key configured.
- User can tell at a glance whether this month is on track.
- User can see remaining budget and daily remaining allowance.
- Risky categories appear before healthy categories.
- Statistics page budget entry gives an actionable summary rather than only a percent.
- Local recommendation can generate a reasonable budget suggestion from history.
- AI actions are opt-in and visibly separate from local budget status.
- AI-generated drafts are never saved without user confirmation.
- AI failure does not block viewing, editing, or deleting budgets.

## Further Notes

The guiding principle is:

```text
Local rules = reliable budgeting system
AI = optional explanation and planning assistant
User confirmation = only path to write budget changes
```

This avoids turning a money-related feature into a model-dependent workflow while still giving AI a useful role: explaining patterns, generating drafts, and reducing the mental work of planning.
