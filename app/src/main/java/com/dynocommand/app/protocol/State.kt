package com.dynocommand.app.protocol

import kotlin.math.PI

/** What the operator wants the dyno to do — read by the TX loop each cycle. */
class CommandState {
    var mode = DynoMode.NoLoad

    /** Operator's dialled-in speed (active unit); fed into [targetSpeed] by the governor. */
    var requestedSpeed = 0.0
    /** Governed value actually sent by the knob packet (active unit). */
    var targetSpeed = 0.0
    var targetLoad = 0.0
    /** Operator-set ramp rate. */
    var rampRate = 80.0
    /** Operator-defined run start speed (active speed unit). Mirrored from settings. */
    var startSpeed = 80.0
    /** True while a shootout is active (PC owns the ramp rate); mirrored from received state. */
    var shootoutActive = false
    /** PC-dictated ramp rate, mirrored from received state. */
    var pcRampRate = 0.0
    /** Rate actually sent: the PC's during a shootout, otherwise the operator's. */
    val effectiveRampRate: Double get() = if (shootoutActive) pcRampRate else rampRate
    var balance = 0.0
    var balanceActive = false

    var speedUnits = SpeedUnit.Kph
    var speedControl = SpeedControl.Road

    var fan1 = FanMode.PC
    var fan2 = FanMode.PC

    var bedRequestedPosition = 0.0 // metres
    /** True only while a Set-Bed button is held; the bed packet is sent only then, so
     * releasing the button stops the bed — a dead-man, as the original did. */
    @Volatile var bedMoveActive = false

    var gearRatio = 1.0
    var rollerDiameter = 0.2186
    val rollerCircumference: Double get() = rollerDiameter * PI

    // Config the handheld reports back when the dyno queries it (CMD 0x3F) or
    // exchanges the BT address (CMD 0xD1). Mirrored from AppSettings.
    var speedMaxKph = 200.0
    var speedMaxMph = 124.0
    var speedMaxRpm = 5000.0
    var featureLevel = 0
    var demoMode = false
    /** EDM Bluetooth MAC (12 hex digits) — answers the address handshake. */
    var edmAddress = ""

    fun toKph(v: Double) = if (speedUnits == SpeedUnit.Mph) v * 1.609344 else v
    fun fromKph(kph: Double) = if (speedUnits == SpeedUnit.Mph) kph / 1.609344 else kph

    // Roller revs/sec is the physical core the original (SYS_Unit) stored speed in;
    // every unit is a view of it (rpm = roller rpm = revs/s x 60). Used to preserve
    // the physical roller speed when the display unit changes, as the original did.
    private fun toRevsPerSec(value: Double, unit: SpeedUnit) = when (unit) {
        SpeedUnit.Mph -> value * 1.609344 / (rollerCircumference * 3.6)
        SpeedUnit.Rpm -> value / 60.0
        else -> value / (rollerCircumference * 3.6) // Kph
    }
    private fun fromRevsPerSec(revs: Double, unit: SpeedUnit) = when (unit) {
        SpeedUnit.Mph -> revs * rollerCircumference * 3.6 * 0.621371192237334
        SpeedUnit.Rpm -> revs * 60.0
        else -> revs * rollerCircumference * 3.6 // Kph
    }
    /** Re-express a speed in a different unit, preserving the physical roller speed. */
    fun convertSpeed(value: Double, from: SpeedUnit, to: SpeedUnit) =
        fromRevsPerSec(toRevsPerSec(value, from), to)

    var is4Wd = false

    // one-shot requests
    var graphClearRequests = 0
    var snapshotRequests = 0
    var swapScreenRequests = 0
    /** When >0, briefly send the Speed command bit to clear the dyno's latched brake warning. */
    var brakeClearRequests = 0
}

/** Decoded live state received from the dyno (via the EDM). */
class DynoState {
    @Volatile var rawSpeedMaster = 0
    @Volatile var rawSpeedSlave = 0
    @Volatile var speedRevsPerSec = 0.0
    @Volatile var speedKph = 0.0
    @Volatile var brakeState = "—"
    @Volatile var handsfreeState = "—"
    @Volatile var shootoutMode = "—"

    /** True during an active (standard/accredited) shootout — PC owns the ramp rate. */
    @Volatile var shootoutActive = false
    /** Ramp rate dictated by the PC (rampratekph core), decoded from 0F A0 67. */
    @Volatile var pcRampRate = 0.0

    // ---- Bed position read-back (Set-Bed) ----
    @Volatile var bedCurrentPosition = 0.0 // metres (0 = none yet)
    @Volatile var bedTargetPosition = 0.0  // metres

    // ---- Config pushed by the dyno (CMD 0x2F) ----
    @Volatile var configReceived = false
    @Volatile var in4wdMode = false
    @Volatile var masterIsController = true
    @Volatile var isA4wdBed = false
    @Volatile var transmission = TransmissionType.Unknown
    @Volatile var dynoRollerDiameter = 0.0 // metres (0 until received)
    @Volatile var dynoSpeedMaxKph = 0.0
    @Volatile var dynoSpeedMaxMph = 0.0
    @Volatile var dynoSpeedMaxRpm = 0.0
    @Volatile var featureLevel = 0
    @Volatile var demoMode = false
    /** Speed unit the dyno last asked us to display (CMD 0x6F 0x60). */
    @Volatile var dynoRequestedUnit = "—"
    /** EDM Bluetooth address learned via the 0xD1 handshake (if any). */
    @Volatile var learnedBtAddress = ""

    // Scratch for the two-part roller-diameter config message.
    var tempRollerDiameterHi = 0

    // ---- Device-online watchdog ----
    @Volatile var pitControllerOnline = false
    @Volatile var pcOnline = false
    @Volatile var bedCtrlOnline = false
    @Volatile var pitSeenMillis = 0L
    @Volatile var pcSeenMillis = 0L
    @Volatile var bedSeenMillis = 0L
    @Volatile var lastRxMillis = 0L

    /**
     * Comms-loss fail-safe, called from the TX loop. When the pit controller stops
     * transmitting, zero the roller speed (so a frozen reading can't be mistaken
     * for a live one) and clear the online flags for any silent subsystem.
     */
    fun updateWatchdog(now: Long) {
        pitControllerOnline = pitSeenMillis != 0L && now - pitSeenMillis < DEVICE_TIMEOUT
        pcOnline = pcSeenMillis != 0L && now - pcSeenMillis < DEVICE_TIMEOUT
        bedCtrlOnline = bedSeenMillis != 0L && now - bedSeenMillis < DEVICE_TIMEOUT

        if (!pitControllerOnline) {
            rawSpeedMaster = 0
            rawSpeedSlave = 0
            speedRevsPerSec = 0.0
            speedKph = 0.0
        }
        if (!pcOnline) shootoutActive = false
    }

    /** Reset everything to a safe idle (on disconnect). */
    fun resetForDisconnect() {
        rawSpeedMaster = 0; rawSpeedSlave = 0
        speedRevsPerSec = 0.0; speedKph = 0.0
        shootoutActive = false
        pitControllerOnline = false; pcOnline = false; bedCtrlOnline = false
        pitSeenMillis = 0L; pcSeenMillis = 0L; bedSeenMillis = 0L; lastRxMillis = 0L
        brakeState = "—"; handsfreeState = "—"
    }

    companion object {
        /** How long a subsystem may go silent before it's declared offline. */
        private const val DEVICE_TIMEOUT = 1500L
    }
}
