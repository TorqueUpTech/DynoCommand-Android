package com.dynocommand.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.content.Intent
import com.dynocommand.app.DynoLink
import com.dynocommand.app.databinding.ActivityMainBinding
import com.dynocommand.app.protocol.DynoMode
import com.dynocommand.app.protocol.FanMode
import com.dynocommand.app.protocol.SpeedControl
import com.dynocommand.app.protocol.SpeedUnit
import com.dynocommand.app.protocol.TransmissionType
import com.dynocommand.app.service.DynoConnectionService
import com.dynocommand.app.settings.AppSettings
import com.dynocommand.app.settings.SettingsStore
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val controller get() = DynoLink.controller
    private lateinit var settings: AppSettings

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            updateUi()
            handler.postDelayed(this, 100)
        }
    }

    private var deviceMacs: List<String> = emptyList()
    private var selectedMac: String? = null
    private val tone by lazy { android.media.ToneGenerator(android.media.AudioManager.STREAM_SYSTEM, 80) }

    // ---- Ramp runs: cycling steppers that mirror the PC app's Manual Ramp / Advanced ----
    // The first step holds the speed the dyno is already doing via Speed mode (the normal
    // Knob/Speed path — the protocol has no dedicated start-speed packet), then:
    // Advanced:       Speed(start) -> Ramp Up -> Hold(finish) -> Ramp Down -> off.
    // Manual (basic): Speed(start) -> Ramp Up -> Hold(finish) -> off (coast; no ramp-down).
    private data class RampStep(val mode: DynoMode, val next: String, val state: String)
    private val advancedSeq = listOf(
        RampStep(DynoMode.Speed,    "Ramp Up",           "Drive to start speed"),
        RampStep(DynoMode.RampUp,   "Hold (finish run)", "Ramping up"),
        RampStep(DynoMode.RampHold, "Ramp Down",         "Hold (run finished)"),
        RampStep(DynoMode.RampDown, "End (free spin)",   "Ramping down"),
    )
    private val basicSeq = listOf(
        RampStep(DynoMode.Speed,    "Ramp Up",           "Drive to start speed"),
        RampStep(DynoMode.RampUp,   "Hold (finish run)", "Ramping up"),
        RampStep(DynoMode.RampHold, "End (free spin)",   "Hold (run finished)"),
    )
    private val activeSeq get() = if (runIsBasic) basicSeq else advancedSeq
    private var rampStep = 0
    private var inRampRun = false
    private var runIsBasic = false

    // Below this the dyno counts as stopped, so a run starts from the configured startSpeed.
    private val rampHoldMinKph = 5.0

    // Brake is only offered when it can actually engage (at/below the interlock speed).
    private val brakeEngageMaxKph = 5.0

    // Speed-mode (road<->engine / unit) may only change near a standstill — see cycleSpeedMode.
    private val speedModeChangeMaxKph = 10.0

    private val requestConnectPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) { populateDevices(); maybeAutoConnect() } else
                Toast.makeText(this, "Bluetooth permission needed to connect", Toast.LENGTH_LONG).show()
        }

    private val requestNotifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* link runs regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        settings = SettingsStore.load(this)
        DynoLink.init(this)

        // Restore an in-progress ramp run if the Activity was recreated (process
        // death). Rotation/config changes are handled without recreation via
        // android:configChanges, but this covers the system reclaiming the app so a
        // manual/advanced ramp doesn't snap back to the main screen mid-run.
        savedInstanceState?.let {
            inRampRun = it.getBoolean("inRampRun", false)
            rampStep = it.getInt("rampStep", 0)
            runIsBasic = it.getBoolean("runIsBasic", false)
        }

        // Startup default target: the physical equivalent of 80 km/h in the active
        // unit (80 km/h, ~50 mph, or ~2000 rpm) — not a bare 80 in whatever unit.
        controller.command.rollerDiameter = settings.rollerDiameter // so the rpm conversion uses the real roller
        controller.command.requestedSpeed = defaultTargetForCurrentUnit()

        setupFanSpinners()
        wireModeButtons()
        wireSpeedButtons()
        wireLoadButtons()
        wireRampButtons()
        wireBalanceButtons()
        wireBedButtons()
        wireActions()

        b.btnConnect.setOnClickListener { connect() }
        b.btnDisconnect.setOnClickListener { disconnect() }
        b.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        b.switch4wd.setOnCheckedChangeListener { _, v -> controller.command.is4Wd = v }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Preserve the ramp run across Activity recreation (see onCreate).
        outState.putBoolean("inRampRun", inRampRun)
        outState.putInt("rampStep", rampStep)
        outState.putBoolean("runIsBasic", runIsBasic)
    }

    override fun onResume() {
        super.onResume()
        settings = SettingsStore.load(this)
        applySettings()
        ensureNotifPermission()
        ensureBtPermissionThenList()
        maybeAutoConnect()
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    // Intentionally does NOT disconnect — the link is owned by the foreground
    // service so it stays alive when this Activity is destroyed/backgrounded.
    override fun onDestroy() {
        super.onDestroy()
    }

    private fun ensureNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun applySettings() {
        val c = controller.command
        c.is4Wd = settings.is4Wd
        c.gearRatio = settings.gearRatio
        c.rollerDiameter = settings.rollerDiameter
        c.speedUnits = settings.speedUnits
        c.speedControl = settings.speedControl
        c.speedMaxKph = settings.speedMaxKph
        c.speedMaxMph = settings.speedMaxMph
        c.speedMaxRpm = settings.speedMaxRpm
        c.startSpeed = settings.startSpeed
        c.edmAddress = settings.edmAddress
        if (c.requestedSpeed > settings.maxForCurrentUnit()) c.requestedSpeed = settings.maxForCurrentUnit()
        c.targetLoad = c.targetLoad.coerceIn(settings.loadMin, settings.loadMax)
        selectedMac = settings.edmAddress

        // Comms file logging (sink owned by DynoLink) + which diagnostics panels show.
        controller.commsLogEnabled = settings.logCommsToFile
        controller.logEnabled = settings.showPacketLog

        b.switch4wd.isChecked = settings.is4Wd
        b.lblTarget.text = "TARGET SPEED (${settings.unitLabel()})"
        b.txtSetUnit.text = settings.unitLabel()
        b.txtUnit.text = "km/h" // live decode is km/h
        b.btnSpeedDecL.text = "-${fmt(settings.activeSpeedIncLarge())}"
        b.btnSpeedDecS.text = "-${fmt(settings.activeSpeedIncSmall())}"
        b.btnSpeedIncS.text = "+${fmt(settings.activeSpeedIncSmall())}"
        b.btnSpeedIncL.text = "+${fmt(settings.activeSpeedIncLarge())}"
        b.btnSpeedMode.text = when {
            settings.isEngineMode -> "Speed: engine"
            settings.speedUnits == SpeedUnit.Mph -> "Speed: mph"
            else -> "Speed: km/h"
        }
        b.btnLoadDecL.text = "-${fmt(settings.loadLargeInc())}"
        b.btnLoadDecS.text = "-${fmt(settings.loadSmallInc())}"
        b.btnLoadIncS.text = "+${fmt(settings.loadSmallInc())}"
        b.btnLoadIncL.text = "+${fmt(settings.loadLargeInc())}"
        b.btnLoadRes.text = if (settings.loadResolutionFine) "Res: fine" else "Res: coarse"
    }

    private fun setupFanSpinners() {
        val fans = FanMode.values()
        b.spinnerFan1.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fans)
        b.spinnerFan2.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fans)
        b.spinnerFan1.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { controller.command.fan1 = fans[pos] }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        b.spinnerFan2.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { controller.command.fan2 = fans[pos] }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun wireModeButtons() {
        b.btnNoLoad.setOnClickListener { setMode(DynoMode.NoLoad) }
        b.btnSpeed.setOnClickListener { setMode(DynoMode.Speed) }
        b.btnLoad.setOnClickListener { setMode(DynoMode.Load) }
        b.btnManualRamp.setOnClickListener { runCycle(basic = true) }
        b.btnAdvanced.setOnClickListener { runCycle(basic = false) }
        b.btnActiveRamp.setOnClickListener { runCycle(basic = runIsBasic) }
        b.btnCancelRamp.setOnClickListener { cancelRamp() }
        b.btnBrake.setOnClickListener {
            inRampRun = false
            val c = controller.command
            if (c.mode == DynoMode.Brake) {
                c.mode = DynoMode.NoLoad
                c.brakeClearRequests = 6 // pulse Speed bit ~0.3s to clear the PC brake warning
            } else {
                c.mode = DynoMode.Brake
            }
            beep()
            updateUi()
        }
        b.btnHandsfree.setOnClickListener { setMode(DynoMode.Handsfree) }
        b.btnSetBed.setOnClickListener { setMode(DynoMode.SetBed) }
    }

    private fun setMode(m: DynoMode) {
        inRampRun = false
        if (m == DynoMode.Load) controller.command.targetLoad = 0.0 // original EVENT_Load.initialise() zeroes requested load on entry
        controller.command.mode = m
        beep()
        updateUi()
    }

    /**
     * Speed the first press of a ramp run requests: whatever the dyno is already
     * doing, so the run starts from the operator's current speed rather than driving
     * to a preset. Falls back to the configured startSpeed when the dyno is stopped
     * (nothing to hold) or when the request is in engine/rpm terms — the received
     * state only carries road speed, so there is nothing to convert from. The rpm
     * gate matches DynoController.govern(). Mirrors MainViewModel.RampStartSpeed.
     */
    private fun rampStartSpeed(): Double {
        val c = controller.command
        val roadSpeed = c.speedControl != SpeedControl.Engine && c.speedUnits != SpeedUnit.Rpm
        val start = if (roadSpeed && controller.state.speedKph >= rampHoldMinKph)
            c.fromKph(controller.state.speedKph)
        else
            c.startSpeed
        return minOf(start, settings.maxForCurrentUnit())
    }

    /**
     * Step a ramp run. First press of Manual Ramp / Advanced locks the start speed
     * (Hold); each subsequent press advances the sequence; the final press ends the
     * run back to No Load. Mirrors MainViewModel.RunCycle in the PC app.
     */
    private fun runCycle(basic: Boolean) {
        val seq = if (basic) basicSeq else advancedSeq
        if (!inRampRun || runIsBasic != basic) {
            inRampRun = true
            runIsBasic = basic
            rampStep = 0 // first press: hold the current speed (Speed mode)
            controller.command.requestedSpeed = rampStartSpeed()
        } else if (rampStep >= seq.size - 1) {
            inRampRun = false
            controller.command.mode = DynoMode.NoLoad // end run -> free spin (coast)
            beep()
            updateUi()
            return
        } else {
            rampStep++
        }
        controller.command.mode = seq[rampStep].mode
        beep()
        updateUi()
    }

    /** Abort an in-progress ramp run and return to the main mode screen (coast / No Load). */
    private fun cancelRamp() {
        inRampRun = false
        controller.command.mode = DynoMode.NoLoad
        beep()
        updateUi()
    }

    private fun wireSpeedButtons() {
        b.btnSpeedDecL.setOnClickListener { nudge(-settings.activeSpeedIncLarge()) }
        b.btnSpeedDecS.setOnClickListener { nudge(-settings.activeSpeedIncSmall()) }
        b.btnSpeedIncS.setOnClickListener { nudge(settings.activeSpeedIncSmall()) }
        b.btnSpeedIncL.setOnClickListener { nudge(settings.activeSpeedIncLarge()) }
        b.btnSpeedMode.setOnClickListener { cycleSpeedMode() }
    }

    private fun wireLoadButtons() {
        b.btnLoadDecL.setOnClickListener { nudgeLoad("L-") }
        b.btnLoadDecS.setOnClickListener { nudgeLoad("S-") }
        b.btnLoadIncS.setOnClickListener { nudgeLoad("S+") }
        b.btnLoadIncL.setOnClickListener { nudgeLoad("L+") }
        b.btnLoadRes.setOnClickListener { toggleLoadResolution() }
    }

    /**
     * Load nudge, faithful to the original's two-axis behaviour: the small buttons
     * (S+/S-) step by the small increment; the large buttons (L+/L-) snap to the
     * nearest large-increment grid line first, then step (ceil/floor) so repeated
     * presses walk clean multiples. Fine/Coarse selects the increment pair.
     */
    private fun nudgeLoad(token: String) {
        val c = controller.command
        var v = c.targetLoad
        if (token[0] == 'S') {
            v += if (token.endsWith("-")) -settings.loadSmallInc() else settings.loadSmallInc()
        } else { // large: snap to grid (original activate_Up/DownButton)
            val lg = settings.loadLargeInc()
            v = if (token.endsWith("-")) {
                val num = floor(v / lg) * lg
                if (v == num) num - lg else num
            } else {
                val num = ceil(v / lg) * lg
                if (v == num) num + lg else num
            }
        }
        // Clamp to [min, max] and round to 0.1 % (matches the original SYS_Unit).
        c.targetLoad = (round(v.coerceIn(settings.loadMin, settings.loadMax) * 10.0) / 10.0)
        updateUi()
    }

    /** Flip the load nudge resolution (fine 0.1/5 <-> coarse 1/10) and persist it. */
    private fun toggleLoadResolution() {
        settings.loadResolutionFine = !settings.loadResolutionFine
        SettingsStore.save(this, settings)
        applySettings() // refreshes the load button labels + Res text
        beep()
    }

    private fun wireRampButtons() {
        b.btnRampDecL.setOnClickListener { nudgeRamp(-10.0) }
        b.btnRampDecS.setOnClickListener { nudgeRamp(-1.0) }
        b.btnRampIncS.setOnClickListener { nudgeRamp(1.0) }
        b.btnRampIncL.setOnClickListener { nudgeRamp(10.0) }
    }

    private fun wireBalanceButtons() {
        b.btnBalDec.setOnClickListener { nudgeBalance(-1.0) }
        b.btnBalInc.setOnClickListener { nudgeBalance(1.0) }
    }

    private fun wireActions() {
        b.btnSnapshot.setOnClickListener { controller.command.snapshotRequests++; beep() }
        b.btnGraphClear.setOnClickListener { controller.command.graphClearRequests++; beep() }
        b.btnSwap.setOnClickListener { controller.command.swapScreenRequests++; beep() }
    }

    private fun nudge(amount: Double) {
        val c = controller.command
        c.requestedSpeed = (c.requestedSpeed + amount).coerceIn(0.0, settings.maxForCurrentUnit())
    }

    private fun nudgeRamp(amount: Double) {
        if (controller.state.shootoutActive) return // PC owns the ramp during a shootout
        val c = controller.command
        c.rampRate = (c.rampRate + amount).coerceAtLeast(0.0)
    }

    private fun nudgeBalance(amount: Double) {
        val c = controller.command
        c.balance = (c.balance + amount).coerceIn(-20.0, 20.0)
        c.balanceActive = true
    }

    // Set Bed is a dead-man: the bed drives only while a button is held. ACTION_DOWN
    // starts the move toward min (Shorten), max (Lengthen) or the PC target (Go);
    // ACTION_UP/CANCEL stops it. Mirrors the original EVENT_SetBed.
    @SuppressLint("ClickableViewAccessibility")
    private fun wireBedButtons() {
        fun listener(which: String) = View.OnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { bedMoveStart(which); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { bedMoveStop(); v.performClick(); true }
                else -> false
            }
        }
        b.btnBedShorten.setOnTouchListener(listener("Shorten"))
        b.btnBedLengthen.setOnTouchListener(listener("Lengthen"))
        b.btnBedGo.setOnTouchListener(listener("Go"))
    }

    private fun bedMoveStart(which: String) {
        val target = when (which) {
            "Shorten" -> settings.bedLengthMin
            "Lengthen" -> settings.bedLengthMax
            "Go" -> controller.state.bedTargetPosition
            else -> 0.0
        }
        if (target <= 0.0) return // nothing valid to drive to (e.g. Go with no PC target yet)
        controller.command.bedRequestedPosition = target
        controller.command.bedMoveActive = true
    }

    private fun bedMoveStop() { controller.command.bedMoveActive = false }

    /**
     * Cycle the main-screen speed mode: km/h -> mph -> engine -> km/h. Each is a
     * (control, unit) pair: km/h and mph are road-speed control; "engine" switches
     * the dyno into RPM control (the DigitalInputs CommandRpm bit) and displays rpm.
     * Persists like a settings change and re-applies so the governor, TX encoder,
     * increments and labels all follow. Mirrors MainViewModel.CycleSpeedMode.
     */
    /** Round a speed UP to a clean value for its unit: nearest 100 rpm, else nearest 10. */
    private fun roundUpForUnit(v: Double, u: SpeedUnit): Double {
        val step = if (u == SpeedUnit.Rpm) 100.0 else 10.0
        return ceil(v / step) * step
    }

    /** The default/start target (physical equivalent of 80 km/h) in the active unit. */
    private fun defaultTargetForCurrentUnit(): Double = minOf(
        roundUpForUnit(controller.command.convertSpeed(80.0, SpeedUnit.Kph, settings.speedUnits), settings.speedUnits),
        settings.maxForCurrentUnit())

    private fun cycleSpeedMode() {
        // Gated to a near standstill; the button is also disabled above the limit.
        if (controller.state.speedKph >= speedModeChangeMaxKph) return
        val oldUnit = settings.speedUnits
        val (ctrl, unit) = when {
            settings.speedControl == SpeedControl.Road && settings.speedUnits == SpeedUnit.Kph ->
                SpeedControl.Road to SpeedUnit.Mph
            settings.speedControl == SpeedControl.Road && settings.speedUnits == SpeedUnit.Mph ->
                SpeedControl.Engine to SpeedUnit.Rpm
            else -> SpeedControl.Road to SpeedUnit.Kph // engine (or any) -> km/h
        }
        settings.speedControl = ctrl
        settings.speedUnits = unit

        // Re-express the target in the new unit preserving the physical roller speed
        // (as the original did), then round UP to a clean value. Stops "80" carrying
        // over as 80 rpm; 80 km/h -> ~1941 -> 2000 rpm.
        if (unit != oldUnit) {
            val c = controller.command
            val converted = c.convertSpeed(c.requestedSpeed, oldUnit, unit)
            c.requestedSpeed = minOf(roundUpForUnit(converted, unit), settings.maxForCurrentUnit())
        }

        SettingsStore.save(this, settings)
        applySettings() // pushes to command, clamps target, refreshes unit/increment labels + button text
        beep()
    }

    // Hand the connection to the foreground service, which owns DynoLink and keeps
    // it alive (with auto-reconnect) across screen-off / app-switch / Activity death.
    private fun connect() {
        if (btAdapter() == null) { b.txtStatus.text = "No Bluetooth adapter"; return }
        if (!hasConnectPermission()) { ensureBtPermissionThenList(); b.txtStatus.text = "Grant Bluetooth permission, then Connect"; return }
        val mac = selectedMac ?: settings.edmAddress
        val svc = Intent(this, DynoConnectionService::class.java).putExtra(DynoConnectionService.EXTRA_MAC, mac)
        ContextCompat.startForegroundService(this, svc)
        b.txtStatus.text = "Connecting…"
    }

    private fun disconnect() {
        DynoLink.disconnect()
        stopService(Intent(this, DynoConnectionService::class.java))
        b.txtStatus.text = "Disconnected"
    }

    private fun maybeAutoConnect() {
        if (settings.autoConnectAtStartup && !DynoLink.wantConnected && hasConnectPermission()) connect()
    }

    // ---- Bluetooth helpers ----
    private fun btAdapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureBtPermissionThenList() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasConnectPermission()) {
            requestConnectPerm.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            populateDevices()
        }
    }

    private fun populateDevices() {
        val labels = mutableListOf<String>()
        val macs = mutableListOf<String>()
        // The configured EDM is always connectable by MAC (no pairing needed) — list it first.
        labels.add("EDM (configured)  ${settings.edmAddress}")
        macs.add(settings.edmAddress)
        val adapter = btAdapter()
        if (adapter != null && hasConnectPermission()) {
            try {
                for (d in adapter.bondedDevices) {
                    if (d.address.equals(settings.edmAddress, ignoreCase = true)) continue
                    labels.add("${d.name ?: "?"}  (${d.address})  paired")
                    macs.add(d.address)
                }
            } catch (_: SecurityException) {
            }
        }
        deviceMacs = macs

        val ad = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        b.spinnerDevices.adapter = ad
        b.spinnerDevices.setSelection(0) // default to the configured EDM
        selectedMac = macs.getOrNull(0)
        b.spinnerDevices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { selectedMac = deviceMacs.getOrNull(pos) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun updateUi() {
        val s = controller.state
        val c = controller.command

        b.txtStatus.text = DynoLink.status
        b.txtSpeed.text = "%.1f".format(s.speedKph)
        b.txtSet.text = fmt(c.requestedSpeed)
        b.txtTarget.text = fmt(c.requestedSpeed)
        b.txtRamp.text = fmt(c.rampRate)
        // Speed-mode change is gated to a near standstill (see cycleSpeedMode).
        b.btnSpeedMode.isEnabled = s.speedKph < speedModeChangeMaxKph

        // Ramp-run locking: during a Manual Ramp / Advanced cycle, hide the whole mode
        // panel and show only the active stepper button so the operator steps the run
        // through to completion. When it ends (returns to No Load) the panel reappears.
        // (Mirrors the PC app's ShowModeButtons / ShowBrakeButton visibility rules.)
        if (inRampRun) {
            b.modeGrid.visibility = View.GONE
            b.conditionalModeRow.visibility = View.GONE
            b.btnActiveRamp.visibility = View.VISIBLE
            b.btnCancelRamp.visibility = View.VISIBLE
            b.btnActiveRamp.text = activeSeq[rampStep].next
            b.txtMode.text = "Mode: ${if (runIsBasic) "Manual Ramp" else "Advanced"} — ${activeSeq[rampStep].state}"
        } else {
            b.modeGrid.visibility = View.VISIBLE
            b.btnActiveRamp.visibility = View.GONE
            b.btnCancelRamp.visibility = View.GONE
            b.conditionalModeRow.visibility = View.VISIBLE
            // Set Bed only for 4WD; Brake only when it can engage (below the interlock) or is already on.
            b.btnSetBed.visibility = if (c.is4Wd) View.VISIBLE else View.GONE
            val brakeVisible = c.mode == DynoMode.Brake || s.speedKph < brakeEngageMaxKph
            b.btnBrake.visibility = if (brakeVisible) View.VISIBLE else View.GONE
            b.txtMode.text = "Mode: ${c.mode}"
        }
        b.btnBrake.text = if (c.mode == DynoMode.Brake) "Brake: ON" else "Brake"

        // Target-load panel: shown only in fixed-Load mode (hidden during a ramp cycle).
        val loadActive = c.mode == DynoMode.Load && !inRampRun
        b.loadRow.visibility = if (loadActive) View.VISIBLE else View.GONE
        if (loadActive) b.txtLoad.text = fmt(c.targetLoad)

        // Set Bed hold-to-move panel: shown only while in Set Bed mode on a 4WD bed.
        val bedActive = c.mode == DynoMode.SetBed && c.is4Wd && !inRampRun
        b.bedRow.visibility = if (bedActive) View.VISIBLE else View.GONE
        if (bedActive) {
            b.btnBedGo.isEnabled = s.bedTargetPosition > 0.0 // Go needs a PC target to drive to
            b.txtBed.text = "bed act ${bedFmt(s.bedCurrentPosition)}   tgt ${bedFmt(s.bedTargetPosition)}"
        }

        // Shootout banner + ramp-rate read-out (PC owns the rate during a shootout).
        val pcKphs = s.pcRampRate * c.rollerCircumference * 3.6
        val rampText: String
        if (s.shootoutActive) {
            b.txtShootout.visibility = View.VISIBLE
            b.txtShootout.text = "🔒 ${s.shootoutMode.uppercase()} — PC controlling the ramp rate, locked at ${"%.0f".format(pcKphs)} km/h/s"
            b.rampRow.alpha = 0.4f
            rampText = "${"%.0f".format(pcKphs)} km/h/s — PC (${s.shootoutMode})"
        } else {
            b.txtShootout.visibility = View.GONE
            b.rampRow.alpha = 1.0f
            rampText = "${"%.0f".format(c.rampRate)} km/h/s"
        }

        // 4WD-only read-outs.
        b.balanceRow.visibility = if (c.is4Wd) View.VISIBLE else View.GONE
        b.txtBalance.text = "%.1f".format(c.balance)

        val age = if (s.lastRxMillis == 0L) "no data" else "${System.currentTimeMillis() - s.lastRxMillis} ms"
        val sb = StringBuilder()
        sb.append("revs/s %.3f   raw %d\n".format(s.speedRevsPerSec, s.rawSpeedMaster))
        sb.append("ramp $rampText\n")
        sb.append("brake ${s.brakeState}   HFR ${s.handsfreeState}\n")
        sb.append("trans ${transLabel(s.transmission)}")
        if (c.is4Wd) {
            sb.append("   4WD ${if (s.configReceived) (if (s.in4wdMode) "Yes" else "No") else "—"}\n")
            sb.append("bed act ${bedFmt(s.bedCurrentPosition)}   tgt ${bedFmt(s.bedTargetPosition)}\n")
        } else {
            sb.append("\n")
        }
        sb.append("Pit ${dot(s.pitControllerOnline)}  PC ${dot(s.pcOnline)}  Bed ${dot(s.bedCtrlOnline)}   rx $age")
        b.txtDetail.text = sb.toString()

        b.txtDiag.text = "TX ${controller.txPackets}  RX ${controller.rxPackets}  err ${controller.rxErrors}  comms-loss ${controller.commsLossEvents}\n" +
            "last TX: ${controller.lastTxHex}\nlast RX: ${controller.lastRxHex}"

        // Packet-types-seen inspector.
        if (settings.showPacketsSeen) {
            b.txtSeen.visibility = View.VISIBLE
            b.txtSeen.text = controller.statsView.entries.sortedBy { it.key }.joinToString("\n") { (key, st) ->
                val id = (key shr 16) and 0xFF
                val cmd = (key shr 8) and 0xFF
                val p0 = key and 0xFF
                "ID=%02X CMD=%02X P0=%02X  val=%6d [%d..%d]  x%-5d  %s".format(
                    id, cmd, p0, st.last, st.min, st.max, st.count, labelFor(id, cmd, p0))
            }
        } else {
            b.txtSeen.visibility = View.GONE
        }

        // Packet log.
        if (settings.showPacketLog) {
            b.txtLog.visibility = View.VISIBLE
            b.txtLog.text = controller.logSnapshot().takeLast(120).joinToString("\n")
        } else {
            b.txtLog.visibility = View.GONE
        }
    }

    private fun beep() {
        if (!settings.audibleFeedback) return
        try { tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 80) } catch (_: Exception) {}
    }

    private fun dot(online: Boolean) = if (online) "●" else "○"
    private fun bedFmt(m: Double) = if (m > 0) "%.3f m".format(m) else "—"

    private fun transLabel(t: TransmissionType) = when (t) {
        TransmissionType.Unknown -> "—"
        TransmissionType.Standard2Wd -> "2WD standard"
        TransmissionType.Locked4Wd -> "4WD locked"
        TransmissionType.CenterDiff4Wd -> "4WD centre diff"
        TransmissionType.CenterViscous4Wd -> "4WD centre viscous"
        TransmissionType.ComputerDogClutch4Wd -> "4WD computer dog clutch"
        TransmissionType.TractionStability4Wd -> "4WD traction/stability"
        TransmissionType.FrontViscous4Wd -> "4WD front viscous"
    }

    // Mirrors the Windows LabelFor — names the packet types we know, "unknown" otherwise.
    private fun labelFor(id: Int, cmd: Int, p0: Int): String {
        val src = id and 0xF0
        if (cmd == 0x0F && p0 == 0x0A && src == 0x20) return "roller speed (master)"
        if (cmd == 0x0F && p0 == 0x5B && src == 0x70) return "roller speed (slave)"
        if (cmd == 0x0F && p0 == 0x1A && src == 0x20) return "pit brake (master)"
        if (cmd == 0x0F && p0 == 0x56 && src == 0x70) return "pit brake (slave)"
        if (cmd == 0x25) return "HFR / shootout status"
        if (cmd == 0xA0 && p0 == 0x32) return "bed current pos"
        if (cmd == 0xA0 && p0 == 0x31) return "bed target pos"
        if (cmd == 0xA0 && p0 == 0x67) return "PC ramp rate"
        if (cmd == 0x6F && p0 == 0x60) return "speed-unit cmd"
        if (cmd == 0x2F) return "set config"
        if (cmd == 0x3F) return "get config"
        if (cmd == 0xFB) return "ping"
        if (cmd == 0xE5) return "FW ctrl"
        if (cmd == 0x05 && p0 == 0xA0) return "shootout start speed"
        if (cmd == 0x05 && p0 == 0xC0) return "shootout ramp rate"
        if (cmd == 0x05 && p0 == 0xD0) return "shootout finish speed"
        return "unknown"
    }

    private fun fmt(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".format(v)
}
