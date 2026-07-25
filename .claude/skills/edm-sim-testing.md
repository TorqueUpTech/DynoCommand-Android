# Skill: EDM Simulator — test a handheld without the real dyno

The **Sim build** of the Windows app (default `EdmSim=true`, see [windows-build.md](windows-build.md))
can act AS the dyno's EDM: a Bluetooth SPP server that replays a captured datalog to a connecting
handheld (the Android app, or a second Windows PC). Verifies the whole handheld path — framing, CRC,
parser, handshake responders, governor, UI — with no phone-less/EDM-less loop needed.

> **Location:** the simulator moved out of this tree into its own repo —
> `D:\Projects\Dyno\EDM-Simulator\` (github.com/TorqueUpTech/EDM-Simulator).
> Paths below are relative to `EDM-Simulator\DynoCommandPC\`.

## Files
- `Server/EdmReplaySource.cs` — parses a `dyno-capture-*.txt` log, extracts the **RX-direction**
  (dyno→handheld) frames + original inter-frame timing.
- `Server/EdmSimServer.cs` — native WinRT `RfcommServiceProvider` + `StreamSocketListener`
  (`PlainSocket` = no pairing), replay loop (re-wraps each frame with `PacketFramer`, CRC recomputed).
- `EdmServerWindow.xaml(.cs)` — the UI (Browse / Start / Stop, shows PC BT MAC + status).

## Procedure
1. Run the **Sim build** (`D:\Projects\Dyno\EDM-Simulator\dist\EDM-Sim\DynoCommandPC.exe`, title
   "— Sim build"). Click **EDM Sim…**.
2. **Browse** to a capture — use a `dyno-capture-*.txt` (has the RX frames incl. speed / shootout /
   config). **NOT** a `dash-log` CSV (different format → 0 frames). A known-good sample ships in the
   repo: `D:\Projects\Dyno\EDM-Simulator\dyno-capture-20260627-103431.txt`.
3. **Start server.** Status → "listening — waiting for handheld"; note the **Bluetooth address** shown.
4. On the handheld, set the EDM address to that PC MAC and **Connect** (Android normalizes the MAC).
5. Confirm: PC shows "handheld connected — replaying" + *frames sent* climbing; the handheld lights
   up with live speed/mode/shootout/config from the replay.

## Platform facts (hard-won — don't re-derive)
- **32feet.NET 4.x is useless as a Windows server** — `BluetoothRadio.Default` / `BluetoothListener`
  are netstandard stubs that throw `PlatformNotSupported`. Removed.
- **Native WinRT `RfcommServiceProvider` works UNPACKAGED** — no MSIX needed (confirmed). Requires
  the Win10 SDK TFM `net8.0-windows10.0.19041.0` so the projections resolve.
- **Two instances on ONE PC can't BT-connect to each other** — Bluetooth won't loop back to its own
  adapter. Use two PCs, or add the (not-yet-built) TCP-loopback transport for a same-PC loop. The
  replay engine is already transport-agnostic.
