# Skill: EDM Bluetooth protocol reference

## Transport

- **BT Classic SPP/RFCOMM**, UUID `00001101-0000-1000-8000-00805F9B34FB`
- EDM MAC: `0012F304962C`, BT name: "SERVER"
- EDM does **NOT** bond/pair — use insecure RFCOMM (`createInsecureRfcommSocketToServiceRecord` on
  Android; raw Winsock `AF_BTH` with `SOCKADDR_BTH` Pack=1 on Windows)
- Windows: `SOCKADDR_BTH` must be `[StructLayout(LayoutKind.Sequential, Pack=1)]` (30 bytes).
  Default padding misplaces `btAddr` → WSAEADDRNOTAVAIL. See `BluetoothRfcommTransport.cs`.

## Packet structure (10 bytes)

```
[0] Id       — source/dest node address
[1] Cmd      — command class
[2] Payload[0] — sub-command / selector
[3] Payload[1]
[4] Payload[2] — Value16 low byte
[5] Payload[3] — Value16 high byte
[6] Tsl      — timestamp low (unused by us)
[7] Tsh      — timestamp high
[8] Crcl     — CRC16 low
[9] Crch     — CRC16 high
```

CRC is **CRC-16/MODBUS**: poly=0xA001, init=0xFFFF, computed over bytes [0..7] (first 8 bytes).
See `Protocol/Crc16.cs`.

## Stream framing

Packets are byte-stuffed inside a stream frame:
```
[0xA0]  SYNC byte
[LEN]   length of stuffed body (before unstuffing)
[body]  stuffed bytes — 0xA0 in the body is escaped as 0xA0 0x00
```

See `Protocol/PacketFramer.cs` (TX wrap) and `Protocol/PacketParser.cs` `FrameParser` (RX).

## Speed scaling

```
revs/s  = raw_value * 0.002 / GearRatio
km/h    = revs_per_sec * (RollerDiameter * π) * 3.6
```

Default roller diameter: **0.2186 m** → circumference ≈ 0.687 m.
Default gear ratio: **1.0** (direct drive).
Speed threshold for "moving": **raw >= 160** (below this is considered stationary noise).

When both master and slave rollers report speed, the average is used.

## TX cadence

- **20 Hz** (every 50 ms): `Knob` + `DigitalInputs` (+ `RequestedBedPosition` in SetBed mode)
- **5 Hz** (every 4th tick): `Ping` + `HfrMode` + `DynoModePacket` (+ `Balance` if 4WD)

## Safety governor (Windows/Android TX)

The governed target (`TargetSpeed`, sent in the `Knob` packet) is clamped to:
- `actual - 10 km/h` ≤ target ≤ `actual + 10 km/h`
- If actual ≥ 5 km/h, minimum commanded = 5 km/h (no zero command while moving)

Operator's `RequestedSpeed` feeds into `TargetSpeed` gradually as the dyno catches up.
Engine/RPM mode bypasses the governor.

## Shootout mode

Two independent broadcasts from the PC:
1. `0F 25 11` = Standard shootout active, `0F 25 12` = Accredited, `0F 25 10` = Normal (off)
2. `0F A0 67` = PC ramp rate (scale: `raw * 0.002 * 10.0 / GearRatio`)

When shootout is active, the handheld uses the PC's ramp rate (`PcRampRate`) not the operator's.
`CommandState.EffectiveRampRate` = `PcRampRate` if shootout, else `RampRate`.

### Ramp rate unit & display
Ramp rate is **km/h per second** (user-confirmed). Stored as core revs/s; the `0F A0 67` raw parses
`raw*0.002*10/GearRatio` (core). To display, apply the SAME conversion as road speed:
`core * RollerCircumference * 3.6` → km/h/s. The PC-locked value read raw (unitless) reads ~0.4×
too low; always convert. UI shows e.g. `150 km/h/s — PC (Standard shootout)`.

### Shootout run-parameter block (`0F 05`, PC → handheld, currently undecoded by the apps)
Sub-addressed by P0: `A0` = start speed (~80 km/h), `B0` = 30 (unidentified), `C0` = ramp rate
(mirrors `0F A0 67`), `D0` = finish/max speed (~200 km/h). A0/C0/D0 use the speed scaling
(`raw*0.002*circ*3.6`; C0 ×10 for ramp). The original handheld ignores this block.

### Drivetrain inertia is NOT on the bus
Proven by a controlled capture (two profiles, same ramp, only inertia differed → zero bus change).
Inertia stays internal to the dyno PC (its power calc). Consequence: shootout sub-modes are
distinguishable ONLY by ramp rate, so modes sharing a ramp are byte-identical — can't be told apart.
(Idea to label modes from the ramp value was scrapped for this reason.)

## Brake interlock

- Brake can only engage when `SpeedKph < 5.0`.
- If requested above 5 km/h, it is "armed" (coast/No Load) and auto-engages when speed drops.
- Brake release: set mode to NoLoad + pulse `DI_Speed` bit 6× (~0.3 s) to clear the PC's latched brake warning.
