# Skill: Windows WPF build and run

> **This app now lives in its own repo.** It moved out of this tree (was
> `Windows\DynoCommandPC\`) to **`D:\Projects\Dyno\EDM-Simulator\`** —
> github.com/TorqueUpTech/EDM-Simulator. It is the **simulator**; its handheld
> code is a Jul-2026 snapshot of `D:\Projects\Dyno\DynoCommandPC\`
> (github.com/TorqueUpTech/DynoCommandPC) and has diverged. Day-to-day handheld
> work happens in DynoCommandPC; port fixes across rather than assuming parity.

## Project location
`D:\Projects\Dyno\EDM-Simulator\DynoCommandPC\` — `DynoCommandPC.csproj` (.NET 8 WPF),
with `DynoCommandPC.sln` one level up at the repo root.

## Three build variants (ONE codebase, `EdmSim` / `DynoNet` flags)
The csproj gates the EDM Simulator behind an MSBuild property `EdmSim` (default **true**):
- **EdmSim=true** (default) — full app incl. the **EDM Simulator** (BT SPP server). Uses the Win10
  SDK TFM `net8.0-windows10.0.19041.0` (needed for WinRT Bluetooth). Title shows "— Sim build";
  the **EDM Sim…** button is visible; `EDM_SIM` constant defined.
- **EdmSim=false** — plain **handheld-only** (`net8.0-windows`); `Server/*` + `EdmServerWindow`
  excluded, button hidden, `OpenEdmServer_Click` `#if EDM_SIM`-stubbed.
- **DynoNet=true** — handheld over TCP/IP via the **DynoServer** relay (`TcpDynoTransport`).
  Implies non-sim; defines `DYNO_NET`.

```powershell
# Stop a running instance first — the build locks DynoCommandPC.exe
Get-Process DynoCommandPC -ErrorAction SilentlyContinue | Stop-Process -Force

cd D:\Projects\Dyno\EDM-Simulator\DynoCommandPC
dotnet build DynoCommandPC.csproj                 # Sim variant (default)
dotnet build DynoCommandPC.csproj -p:EdmSim=false # Handheld-only variant
dotnet build DynoCommandPC.csproj -p:DynoNet=true # Network (DynoServer) variant
```
Outputs land in different TFM folders (no clobber):
- Sim: `bin\Debug\net8.0-windows10.0.19041.0\DynoCommandPC.exe`
- Handheld: `bin\Debug\net8.0-windows\DynoCommandPC.exe`

Publish to `dist\` (each folder ends up with its own `settings.json`):
```powershell
dotnet publish DynoCommandPC.csproj -c Release -o D:\Projects\Dyno\EDM-Simulator\dist\EDM-Sim
dotnet publish DynoCommandPC.csproj -c Release -p:EdmSim=false -o D:\Projects\Dyno\EDM-Simulator\dist\Handheld
```

## Transport
Bluetooth-only: `BluetoothRfcommTransport` (raw Winsock AF_BTH, no COM port, **no pairing** — the
EDM won't bond). Set the EDM MAC in ⚙ Settings (default `0012F304962C`). The old
`SimulatorTransport` and Serial COM path were **removed** (direct comms confirmed).

## Settings file
`settings.json` **beside the exe** (`AppContext.BaseDirectory`) — portable, NOT `%APPDATA%`. Each
dist/bin folder has its own; delete to reset. Two instances in the SAME folder fight over one file
— run "two versions" from separate folders.

## EDM Simulator test rig (Sim build only)
**EDM Sim…** → Browse a `dyno-capture-*.txt` log → Start. Hosts a native WinRT RFCOMM SPP server
(`RfcommServiceProvider`, `PlainSocket` = no pairing; works unpackaged) and replays the
dyno→handheld frames. Point a handheld (Android app / 2nd PC) at THIS PC's Bluetooth MAC. Full
procedure: see [edm-sim-testing.md](edm-sim-testing.md).

## Debugging packet traffic
Settings → show packet log + "packet types seen" inspector (ID/CMD/P0 with min/max, labelled via
`LabelFor`). `Last TX`/`Last RX` show raw hex; comms-loss tally + optional file log (`logs\` beside
the exe).

## Common WSA errors on connect (surfaced in the status bar)
| Code | Meaning |
|------|---------|
| 10060 | Timed out — EDM off / out of range |
| 10061 | Refused — busy or auth required |
| 10013 | Access denied — may need pairing |
| 10108 | SPP SDP service not found |

Decoded in `BluetoothRfcommTransport.WsaHint()`.
