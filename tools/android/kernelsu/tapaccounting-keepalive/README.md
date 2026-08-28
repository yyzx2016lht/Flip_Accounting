# TapAccounting KeepAlive KernelSU Module

KernelSU/Magisk module to keep `com.taostudio.tapaccounting` alive on OPPO/ColorOS.

## What it does

1. Adds the app to Android device-idle whitelist and appops allowlists.
2. Polls every 10 seconds to detect if the app was force-stopped.
3. When killed: clears `stopped=true`, sends broadcast to `BootReceiver` to restart `OverlayService`.
4. Runs a background logcat monitor for real-time force-stop detection from `com.oplus.battery`.

## Install

Flash via KernelSU manager. Log at `/data/adb/tapaccounting-keepalive.log`.

## Config

Edit `/data/adb/modules/tapaccounting_keepalive/config.env`:

```
CHECK_INTERVAL=10        # seconds between checks
RESTART_ACTION=...       # broadcast action for restart
```

## Notes

- Does NOT disable `com.oplus.battery` or hide the process.
- Recovery latency: ~10 seconds (poll) or ~1-2 seconds (logcat catch).
- If broadcast fails, falls back to direct service start only.
