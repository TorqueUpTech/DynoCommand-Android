package com.dynocommand.app.protocol

/** Builds the packets the handheld continuously transmits. Mirrors the original. */
object PacketFactory {
    private const val DI_GRAPH_CLEAR = 0x0001
    private const val DI_BRAKE       = 0x0002
    private const val DI_SNAPSHOT    = 0x0004
    private const val DI_COMMAND_RPM = 0x0008
    private const val DI_FAN1_LOW    = 0x0010
    private const val DI_FAN1_HIGH   = 0x0020
    private const val DI_FAN2_LOW    = 0x0040
    private const val DI_FAN2_HIGH   = 0x0080
    private const val DI_RAMP_UP     = 0x0100
    private const val DI_RAMP_DOWN   = 0x0200
    private const val DI_LOAD        = 0x0400
    private const val DI_SPEED       = 0x0800
    private const val DI_SWAP        = 0x1000

    fun ping() = CanPacket().apply { id = 0x41; cmd = 0xFB }

    fun knob(s: CommandState): CanPacket {
        var value = 0
        var active = 0
        when (s.mode) {
            DynoMode.SetBed -> { value = 300; active = 1 }
            DynoMode.Speed -> {
                value = if (s.speedUnits == SpeedUnit.Rpm || s.speedControl == SpeedControl.Engine)
                    s.targetSpeed.toInt()
                else
                    (s.targetSpeed * 10.0).toInt()
                active = 1
            }
            DynoMode.Load -> { value = (s.targetLoad * 10.0).toInt(); active = 1 }
            DynoMode.RampUp, DynoMode.RampHold, DynoMode.RampDown -> value = s.effectiveRampRate.toInt()
            else -> {}
        }
        return CanPacket().apply {
            id = 0x4F; cmd = 0x0F; payload[0] = 0x19; payload[1] = active; value16 = value
        }
    }

    fun digitalInputs(s: CommandState): CanPacket {
        var bits = 0
        if (s.graphClearRequests > 0) { bits = bits or DI_GRAPH_CLEAR; s.graphClearRequests-- }
        if (s.snapshotRequests > 0)   { bits = bits or DI_SNAPSHOT;    s.snapshotRequests-- }
        if (s.swapScreenRequests > 0) { bits = bits or DI_SWAP;        s.swapScreenRequests-- }
        // Brake release: pulse the Speed command bit to clear the PC's latched brake warning.
        if (s.brakeClearRequests > 0) { bits = bits or DI_SPEED;       s.brakeClearRequests-- }

        when (s.mode) {
            DynoMode.RampUp   -> bits = bits or DI_LOAD or DI_RAMP_UP
            DynoMode.RampHold -> bits = bits or DI_LOAD
            DynoMode.RampDown -> bits = bits or DI_LOAD or DI_RAMP_DOWN
            DynoMode.Speed    -> bits = bits or DI_SPEED
            DynoMode.Load     -> bits = bits or DI_LOAD
            DynoMode.Brake    -> bits = bits or DI_BRAKE
            DynoMode.SetBed   -> bits = bits or DI_SPEED
            else -> {}
        }
        if (s.speedControl == SpeedControl.Engine) bits = bits or DI_COMMAND_RPM
        bits = bits or fanBits(s.fan1, DI_FAN1_LOW, DI_FAN1_HIGH)
        bits = bits or fanBits(s.fan2, DI_FAN2_LOW, DI_FAN2_HIGH)

        return CanPacket().apply { id = 0x4F; cmd = 0x0F; payload[0] = 0x18; value16 = bits }
    }

    private fun fanBits(mode: FanMode, low: Int, high: Int) = when (mode) {
        FanMode.Off -> low
        FanMode.On -> low or high
        FanMode.Auto -> high
        FanMode.PC -> 0
    }

    fun hfrMode(s: CommandState) = CanPacket().apply {
        id = 0x4F; cmd = 0x05; payload[0] = 0xE0
        value16 = if (s.mode == DynoMode.Handsfree) 0xA500 else 0x5050
    }

    fun dynoMode(s: CommandState) = CanPacket().apply {
        id = 0x4F; cmd = 0x6F; payload[0] = 0x50
        value16 = if (s.mode == DynoMode.Load) 0x35 else 0x53
    }

    fun balance(s: CommandState): CanPacket {
        val value = if (s.balanceActive) (Math.round(s.balance * -10.0).toInt() and 0xFFFF) else 0
        return CanPacket().apply { id = 0x4F; cmd = 0x0F; payload[0] = 0x17; value16 = value }
    }

    fun requestedBedPosition(s: CommandState): CanPacket? {
        if (s.bedRequestedPosition == 0.0) return null
        return CanPacket().apply {
            id = 0x48; cmd = 0xA0; payload[0] = 0x32; value16 = (s.bedRequestedPosition * 1000.0).toInt()
        }
    }
}
