package com.dynocommand.app.settings

import android.content.Context
import com.dynocommand.app.protocol.SpeedControl
import com.dynocommand.app.protocol.SpeedUnit

/** Persisted operator settings — the Android equivalent of PdaSettings.xml. */
data class AppSettings(
    // --- Connection (Bluetooth-direct only) ---
    var edmAddress: String = "0012F304962C",
    var autoConnectAtStartup: Boolean = false,
    var logCommsToFile: Boolean = false,
    // --- Dyno type ---
    var is4Wd: Boolean = false,
    // 4WD bed travel limits (metres). The dyno doesn't report these; they mirror the
    // original's BedLength config. Shorten drives to min, Lengthen to max.
    var bedLengthMin: Double = 2.25,
    var bedLengthMax: Double = 3.5,
    // --- Diagnostics panel visibility ---
    var showPacketLog: Boolean = true,
    var showPacketsSeen: Boolean = true,
    // --- Feedback ---
    var audibleFeedback: Boolean = false,
    // --- Speed ---
    var speedUnits: SpeedUnit = SpeedUnit.Kph,
    var speedControl: SpeedControl = SpeedControl.Road,
    var rollerDiameter: Double = 0.2186,
    var gearRatio: Double = 1.0,
    var speedMaxKph: Double = 200.0,
    var speedMaxMph: Double = 124.0,
    var speedMaxRpm: Double = 5000.0,
    // Speed-nudge increments, held per unit — a step that suits km/h (±1/±5) is
    // uselessly fine in rpm (which wants ±10/±100), so the active set follows the
    // effective mode. Mirrors the original's per-unit SpeedInc_* values.
    var speedIncSmallKph: Double = 1.0,
    var speedIncLargeKph: Double = 5.0,
    var speedIncSmallMph: Double = 1.0,
    var speedIncLargeMph: Double = 5.0,
    var speedIncSmallRpm: Double = 100.0,
    var speedIncLargeRpm: Double = 1000.0,
    /** Operator-defined run start speed (in the active speed unit). */
    var startSpeed: Double = 80.0,
    // --- Load (fixed-load mode). Percent, clamped loadMin..loadMax. Two resolutions
    // (fine/coarse) toggled at runtime; the small buttons step by the small inc, the
    // large buttons snap to the large-inc grid then step. Mirrors the original's
    // Load*Inc / LoadMin / LoadMax config. ---
    var loadMin: Double = 0.0,
    var loadMax: Double = 200.0,
    var loadFineSmallInc: Double = 0.1,
    var loadFineLargeInc: Double = 5.0,
    var loadCoarseSmallInc: Double = 1.0,
    var loadCoarseLargeInc: Double = 10.0,
    /** true = fine (0.1 / 5), false = coarse (1 / 10). Persisted like the original's LoadResolution. */
    var loadResolutionFine: Boolean = true
) {
    fun maxForCurrentUnit(): Double = when (speedUnits) {
        SpeedUnit.Rpm -> speedMaxRpm
        SpeedUnit.Mph -> speedMaxMph
        else -> speedMaxKph
    }

    fun unitLabel(): String = when (speedUnits) {
        SpeedUnit.Rpm -> "rpm"
        SpeedUnit.Mph -> "mph"
        else -> "km/h"
    }

    /**
     * True when speed is commanded in engine terms — rpm units, or Engine control
     * regardless of unit. Matches the gate used by the governor, the TX encoder and
     * the ramp start-speed logic.
     */
    val isEngineMode: Boolean get() = speedUnits == SpeedUnit.Rpm || speedControl == SpeedControl.Engine

    /** Small nudge increment for the active mode/unit. */
    fun activeSpeedIncSmall(): Double = when {
        isEngineMode -> speedIncSmallRpm
        speedUnits == SpeedUnit.Mph -> speedIncSmallMph
        else -> speedIncSmallKph
    }

    /** Large nudge increment for the active mode/unit. */
    fun activeSpeedIncLarge(): Double = when {
        isEngineMode -> speedIncLargeRpm
        speedUnits == SpeedUnit.Mph -> speedIncLargeMph
        else -> speedIncLargeKph
    }

    /** Convert a km/h value into the active speed unit (mph converted; rpm left as-is). */
    fun fromKph(kph: Double): Double = if (speedUnits == SpeedUnit.Mph) kph / 1.609344 else kph

    /** Small (Left/Right) load step for the active resolution. */
    fun loadSmallInc(): Double = if (loadResolutionFine) loadFineSmallInc else loadCoarseSmallInc

    /** Large (Up/Down, snap-to-grid) load step for the active resolution. */
    fun loadLargeInc(): Double = if (loadResolutionFine) loadFineLargeInc else loadCoarseLargeInc
}

object SettingsStore {
    private const val PREFS = "dyno_settings"

    fun load(ctx: Context): AppSettings {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AppSettings(
            edmAddress = p.getString("edm", "0012F304962C") ?: "0012F304962C",
            autoConnectAtStartup = p.getBoolean("autoconnect", false),
            logCommsToFile = p.getBoolean("logcomms", false),
            is4Wd = p.getBoolean("is4wd", false),
            bedLengthMin = p.getFloat("bedmin", 2.25f).toDouble(),
            bedLengthMax = p.getFloat("bedmax", 3.5f).toDouble(),
            showPacketLog = p.getBoolean("showlog", true),
            showPacketsSeen = p.getBoolean("showseen", true),
            audibleFeedback = p.getBoolean("audible", false),
            speedUnits = runCatching { SpeedUnit.valueOf(p.getString("units", "Kph")!!) }.getOrDefault(SpeedUnit.Kph),
            speedControl = runCatching { SpeedControl.valueOf(p.getString("control", "Road")!!) }.getOrDefault(SpeedControl.Road),
            rollerDiameter = p.getFloat("roller", 0.2186f).toDouble(),
            gearRatio = p.getFloat("gear", 1.0f).toDouble(),
            speedMaxKph = p.getFloat("maxkph", 200f).toDouble(),
            speedMaxMph = p.getFloat("maxmph", 124f).toDouble(),
            speedMaxRpm = p.getFloat("maxrpm", 5000f).toDouble(),
            speedIncSmallKph = p.getFloat("incs_kph", 1f).toDouble(),
            speedIncLargeKph = p.getFloat("incl_kph", 5f).toDouble(),
            speedIncSmallMph = p.getFloat("incs_mph", 1f).toDouble(),
            speedIncLargeMph = p.getFloat("incl_mph", 5f).toDouble(),
            speedIncSmallRpm = p.getFloat("incs_rpm", 100f).toDouble(),
            speedIncLargeRpm = p.getFloat("incl_rpm", 1000f).toDouble(),
            startSpeed = p.getFloat("startspd", 80f).toDouble(),
            loadMin = p.getFloat("loadmin", 0f).toDouble(),
            loadMax = p.getFloat("loadmax", 200f).toDouble(),
            loadFineSmallInc = p.getFloat("load_fs", 0.1f).toDouble(),
            loadFineLargeInc = p.getFloat("load_fl", 5f).toDouble(),
            loadCoarseSmallInc = p.getFloat("load_cs", 1f).toDouble(),
            loadCoarseLargeInc = p.getFloat("load_cl", 10f).toDouble(),
            loadResolutionFine = p.getBoolean("load_fine", true)
        )
    }

    fun save(ctx: Context, s: AppSettings) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString("edm", s.edmAddress)
            putBoolean("autoconnect", s.autoConnectAtStartup)
            putBoolean("logcomms", s.logCommsToFile)
            putBoolean("is4wd", s.is4Wd)
            putFloat("bedmin", s.bedLengthMin.toFloat())
            putFloat("bedmax", s.bedLengthMax.toFloat())
            putBoolean("showlog", s.showPacketLog)
            putBoolean("showseen", s.showPacketsSeen)
            putBoolean("audible", s.audibleFeedback)
            putString("units", s.speedUnits.name)
            putString("control", s.speedControl.name)
            putFloat("roller", s.rollerDiameter.toFloat())
            putFloat("gear", s.gearRatio.toFloat())
            putFloat("maxkph", s.speedMaxKph.toFloat())
            putFloat("maxmph", s.speedMaxMph.toFloat())
            putFloat("maxrpm", s.speedMaxRpm.toFloat())
            putFloat("incs_kph", s.speedIncSmallKph.toFloat())
            putFloat("incl_kph", s.speedIncLargeKph.toFloat())
            putFloat("incs_mph", s.speedIncSmallMph.toFloat())
            putFloat("incl_mph", s.speedIncLargeMph.toFloat())
            putFloat("incs_rpm", s.speedIncSmallRpm.toFloat())
            putFloat("incl_rpm", s.speedIncLargeRpm.toFloat())
            putFloat("startspd", s.startSpeed.toFloat())
            putFloat("loadmin", s.loadMin.toFloat())
            putFloat("loadmax", s.loadMax.toFloat())
            putFloat("load_fs", s.loadFineSmallInc.toFloat())
            putFloat("load_fl", s.loadFineLargeInc.toFloat())
            putFloat("load_cs", s.loadCoarseSmallInc.toFloat())
            putFloat("load_cl", s.loadCoarseLargeInc.toFloat())
            putBoolean("load_fine", s.loadResolutionFine)
            apply()
        }
    }
}
