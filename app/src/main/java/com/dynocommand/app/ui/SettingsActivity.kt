package com.dynocommand.app.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.dynocommand.app.databinding.ActivitySettingsBinding
import com.dynocommand.app.protocol.SpeedControl
import com.dynocommand.app.protocol.SpeedUnit
import com.dynocommand.app.settings.AppSettings
import com.dynocommand.app.settings.SettingsStore

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        val s = SettingsStore.load(this)

        val units = SpeedUnit.values()
        val controls = SpeedControl.values()
        b.spinnerUnits.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, units)
        b.spinnerControl.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, controls)

        // populate
        b.editEdm.setText(s.edmAddress)
        b.switchAutoConnect.isChecked = s.autoConnectAtStartup
        b.switchIs4wd.isChecked = s.is4Wd
        b.editBedMin.setText(s.bedLengthMin.toString())
        b.editBedMax.setText(s.bedLengthMax.toString())
        b.switchLogComms.isChecked = s.logCommsToFile
        b.switchShowLog.isChecked = s.showPacketLog
        b.switchShowSeen.isChecked = s.showPacketsSeen
        b.switchAudible.isChecked = s.audibleFeedback
        b.spinnerUnits.setSelection(units.indexOf(s.speedUnits))
        b.spinnerControl.setSelection(controls.indexOf(s.speedControl))
        b.editRoller.setText(s.rollerDiameter.toString())
        b.editGear.setText(s.gearRatio.toString())
        b.editMaxKph.setText(s.speedMaxKph.toString())
        b.editMaxMph.setText(s.speedMaxMph.toString())
        b.editMaxRpm.setText(s.speedMaxRpm.toString())
        b.editIncSmallKph.setText(s.speedIncSmallKph.toString())
        b.editIncLargeKph.setText(s.speedIncLargeKph.toString())
        b.editIncSmallMph.setText(s.speedIncSmallMph.toString())
        b.editIncLargeMph.setText(s.speedIncLargeMph.toString())
        b.editIncSmallRpm.setText(s.speedIncSmallRpm.toString())
        b.editIncLargeRpm.setText(s.speedIncLargeRpm.toString())
        b.editStartSpeed.setText(s.startSpeed.toString())
        b.editLoadMin.setText(s.loadMin.toString())
        b.editLoadMax.setText(s.loadMax.toString())
        b.editLoadFineSmall.setText(s.loadFineSmallInc.toString())
        b.editLoadFineLarge.setText(s.loadFineLargeInc.toString())
        b.editLoadCoarseSmall.setText(s.loadCoarseSmallInc.toString())
        b.editLoadCoarseLarge.setText(s.loadCoarseLargeInc.toString())
        b.switchLoadResFine.isChecked = s.loadResolutionFine

        b.btnSave.setOnClickListener {
            val out = AppSettings(
                edmAddress = b.editEdm.text.toString().trim().ifEmpty { "0012F3026E45" },
                autoConnectAtStartup = b.switchAutoConnect.isChecked,
                logCommsToFile = b.switchLogComms.isChecked,
                is4Wd = b.switchIs4wd.isChecked,
                bedLengthMin = d(b.editBedMin.text.toString(), s.bedLengthMin),
                bedLengthMax = d(b.editBedMax.text.toString(), s.bedLengthMax),
                showPacketLog = b.switchShowLog.isChecked,
                showPacketsSeen = b.switchShowSeen.isChecked,
                audibleFeedback = b.switchAudible.isChecked,
                speedUnits = units[b.spinnerUnits.selectedItemPosition],
                speedControl = controls[b.spinnerControl.selectedItemPosition],
                rollerDiameter = d(b.editRoller.text.toString(), s.rollerDiameter),
                gearRatio = d(b.editGear.text.toString(), s.gearRatio),
                speedMaxKph = d(b.editMaxKph.text.toString(), s.speedMaxKph),
                speedMaxMph = d(b.editMaxMph.text.toString(), s.speedMaxMph),
                speedMaxRpm = d(b.editMaxRpm.text.toString(), s.speedMaxRpm),
                speedIncSmallKph = d(b.editIncSmallKph.text.toString(), s.speedIncSmallKph),
                speedIncLargeKph = d(b.editIncLargeKph.text.toString(), s.speedIncLargeKph),
                speedIncSmallMph = d(b.editIncSmallMph.text.toString(), s.speedIncSmallMph),
                speedIncLargeMph = d(b.editIncLargeMph.text.toString(), s.speedIncLargeMph),
                speedIncSmallRpm = d(b.editIncSmallRpm.text.toString(), s.speedIncSmallRpm),
                speedIncLargeRpm = d(b.editIncLargeRpm.text.toString(), s.speedIncLargeRpm),
                startSpeed = d(b.editStartSpeed.text.toString(), s.startSpeed),
                loadMin = d(b.editLoadMin.text.toString(), s.loadMin),
                loadMax = d(b.editLoadMax.text.toString(), s.loadMax),
                loadFineSmallInc = d(b.editLoadFineSmall.text.toString(), s.loadFineSmallInc),
                loadFineLargeInc = d(b.editLoadFineLarge.text.toString(), s.loadFineLargeInc),
                loadCoarseSmallInc = d(b.editLoadCoarseSmall.text.toString(), s.loadCoarseSmallInc),
                loadCoarseLargeInc = d(b.editLoadCoarseLarge.text.toString(), s.loadCoarseLargeInc),
                loadResolutionFine = b.switchLoadResFine.isChecked
            )
            SettingsStore.save(this, out)
            finish()
        }
    }

    private fun d(text: String, fallback: Double): Double = text.trim().toDoubleOrNull() ?: fallback
}
