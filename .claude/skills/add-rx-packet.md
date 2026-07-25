# Skill: Add a new inbound (RX) packet decoder

All inbound packets are processed in `Protocol/PacketParser.cs` → `Process()`.
The packet inspector (`InspectorText` in the UI) shows every distinct ID/CMD/P0 seen,
including ones not yet decoded — use that to identify new packet types first.

## Packet structure recap

```
byte[0] = Id    (source node / address)
byte[1] = Cmd   (command class)
byte[2] = Payload[0]   (sub-command / selector)
byte[3] = Payload[1]
byte[4] = Payload[2]   ← Value16 low byte
byte[5] = Payload[3]   ← Value16 high byte
```

`p.Value16` = `Payload[2] | (Payload[3] << 8)` — the main 16-bit numeric value.

## Steps

### 1. Identify the packet

From the inspector: `ID=XX CMD=YY P0=ZZ  val=...`. Note the source group:
- `ID & 0xF0 == 0x20` → master roller controller
- `ID & 0xF0 == 0x70` → slave roller controller
- `ID == 0x0F`, `CMD == 0xA0` → from the PC (broadcasted settings)

### 2. Add a field to `DynoState.cs`

```csharp
/// <summary>Brief description and units.</summary>
public double MyNewValue;
```

### 3. Decode in `PacketParser.Process()`

Add a branch in `Protocol/PacketParser.cs`. Match the most specific condition first:
```csharp
if (p.Id == 0xXX && p.Cmd == 0xYY && p.Payload[0] == 0xZZ)
    st.MyNewValue = p.Value16 * SCALE_FACTOR;
```
For source-group matches use `(p.Id & 0xF0) == 0x20` etc.

### 4. Expose it in the UI (optional)

Follow the `add-live-readout` skill to wire it to the left panel.

### 5. Add a label to `LabelFor()` in `MainViewModel.cs`

In the `LabelFor()` helper (~line 361), add a descriptive return so the inspector is readable:
```csharp
if (cmd == 0xYY && p0 == 0xZZ) return "my new field description";
```

## Known decoded packets

| ID (masked) | CMD  | P0   | Meaning                        | Value scaling                        |
|-------------|------|------|--------------------------------|--------------------------------------|
| `0x2x`      | `0F` | `0A` | Roller speed (master)          | `raw * 0.002 / GearRatio` rev/s      |
| `0x7x`      | `0F` | `5B` | Roller speed (slave)           | same                                  |
| `0x2x`      | `0F` | `1A` | Pit brake (master)             | bit 0x200 = Active                   |
| `0x7x`      | `0F` | `56` | Pit brake (slave)              | same                                  |
| `0x0F`      | `25` | `10` | Shootout off (Normal)          | —                                     |
| `0x0F`      | `25` | `11` | Standard shootout active       | —                                     |
| `0x0F`      | `25` | `12` | Accredited shootout active     | —                                     |
| any         | `25` | `A0`–`A5` | Hands-free state machine  | string label                          |
| `0x0F`      | `A0` | `67` | PC ramp rate (shootout)        | `raw * 0.002 * 10.0 / GearRatio`     |
