# Skill: Ramp run flow (Basic / Advanced)

The ramp run state machine is in `MainViewModel` (~line 197). There are two variants
driven by a single cycling button press.

## Sequences

**Advanced** (4 steps):
```
press 1: Drive to start speed  → DynoMode.Speed  (sets RequestedSpeed = current speed)
press 2: Ramp Up               → DynoMode.RampUp
press 3: Hold (run finished)   → DynoMode.RampHold
press 4: Ramp Down             → DynoMode.RampDown
press 5: End (free spin)       → DynoMode.NoLoad, exits run
```

**Basic** (3 steps):
```
press 1: Drive to start speed  → DynoMode.Speed  (sets RequestedSpeed = current speed)
press 2: Ramp Up               → DynoMode.RampUp
press 3: Hold (run finished)   → DynoMode.RampHold
press 4: End (free spin)       → DynoMode.NoLoad, exits run
```

Basic omits the Ramp Down — car coasts after the run.

**Start speed:** the protocol has no dedicated start-speed packet (the original never
sent one; hands-free start speed is dyno-owned and only received). So the first step
just commands **Speed mode** via the normal Knob/Speed path, holding the speed the dyno
is already doing so the operator drives up to their own start speed and locks it in
before pressing Ramp Up.

`RampStartSpeed()` (PC) / `rampStartSpeed()` (Android) picks that value:

```
roadSpeed && State.SpeedKph >= 5.0  ->  FromKph(State.SpeedKph)   // hold current speed
otherwise                           ->  CommandState.StartSpeed    // configured fallback
                                        (then clamped to MaxForCurrentUnit())
```

Two reasons it falls back to the configured `StartSpeed` (the operator setting mirrored
into `CommandState.StartSpeed`):
- **Dyno stopped** (< 5 km/h) — nothing to hold, so drive up to the preset as before.
- **Engine/rpm control** (`SpeedControl.Engine` or `SpeedUnit.Rpm`) — the received state
  only carries road speed (`DynoState` has no engine rpm), and `FromKph` passes rpm
  through unconverted. Same gate as `DynoController.Govern()`.

The watchdog zeroes `SpeedKph` when the pit controller goes silent, so a stale reading
can't be captured as a start point — it falls back for free.

## TX during a ramp run

`Knob` packet: `p.Value16 = (ushort)s.EffectiveRampRate` (not a speed).
`DigitalInputs`:
- `RampHold`: `DI_Load` only
- `RampUp`:   `DI_Load | DI_RampUp`
- `RampDown`: `DI_Load | DI_RampDown`

## State variables

```csharp
bool  _inRampRun   // true while a run is in progress
bool  _runIsBasic  // Basic vs Advanced
int   _rampStep    // index into the active sequence
```

`ShowRamp` (visibility of the ramp-rate nudge control) = `_inRampRun`.

## Button text / highlight

- `BasicButtonText` / `AdvancedButtonText`: shows the **next** press label while active,
  or "Basic ▶" / "Advanced ▶" when idle.
- `BasicActiveTag` / `AdvancedActiveTag`: set to `"ACTIVE"` while that variant is running;
  bound to `ModeBrushConverter` to green-highlight the active button.

## Ramp rate during shootout

`EffectiveRampRate` = `PcRampRate` when `ShootoutActive`, else operator's `RampRate`.
The ramp-rate nudge control is disabled (`IsEnabled="{Binding RampUnlocked}"`) during shootout.
The shootout banner appears above the mode buttons.

## Any mode button or Brake cancels an in-progress run

`SetMode()` and `ToggleBrake()` both set `_inRampRun = false` before switching mode.
