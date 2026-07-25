# Dyno Command — Android app

A Kotlin Android port of the dyno handheld. The protocol layer is a direct,
behaviour-identical translation of the verified C# implementation (same CRC,
framing, packet factory and parser), so the wire format matches the original
firmware. Same two modes as the PC tool: a built-in **simulator** (test with no
hardware) and real **Bluetooth SPP** to the EDM.

## Sibling project — keep in sync
This app is a **parallel port of [DynoCommandPC](../DynoCommandPC)** (WPF/.NET8).
The two are behaviour-for-behaviour equivalents: **every control/protocol change
must be mirrored to both**. The vendor decompilation and conversion notes the
ports were written against live in the reference folder `../Dyno-Command+EDM`
(`Original/`, `Server/`, `DYNO_CONVERSION_NOTES.md`, `Documentation/`).

## Install the prebuilt APK
- File: **`dist/DynoCommand-debug.apk`** (debug-signed, installable directly).
- Copy to the phone and tap it (enable "install from unknown sources" if asked),
  or via ADB: `adb install -r dist/DynoCommand-debug.apk`.
- Min Android **8.0 (API 26)**.

## Using it
1. Launch **Dyno Command**.
2. **Simulator** is selected by default — tap **Connect** and you'll see the
   speed read-out respond when you choose **Speed** mode and nudge the target.
   No hardware needed.
3. For the real dyno: pair the EDM in Android Bluetooth settings first, choose
   **Bluetooth (EDM)**, grant the Bluetooth permission when prompted, pick the
   EDM from the device list (or set its MAC in Settings), then **Connect**.
4. **Settings** configures the EDM MAC, dyno type (4WD / master-is-controller /
   is-a-4WD-bed) and speed settings (units, control, roller diameter, gear ratio,
   max speeds, nudge increments) — persisted via SharedPreferences (the Android
   equivalent of PdaSettings.xml).

## Build it yourself
Requires JDK 17+ and the Android SDK (platform 34, build-tools 34.0.0).
```
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
```
`local.properties` must point at the SDK (`sdk.dir=...`, forward slashes on
Windows). Or just open this folder in Android Studio and Run.

## Layout
```
app/src/main/java/com/dynocommand/app/
  protocol/   CanPacket, Crc16, PacketFramer (wrap + FrameParser),
              PacketFactory, PacketParser, State (Command/Dyno), Enums
              — ported verbatim from the verified PC Protocol/ layer
  transport/  Transport, BluetoothSppTransport (createRfcommSocketToServiceRecord,
              UUID 00001101-…), SimulatorTransport (in-process EDM)
  DynoController.kt   TX loop (~20 Hz) + RX framer/parser
  settings/   AppSettings + SettingsStore (SharedPreferences)
  ui/         MainActivity, SettingsActivity
```

## Notes / next steps
- The simulator interprets the knob as a road-speed target (calibrated for
  kph/mph) so the loop is visible; it proves the protocol, not dyno physics.
- Live speed is shown in km/h + roller revs/s; full kph/mph/rpm display scaling
  and engine-vs-road is a parity item.
- **Safety:** before using against a real dyno, add a fail-safe on link loss
  (revert to no-load), matching the original firmware's watchdog behaviour.
- The release build is unsigned; `assembleDebug` is debug-signed for testing.
</content>
