# Skill: Android build and deploy

## Project location
`D:\Projects\Dyno-Command\Android\` — Kotlin, minSdk 26, targetSdk 34.

## Build
```powershell
D:\Projects\Dyno-Command\Android\gradlew.bat -p D:\Projects\Dyno-Command\Android assembleDebug
```
APK: `app\build\outputs\apk\debug\app-debug.apk`.

## Deploy over adb (this is how we iterate)
adb path: `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`.

Known devices (both Android 13 / API 33):
| Device | Serial |
|--------|--------|
| Motorola moto g82 5G | `ZY22FX9FJZ` |
| Samsung Galaxy Tab Active3 (SM-T575) | `R52RA0B0MCE` |

Install + launch on whichever single device is connected (robust to serial changes):
```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$dev = (& $adb devices) | Select-String "\tdevice$"
$serial = ($dev[0] -split "\s+")[0]
& $adb -s $serial install -r "D:\Projects\Dyno-Command\Android\app\build\outputs\apk\debug\app-debug.apk"
& $adb -s $serial shell am start -n com.dynocommand.app/.ui.MainActivity | Out-Null
```
Screenshot (avoid piping binary through PowerShell — save on device then pull):
```powershell
& $adb -s $serial shell screencap -p /sdcard/s.png
& $adb -s $serial pull /sdcard/s.png <local.png>; & $adb -s $serial shell rm /sdcard/s.png
```
Devices drop off adb often (rugged tablet pogo/USB is flaky) — re-check `adb devices` and re-run.
**Sideloading the APK by copying it to the device fails silently** (Play Protect / "install unknown
apps" not allowed); adb bypasses both. That was NOT a build problem.

## Key source files
| File | Purpose |
|------|---------|
| `DynoLink.kt` | **Process-wide** connection owner + auto-reconnect watchdog (survives Activity death) |
| `service/DynoConnectionService.kt` | Foreground service: keeps process alive (screen-off/app-switch) + wake lock + notification |
| `DynoController.kt` | TX loop, RX framer, response queue, governor, comms tally, packet inventory |
| `protocol/PacketParser.kt` | RX decode + handshake responders (config/FW/BT-addr) |
| `protocol/PacketFactory.kt` | TX packet builder (`effectiveRampRate`) |
| `protocol/State.kt` | DynoState + CommandState |
| `transport/BluetoothSppTransport.kt` | BT Classic SPP; insecure connect; `onClosed` drop callback |
| `ui/MainActivity.kt` | UI; hands the connection to the service |
| `res/layout/activity_main.xml` | main screen (SET \| ACTUAL read-out, ramp, balance, inspector) |

## Bluetooth connection (Android)
The EDM does **NOT** bond/pair — use `createInsecureRfcommSocketToServiceRecord` (insecure-first,
secure fallback in `BluetoothSppTransport`). SPP UUID `00001101-0000-1000-8000-00805F9B34FB`.

**MAC format gotcha:** settings store the bare hex `0012F304962C` (Windows/Winsock form), but
Android's `getRemoteDevice()` needs colons `00:12:F3:04:96:2C` or it throws "Bad MAC" **before** it
ever hits the radio (looks like a range failure but isn't). `DynoLink.normalizeMac()` inserts them.
An `IP:port` in the EDM-address field would be the hook if a TCP transport is ever added.

Manifest permissions: `BLUETOOTH_CONNECT` (API 31+); `BLUETOOTH`/`BLUETOOTH_ADMIN` (≤API 30);
`WAKE_LOCK`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`.

## Background keep-alive (stays connected screen-off / app-switch)
`Connect` starts `DynoConnectionService` (foreground, `connectedDevice` type) which owns a partial
wake lock + ongoing notification and keeps the process alive; `DynoLink` (a singleton, not the
Activity) owns the `DynoController` and a watchdog that auto-reconnects on drop (1s→15s backoff).
`Disconnect` stops the service. The Activity no longer disconnects on `onDestroy`.

## Parity with the Windows app (keep in sync)
`PacketParser`, `PacketFactory` (+ digital-inputs bits), safety governor (±10 km/h leash, no-zero
above 5 km/h), brake interlock, ramp run sequences, shootout lockout + `effectiveRampRate`, config
push/query + FW + BT-addr responders, speed scaling `raw*0.002/GearRatio*circ*3.6`. The Android UI
is information-parity in mobile idiom (single scrolling screen), not a WPF clone.
