# TapTapAccounting Release Regression Checklist

## Goal

Use this checklist before shipping a `release` build to verify that release-only steps such as R8 minification, resource shrinking, manifest merging, and native packaging do not remove or break user-facing functionality.

Recommended comparison method:

1. Install a fresh `debug` build and verify the same scenario.
2. Uninstall it or clear app data.
3. Install the target `release` build.
4. Repeat the exact same scenario and compare behavior.

## Test Scope

This checklist focuses on the project areas most sensitive to release/debug differences:

- Gson reflection and JSON-backed presets
- Room-backed backup/restore flows
- Overlay service and boot/service entry points
- Shizuku integration and reflective process launch
- Local ASR / JNI / native model loading
- AI chat, OCR, and image import flows
- Dynamic resource lookup paths

## Environment Setup

Before starting:

- Prepare one Android 13+ device if image/media permission flows matter.
- Prepare one device with Shizuku installed if white-list mode is used.
- Prepare a network connection for AI / model download cases.
- Keep one sample backup file, one sample CSV, one sample receipt image, and one short voice sample.
- Record the exact APK being tested:
  - `app/build/outputs/apk/debug/app-debug.apk`
  - `app/build/outputs/apk/release/app-release.apk`

## Smoke Checks

### 1. App Launch

- Precondition: fresh install.
- Steps: open app from launcher.
- Expected:
  - App launches without crash.
  - Main tabs and primary screens render normally.
  - No missing strings, blank cards, or obviously broken layouts.

### 2. Main Navigation

- Steps:
  - Open Home, Assets, Stats, Profile.
  - Open Settings, Currency Manager, Backup, AI Config, Chat.
- Expected:
  - Every page opens successfully.
  - No activity launch crash.
  - Back navigation behaves normally.

## Core Data Checks

### 3. Built-in Categories and Preset Assets

- Risk: release minification breaking Gson field names for preset JSON models.
- Steps:
  - Open add-category / built-in category related UI.
  - Open add-asset type picker.
  - Verify preset names and icons load.
- Expected:
  - Built-in categories are present.
  - Preset asset icons and names are not empty.
  - No blank list caused by JSON parse failure.

### 4. Basic CRUD

- Steps:
  - Create asset.
  - Create expense bill.
  - Create income bill.
  - Edit one bill.
  - Delete one bill.
- Expected:
  - Data persists after process restart.
  - Home/Stats/Assets pages refresh correctly.

## AI and OCR Checks

### 5. AI Text Accounting

- Precondition: valid AI endpoint, model, and API key.
- Steps:
  - In Chat or overlay AI input, submit one single-bill sentence.
  - Submit one multi-bill sentence.
- Expected:
  - Response returns successfully.
  - Parsed bill fields appear in UI.
  - No Retrofit/Gson runtime crash in release.

### 6. AI Image / Receipt Flow

- Risk: release manifest, picker flow, image handoff, OCR assets, or AI parse differences.
- Steps:
  - Trigger image import from main chat entry.
  - Trigger image import from overlay entry.
  - Select a receipt image.
- Expected:
  - Picker opens.
  - Image returns to app successfully.
  - OCR/AI analysis runs.
  - Overlay reappears after picker cancel or completion.

### 7. AI Model / Config Screen

- Steps:
  - Open AI config page.
  - Load model list.
  - Save config.
- Expected:
  - Model fetch succeeds.
  - Saved configuration survives app restart.

## Voice and Native Checks

### 8. Cloud ASR

- Precondition: AI speech model configured.
- Steps:
  - Record a short voice message.
  - Wait for speech-to-text conversion.
- Expected:
  - Recording succeeds.
  - Text result returns or a user-facing error is shown.
  - No release-only converter crash.

### 9. Local ASR Model Download / Import

- Risk: release package missing JNI libs or Sherpa classes.
- Steps:
  - From Profile, download local ASR model or import local archive.
  - Initialize local ASR.
  - Run one speech-to-text attempt.
- Expected:
  - Model download/import completes.
  - No `UnsatisfiedLinkError`, `NoClassDefFoundError`, or init crash.
  - Local transcription can run.

### 10. Voice Streaming

- Steps:
  - Start live voice input.
  - Speak briefly.
  - Stop recording.
- Expected:
  - Streaming starts and stops cleanly.
  - Final transcript or fallback path works.

## Overlay and Service Checks

### 11. Overlay Permission and Manual Open

- Steps:
  - Grant overlay permission if needed.
  - Open overlay manually from app.
  - Close it by cancel, outside touch, and save.
- Expected:
  - Overlay shows without crash.
  - Buttons and form inputs work.
  - Cancel/save behavior is correct.

### 12. Foreground Service Start / Stop

- Steps:
  - Enable flip service from main/profile.
  - Disable it again.
- Expected:
  - Foreground notification appears.
  - Service can stop cleanly.
  - No Android 12+/14+ foreground-service crash.

### 13. Quick Settings Tile / Quick Start

- Steps:
  - Add quick settings tile.
  - Tap tile.
  - Launch quick-start activity if used.
- Expected:
  - Overlay opens through the service.
  - No component-not-found or manifest issue.

### 14. Boot Restore Path

- Steps:
  - Enable flip-related settings.
  - Reboot device or simulate package replaced / restart flow if practical.
- Expected:
  - Boot receiver path works as designed.
  - Service restart behavior matches debug expectation.

## Shizuku Checks

### 15. Shizuku Permission Flow

- Precondition: Shizuku installed and running.
- Steps:
  - Enable white-list related behavior.
  - Request Shizuku permission.
  - Open app whitelist page.
- Expected:
  - Permission request works.
  - Status checks behave normally.
  - No reflection failure from `ShizukuHelper`.

### 16. Foreground App / White-list Logic

- Steps:
  - Add one app to whitelist.
  - Trigger overlay logic in allowed and disallowed apps.
- Expected:
  - Allowed app can trigger overlay.
  - Disallowed app does not trigger overlay.
  - No release-only shell/process failure.

## Backup / Restore Checks

### 17. Full Backup Export

- Steps:
  - Export backup with assets, categories, bills, rules, chat, and settings selected.
  - Export another backup with AI core enabled and PIN protection.
- Expected:
  - Backup file is created.
  - No crash while serializing Room/Gson models.

### 18. Full Restore

- Steps:
  - Clear app data or use a clean install.
  - Restore from backup.
  - Restore from PIN-protected backup.
- Expected:
  - Restore dialog shows correct modules.
  - PIN-protected settings can be decrypted with correct PIN.
  - Restored assets, bills, categories, chat, settings all appear correctly.

### 19. CSV Export / Import

- Steps:
  - Export bills to CSV.
  - Import the same CSV into a chosen book.
- Expected:
  - Export file is generated.
  - Import parses records successfully.
  - Imported bills appear in the selected or fallback book.

## Media and File Checks

### 20. Chat Media / Avatar / Background Paths

- Steps:
  - Set chat background or avatar if the feature is used.
  - Back up and restore chat media.
- Expected:
  - Restored file paths are still valid.
  - Images render after restore.

### 21. uCrop / Image Editing Path

- Steps:
  - Trigger any path that opens `UCropActivity`.
- Expected:
  - Crop page opens and returns successfully.

## Dynamic Resource Checks

### 22. Dynamic IDs and System Resource Lookup

- Risk: release resource shrinking with `getIdentifier`.
- Steps:
  - Open Home page with grouped bill list headers.
  - Observe icon container and header summary rendering.
  - Trigger any toast path using system transient notification lookup.
- Expected:
  - Header summary text appears when expected.
  - Transaction icon container is visible.
  - No null-view crash caused by missing shrunk resources.

## Release Sign-off

Mark each item:

- PASS
- FAIL
- NOT TESTED

Release can be considered safe only if:

- All smoke checks pass.
- At least one AI text flow passes.
- At least one image/OCR flow passes.
- Overlay/service flow passes.
- Backup export + restore passes.
- If local ASR is a supported feature, one local ASR run passes.
- If Shizuku is a supported feature for your release audience, one Shizuku flow passes.

## Known Build-Level Protections Already Verified

These were already checked in the current codebase:

- Release APK builds successfully.
- Debug APK builds successfully.
- Key native libraries are packaged in release.
- `@raw/assets`, `@raw/category`, and `@raw/default_category` remain reachable in release.
- Sensitive classes used by reflection/Gson such as `BuiltInCategory` and `SiliconFlowApi` are preserved appropriately for release behavior.
