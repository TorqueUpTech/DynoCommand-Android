# Skill: Add a new outbound (TX) packet

TX packets are built in `Protocol/PacketFactory.cs` and sent from the TX loop in
`DynoController.TxTick()`. The loop fires every ~50 ms (~20 Hz); 5 Hz sub-tasks run
every 4th tick.

## Steps

### 1. Add a factory method in `PacketFactory.cs`

```csharp
public static CanPacket MyPacket(CommandState s)
{
    var p = new CanPacket { Id = 0xXX, Cmd = 0xYY };
    p.Payload[0] = 0xZZ;          // sub-command / selector
    p.Value16 = (ushort)(s.SomeField * SCALE);
    return p;
}
```

`Value16` sets `Payload[2..3]` little-endian. For fixed/no-value packets leave it 0.

### 2. Send from the TX loop in `DynoController.TxTick()`

20 Hz (every tick):
```csharp
Send(PacketFactory.MyPacket(Command));
```

5 Hz (every 4th tick, inside the `if (++_tick5Hz >= 4)` block):
```csharp
Send(PacketFactory.MyPacket(Command));
```

### 3. Add state to `CommandState.cs` if needed

If the packet carries a value the operator controls:
```csharp
public double MyValue = DEFAULT;
```

Expose it on `MainViewModel` (property + nudge command + XAML) if the operator adjusts it.

## TX packet reference

| Factory method         | ID   | CMD  | P0   | Rate  | Purpose                                   |
|------------------------|------|------|------|-------|-------------------------------------------|
| `Ping()`               | `41` | `FB` | —    | 5 Hz  | keepalive                                 |
| `Knob(s)`              | `4F` | `0F` | `19` | 20 Hz | target speed/load/ramp value + active bit |
| `DigitalInputs(s)`     | `4F` | `0F` | `18` | 20 Hz | mode bits, fan bits, one-shot triggers    |
| `HfrMode(s)`           | `4F` | `05` | `E0` | 5 Hz  | hands-free run enable                     |
| `DynoModePacket(s)`    | `4F` | `6F` | `50` | 5 Hz  | Load vs Speed mode selector               |
| `Balance(s)`           | `4F` | `0F` | `17` | 5 Hz  | 4WD front/rear balance (4WD only)         |
| `RequestedBedPosition` | `48` | `A0` | `32` | 20 Hz | 4WD bed position (Set-Bed mode only)      |

## DigitalInputs bitfield (Payload[2..3])

| Bit    | Constant        | Meaning                         |
|--------|----------------|---------------------------------|
| 0x0001 | DI_GraphClear   | one-shot: clear graph           |
| 0x0002 | DI_Brake        | pit brake                       |
| 0x0004 | DI_Snapshot     | one-shot: snapshot              |
| 0x0008 | DI_CommandRpm   | speed control = engine (RPM)    |
| 0x0010 | DI_Fan1Low      | fan 1 low                       |
| 0x0020 | DI_Fan1High     | fan 1 high (Off=low, On=both, Auto=high) |
| 0x0040 | DI_Fan2Low      | fan 2 low                       |
| 0x0080 | DI_Fan2High     | fan 2 high                      |
| 0x0100 | DI_RampUp       | ramp run ascending              |
| 0x0200 | DI_RampDown     | ramp run descending             |
| 0x0400 | DI_Load         | load control active             |
| 0x0800 | DI_Speed        | speed control active / brake-clear pulse |
| 0x1000 | DI_SwapScreens  | swap display screens            |
