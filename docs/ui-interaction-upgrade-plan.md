# FlipAccounting AI UI/Interaction Upgrade Plan

This document is the source of truth for the staged UI and interaction upgrade.
Use it when continuing work after context compaction, when handing prompts to another AI, or when reviewing staged changes.

## Project Context

- Project path: `E:\FlipAccounting-AI`
- UI stack: native Android View/XML with MaterialComponents.
- Do not migrate to Compose.
- Do not rewrite architecture.
- Keep business logic intact.
- Prefer incremental, buildable stages.

Important areas:

- Main shell: `app/src/main/java/tao/test/flipaccounting/MainActivity.kt`
- Main layout: `app/src/main/res/layout/activity_main.xml`
- Home: `app/src/main/res/layout/fragment_home.xml`
- Stats: `app/src/main/res/layout/fragment_stats.xml`
- Assets: `app/src/main/res/layout/fragment_assets.xml`
- Profile/settings: `app/src/main/res/layout/fragment_profile.xml`
- Floating accounting: `app/src/main/java/tao/test/flipaccounting/OverlayManager.kt`
- Floating layout: `app/src/main/res/layout/layout_floating_window.xml`
- AI chat: `app/src/main/java/tao/test/flipaccounting/ChatActivity.kt`
- AI chat layout: `app/src/main/res/layout/activity_chat.xml`
- Dialogs/pickers: `app/src/main/java/tao/test/flipaccounting/ui/dialog/OverlayDialogs.kt`
- Theme resources:
  - `app/src/main/res/values/colors.xml`
  - `app/src/main/res/values/dimens.xml`
  - `app/src/main/res/values/themes.xml`

## Collaboration Workflow

Use this loop for every stage:

1. Codex gives a stage prompt.
2. User gives that prompt to another AI.
3. Other AI modifies the project.
4. User returns to Codex with: `第 X 阶段改完了，帮我审查。`
5. Codex reviews the diff, build status, risks, and validation needs.
6. If accepted, Codex gives the next stage prompt.

Rules for the implementer AI:

- Do one stage at a time.
- Modify only allowed files for that stage.
- Never delete user changes.
- Avoid broad refactors.
- Run `.\gradlew.bat :app:assembleDebug` after each stage.
- Report changed files, build result, and manual validation steps.

Rules for Codex review:

- Review in code-review stance.
- Findings first, ordered by severity.
- Check for stage scope violations.
- Check build and runtime risk.
- Check visual regressions and interaction regressions.
- Give exact acceptance or required fix prompt.

## Global Design Direction

The app should feel like a modern personal finance tool with an AI assistant:

- Clean, light, readable surfaces.
- Strong information hierarchy for amounts, categories, assets, and AI results.
- Comfortable motion for appearance, disappearance, switching, loading, and confirmation.
- Consistent color, spacing, typography, corner radius, and elevation.
- Financial workflows should stay efficient and scan-friendly.
- Avoid decorative visual noise.
- Prefer subtle, useful animation over flashy animation.

Motion rule:

- Prefer animating `alpha`, `translationX`, `translationY`, `scaleX`, and `scaleY`.
- Avoid layout-param animation unless absolutely necessary.
- Cancel old animations before starting new ones.
- Cancel infinite animations during lifecycle cleanup.
- Use shared motion constants from `UiMotion.kt` once available.

## Current Known Stage Status

Update this section as stages complete.

- Stage 1: Global UI foundation. Implemented and reviewed with one requested fix: avoid binding `android:colorBackground` globally to gray `page_background`. After fix, it should be accepted.
- Stage 2: Main navigation/FAB/Tab interaction. Prompt has been provided. Review pending.
- Stage 3+: Not started.

Note: The repository may contain unrelated dirty files from earlier work. Review each stage by diffing the allowed files only.

## Stage 1 - Global UI Foundation

Goal:

Create shared design tokens and motion helpers without changing page behavior.

Allowed files:

- `app/src/main/res/values/colors.xml`
- `app/src/main/res/values/dimens.xml`
- `app/src/main/res/values/themes.xml`
- `app/src/main/java/tao/test/flipaccounting/ui/common/UiMotion.kt`
- Optional new drawable resources only if they do not affect existing pages yet.

Forbidden:

- Activity/Fragment logic.
- Page layouts.
- Adapters.
- Data layer.
- Gradle.
- Manifest.

Implementation requirements:

- Add semantic color resources:
  - `page_background`
  - `surface_card`
  - `surface_elevated`
  - `text_primary`
  - `text_secondary`
  - `text_tertiary`
  - `divider_soft`
  - `brand_primary`
  - `brand_secondary`
  - `expense_color`
  - `income_color`
  - `warning_color`
  - `success_color`
  - `danger_color`
  - `ai_accent`
- Add shared dimensions:
  - `space_4`, `space_8`, `space_12`, `space_16`, `space_20`, `space_24`
  - `radius_8`, `radius_12`, `radius_16`, `radius_20`
  - `toolbar_height`
  - `bottom_nav_height`
  - `card_padding`
  - `list_item_min_height`
  - `button_height`
  - `touch_target_min`
- Add `UiMotion.kt` with:
  - `FAST = 150L`
  - `NORMAL = 220L`
  - `SLOW = 300L`
  - `STANDARD_EASING = PathInterpolator(0.2f, 0f, 0f, 1f)`
  - `EXIT_EASING = PathInterpolator(0.4f, 0f, 1f, 1f)`
  - `EMPHASISED_EASING = PathInterpolator(0.2f, 0f, 0f, 1f)`
  - Optional small View helpers, not wired into pages yet.

Review checklist:

- Build passes.
- No business logic modified.
- No page layout modified.
- Existing colors not deleted.
- New theme bindings do not cause broad visual changes.
- `android:colorBackground` should remain white in Stage 1 unless page-specific changes are planned.

Acceptance validation:

- `.\gradlew.bat :app:assembleDebug`
- Open app and confirm:
  - Bottom navigation remains white.
  - Status bar remains white with dark icons where expected.
  - Home/stats/assets/profile backgrounds do not unexpectedly change.
  - No resource resolution errors.

## Stage 2 - Main Navigation, FAB, and Tab Motion

Goal:

Make `MainActivity` bottom navigation, center FAB, and Tab switching feel smoother and consistent.

Allowed files:

- `app/src/main/java/tao/test/flipaccounting/MainActivity.kt`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/values/dimens.xml`
- `app/src/main/res/values/colors.xml`
- `app/src/main/java/tao/test/flipaccounting/ui/common/UiMotion.kt`
- Optional new drawable/color selector resources for bottom navigation/FAB.

Forbidden:

- `fragment_home.xml`
- `fragment_stats.xml`
- `fragment_assets.xml`
- `fragment_profile.xml`
- Adapters.
- Data layer.
- Manifest.
- Gradle.
- `OverlayManager.kt`

Implementation requirements:

- Preserve current four Tab order:
  - Home/bills
  - Stats
  - Assets
  - Profile/settings
- Preserve `SwipeFrameLayout`.
- Preserve existing Fragment management behavior.
- Use `UiMotion` durations and interpolators.
- Animate Tab switching using only `alpha`, `translationX`, and `scale`.
- Keep drag-to-switch feeling responsive.
- Add animation cancellation/debounce for rapid Tab clicks.
- Clean up `settleAnimator` during Activity destroy.
- FAB:
  - Add press feedback.
  - Animate show/hide with `alpha + scale`.
  - Use shared color resources, not new hardcoded colors.
  - Do not obscure navigation or list endings.
- Bottom navigation:
  - Keep background white or `surface_card`.
  - Selected color: `brand_primary`.
  - Unselected color: weak text color.
  - Touch target remains at least 48dp.

Review checklist:

- No forbidden files changed.
- Build passes.
- Fast repeated Tab taps cannot corrupt state.
- Swipe and tap transitions use consistent motion.
- FAB click still opens existing add-bill flow.
- FAB visibility rules still match existing behavior.
- No new hardcoded random colors unless there is a clear reason.

Manual validation:

- Launch app.
- Tap each bottom Tab.
- Swipe left/right between Tabs.
- Rapidly tap Tabs 10 times.
- Tap FAB on Home and verify add flow opens.
- Rotate or background/foreground app if relevant.
- Confirm bottom navigation remains visually stable.

Prompt for implementer AI:

```text
You are an Android native View/XML UI interaction engineer.

Project path: E:\FlipAccounting-AI

Now execute Stage 2: improve MainActivity bottom navigation, center FAB, and Tab switching motion.

Allowed files:
- app/src/main/java/tao/test/flipaccounting/MainActivity.kt
- app/src/main/res/layout/activity_main.xml
- app/src/main/res/values/dimens.xml
- app/src/main/res/values/colors.xml
- app/src/main/java/tao/test/flipaccounting/ui/common/UiMotion.kt
- Optional new drawable/color selector resources for bottom navigation/FAB only.

Forbidden:
- Fragment page layouts
- Adapters
- Data layer
- Manifest
- Gradle
- OverlayManager.kt

Read first:
- app/src/main/java/tao/test/flipaccounting/MainActivity.kt
- app/src/main/res/layout/activity_main.xml
- app/src/main/java/tao/test/flipaccounting/ui/common/UiMotion.kt
- app/src/main/res/menu/bottom_nav_menu.xml

Requirements:
1. Preserve the existing 4 Tab order and existing Fragment management.
2. Preserve SwipeFrameLayout and horizontal swipe switching.
3. Use UiMotion constants/interpolators for Tab switch and FAB animation.
4. Use alpha/translation/scale only for animation.
5. Add animation cancellation/debounce for rapid Tab taps.
6. Clean up settleAnimator on destroy.
7. FAB click should keep opening the existing add-bill flow.
8. FAB show/hide should use alpha + scale and press feedback.
9. Bottom nav background should remain white/surface_card.
10. Selected nav color should use brand_primary; unselected should use a weak text color.
11. Run .\gradlew.bat :app:assembleDebug after changes.

Report:
- Changed files
- What changed in each file
- Build result
- Manual validation steps
- Any remaining risk
```

## Stage 3 - Home Page Experience

Goal:

Make Home feel like the strongest, most polished finance dashboard while preserving current layout direction.

Allowed likely files:

- `app/src/main/res/layout/fragment_home.xml`
- `app/src/main/java/tao/test/flipaccounting/ui/main/home/HomeFragment.kt`
- `app/src/main/java/tao/test/flipaccounting/ui/main/home/HomeAdapter.kt`
- `app/src/main/java/tao/test/flipaccounting/ui/main/home/HomeBannerController.kt`
- `app/src/main/java/tao/test/flipaccounting/ui/main/home/HomeUiListController.kt`
- Related home item layouts only when necessary:
  - `item_home_transaction.xml`
  - `item_bill_header.xml`
  - `item_home_chart.xml`

Implementation goals:

- Keep large visual header direction.
- Improve top bar clarity.
- Smooth month/date amount changes via crossfade.
- Add controlled first-load list animation for visible items only.
- Add item press feedback.
- Improve empty state with clearer action.
- Preserve performance of RecyclerView.

Review checklist:

- No data query logic changed.
- No list rebind animation loops.
- No janky animations in `onBindViewHolder`.
- Empty state still appears correctly.
- SwipeRefresh and AppBar still cooperate.
- FAB remains usable and not obstructive.

Manual validation:

- Open Home with data.
- Scroll fast.
- Change month.
- Switch book.
- Search.
- Empty-book state.
- Add bill flow from FAB.

## Stage 4 - Floating Accounting and Accounting Form Polish

Goal:

Extend the already improved floating window motion into a more cohesive accounting form experience.

Likely files:

- `OverlayManager.kt`
- `layout_floating_window.xml`
- `AccountingFormController.kt`
- `layout_amount_keypad.xml`
- Picker/dialog files used from the accounting form.

Implementation goals:

- Keep current smooth overlay enter/exit direction.
- Unify row press feedback.
- Improve amount input focus/validation motion.
- Smooth single/multi bill transitions.
- Improve save success/error feedback.
- Improve keyboard/keypad ergonomics.

Review checklist:

- Overlay permission behavior unchanged.
- Existing AI prefill behavior unchanged.
- Multi-bill behavior unchanged.
- No WindowManager leaks.
- Animations cancel on removal.

Manual validation:

- Show overlay.
- Close via outside/back.
- Enter amount.
- Switch expense/income/transfer/refund.
- Pick category/account/date/book.
- Save single bill.
- Multi-bill flow.
- Screenshot/image recognition flow.

## Stage 5 - AI Chat Experience

Goal:

Make AI chat feel like a core product surface, not a utility page.

Likely files:

- `activity_chat.xml`
- `ChatActivity.kt`
- `ChatAdapters.kt`
- `ChatVoiceController.kt`
- `ChatVoiceInputController.kt`
- `ChatMessageMenuController.kt`
- Chat item layouts:
  - `item_chat_ai.xml`
  - `item_chat_user.xml`
  - `item_chat_bill.xml`
  - `item_chat_bill_card.xml`

Implementation goals:

- Improve toolbar hierarchy.
- Improve message bubble spacing and readability.
- Add typing/streaming visual affordance if possible without logic risk.
- Improve input area ergonomics.
- Animate voice recording overlay enter/exit.
- Unify long-press message menu style.
- Make bill result cards more actionable.

Review checklist:

- Sending text/voice/image unchanged.
- Message persistence unchanged.
- RecyclerView scroll-to-bottom behavior intact.
- Long-press menus still work.
- No keyboard overlap.

Manual validation:

- Send text.
- Use voice input.
- Attach image if supported.
- Long press message.
- Receive AI response.
- Save/modify bill card.
- Open session drawer.

## Stage 6 - Stats Page

Goal:

Make statistics easier to scan and more interactive.

Likely files:

- `fragment_stats.xml`
- `StatsFragment.kt`
- `StatsPopups.kt`
- `card_stats_overview.xml`
- `card_stats_category_report.xml`
- `card_stats_daily_chart.xml`
- `card_stats_daily_report.xml`
- `item_stats_category.xml`
- `item_stats_day.xml`

Implementation goals:

- Convert month/year switcher into a stronger segmented control.
- Improve filter active state.
- Add smooth month/year/date transition.
- Improve empty state.
- Improve chart/card hierarchy.
- Make category ranking easier to scan.

Review checklist:

- Stats query logic unchanged.
- Chart rendering still correct.
- Month/year mode state persists.
- Filter sheet still works.

Manual validation:

- Month mode.
- Year mode.
- Previous/next date.
- Filter.
- Empty state.
- Category report.
- Daily report.

## Stage 7 - Assets Page

Goal:

Make asset overview clearer and more professional.

Likely files:

- `fragment_assets.xml`
- `AssetsFragment.kt`
- `AssetDetailActivity.kt`
- `AssetStatsActivity.kt`
- Asset item layouts.

Implementation goals:

- Strengthen net asset summary.
- Improve asset/debt hierarchy.
- Show exchange-rate status as chip/status row.
- Add item press feedback.
- Unify asset detail transaction list with Home style.

Review checklist:

- Asset calculations unchanged.
- Exchange-rate refresh behavior unchanged.
- Add/edit/delete asset unchanged.
- NestedScroll performance remains acceptable.

Manual validation:

- Asset overview.
- Add asset.
- Edit asset.
- Asset detail.
- Asset stats.
- Exchange-rate refresh.

## Stage 8 - Profile, Settings, and Configuration Pages

Goal:

Make settings easy to understand and safer to operate.

Likely files:

- `fragment_profile.xml`
- `ProfileFragment.kt`
- `SettingsActivity.kt`
- `AiConfigActivity.kt`
- `AiFeatureSettingsActivity.kt`
- `BillDisplaySettingsActivity.kt`
- `SensitivityActivity.kt`
- Backup/storage/settings layouts.

Implementation goals:

- Create consistent settings item pattern:
  - icon
  - title
  - summary
  - trailing value/switch/chevron
- Unify permission reminder cards.
- Make AI configuration less like a debug form.
- Standardize dangerous operations.
- Improve storage/backup pages.

Review checklist:

- Settings persistence unchanged.
- Permission request paths unchanged.
- Backup/restore unchanged.
- AI model config behavior unchanged.

Manual validation:

- Open profile.
- Toggle settings.
- Overlay permission prompt.
- AI config test connection.
- Backup/restore.
- Storage cleanup.
- Sensitivity pages.

## Stage 9 - Dialogs, BottomSheets, Pickers

Goal:

Make all transient surfaces feel like one system.

Likely files:

- `OverlayDialogs.kt`
- `RuleDialogHelper.kt`
- `ElegantDatePickerSheet.kt`
- `YearMonthPickerDialog.kt`
- `dialog_*.xml`
- `bottom_sheet_*.xml`
- `layout_elegant_date_picker.xml`
- `layout_year_month_picker.xml`

Implementation goals:

- Centralize dialog enter/exit behavior.
- Unify center dialogs.
- Unify bottom sheets.
- Unify picker selected states.
- Replace excessive Toasts with stronger in-context feedback where safe.
- Make dangerous confirmations visually distinct.

Review checklist:

- Dialog token/window type handling preserved.
- Overlay/page dialog paths both work.
- Pickers return same values.
- No leaked windows.

Manual validation:

- Category picker.
- Asset picker.
- Book picker.
- Date/time picker.
- Year/month picker.
- Delete confirmation.
- Rule edit dialog.
- Exchange-rate dialog.

## Stage 10 - Final QA and Regression

Goal:

Verify the app is cohesive and stable.

Commands:

```powershell
.\gradlew.bat :app:assembleDebug
```

Manual full pass:

- Home:
  - Launch.
  - Scroll.
  - Switch month.
  - Switch book.
  - Search.
  - Empty state.
  - FAB add bill.
- Stats:
  - Month/year.
  - Date navigation.
  - Filter.
  - Empty/non-empty.
- Assets:
  - Overview.
  - Add/edit.
  - Detail.
  - Exchange rate.
- Profile/settings:
  - Permission reminders.
  - AI config.
  - Backup.
  - Storage cleanup.
  - Sensitivity.
- AI chat:
  - Text.
  - Voice.
  - Image.
  - Long press.
  - Bill card action.
- Floating window:
  - Open.
  - Close.
  - Save.
  - Multi bill.
  - Screenshot.
  - Image.
  - Keyboard.

Performance checks:

- No repeated expensive animation in RecyclerView bind.
- No infinite animator left running after leaving page.
- No visible frame drops during Tab switch.
- No layout jumping when keyboard appears.
- No clipped text on common screen sizes.

## Review Response Template

When user returns with a completed stage, use this structure:

1. Findings first:
   - Use code-comment directives for concrete file/line issues.
   - Order by severity.
2. Scope check:
   - Allowed files changed?
   - Forbidden files touched?
3. Build:
   - Report build result or ask user/AI to run it.
4. Manual validation:
   - Give exact steps.
5. Decision:
   - Accepted.
   - Accepted with follow-up.
   - Needs fix before next stage.
6. Next prompt:
   - Only provide the next stage prompt after current stage is accepted.

