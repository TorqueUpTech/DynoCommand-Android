package com.dynocommand.app.protocol

enum class DynoMode { NoLoad, Speed, Load, RampUp, RampHold, RampDown, Brake, Handsfree, SetBed }

enum class SpeedUnit { Kph, Mph, Rpm }

enum class SpeedControl { Road, Engine }

/** Per-fan output state, matches FAN_CONTROL_TYPE. */
enum class FanMode { PC, Off, On, Auto }

/** Drivetrain/bed type, reported by the dyno in a config push (CMD 0x2F P0 0x05). Mirrors TRANSMISSIONS. */
enum class TransmissionType {
    Unknown,
    Standard2Wd,
    Locked4Wd,
    CenterDiff4Wd,
    CenterViscous4Wd,
    ComputerDogClutch4Wd,
    TractionStability4Wd,
    FrontViscous4Wd
}
