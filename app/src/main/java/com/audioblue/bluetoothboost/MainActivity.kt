package com.audioblue.bluetoothboost

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.math.log10
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "BluetoothBoostPrefs"
        const val KEY_DEVICE_NAME = "saved_device_name"
        const val KEY_BOOST_PERCENT = "boost_percent"
        const val KEY_SERVICE_ENABLED = "service_enabled"
        const val PERM_REQUEST_CODE = 100
        const val DEFAULT_BOOST = 130
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var tvSavedDevice: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvBoostValue: TextView
    private lateinit var sliderBoost: Slider
    private lateinit var btnSelectDevice: Button
    private lateinit var switchService: SwitchMaterial
    private lateinit var cardStatus: MaterialCardView

    private var pairedDeviceNames = listOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        initViews()
        requestAllPermissions()
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun initViews() {
        tvSavedDevice = findViewById(R.id.tv_saved_device)
        tvStatus = findViewById(R.id.tv_status)
        tvBoostValue = findViewById(R.id.tv_boost_value)
        sliderBoost = findViewById(R.id.slider_boost)
        btnSelectDevice = findViewById(R.id.btn_select_device)
        switchService = findViewById(R.id.switch_service)
        cardStatus = findViewById(R.id.card_status)

        // Slider: range 100–160 %
        sliderBoost.valueFrom = 100f
        sliderBoost.valueTo = 160f
        sliderBoost.stepSize = 1f
        sliderBoost.value = prefs.getInt(KEY_BOOST_PERCENT, DEFAULT_BOOST).toFloat()

        updateBoostLabel(sliderBoost.value.toInt())

        sliderBoost.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val percent = value.toInt()
                prefs.edit().putInt(KEY_BOOST_PERCENT, percent).apply()
                updateBoostLabel(percent)
                // Notifica il servizio del cambio di boost
                if (BluetoothMonitorService.isRunning) {
                    val intent = Intent(this, BluetoothMonitorService::class.java)
                    intent.action = BluetoothMonitorService.ACTION_UPDATE_BOOST
                    intent.putExtra(BluetoothMonitorService.EXTRA_BOOST_PERCENT, percent)
                    startService(intent)
                }
            }
        }

        btnSelectDevice.setOnClickListener {
            showDeviceSelectionDialog()
        }

        switchService.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SERVICE_ENABLED, isChecked).apply()
            if (isChecked) {
                startMonitorService()
            } else {
                stopMonitorService()
            }
            updateStatusCard()
        }
    }

    private fun updateBoostLabel(percent: Int) {
        val dbBoost = if (percent <= 100) 0.0
        else (20.0 * log10(percent / 100.0) * 10).roundToInt() / 10.0
        tvBoostValue.text = if (percent <= 100) "Nessun boost (100%)"
        else "+${String.format("%.1f", dbBoost)} dB  (${percent}%)"
    }

    private fun updateUI() {
        val savedDevice = prefs.getString(KEY_DEVICE_NAME, null)
        tvSavedDevice.text = if (savedDevice.isNullOrEmpty()) {
            getString(R.string.no_device_saved)
        } else {
            savedDevice
        }

        val serviceEnabled = prefs.getBoolean(KEY_SERVICE_ENABLED, false)
        switchService.isChecked = serviceEnabled && BluetoothMonitorService.isRunning
        sliderBoost.value = prefs.getInt(KEY_BOOST_PERCENT, DEFAULT_BOOST).toFloat()
        updateBoostLabel(sliderBoost.value.toInt())
        updateStatusCard()
    }

    private fun updateStatusCard() {
        val isActive = BluetoothMonitorService.isRunning
        val boostActive = BluetoothMonitorService.isBoostActive
        val connectedDevice = BluetoothMonitorService.connectedDeviceName

        when {
            !isActive -> {
                tvStatus.text = getString(R.string.status_service_off)
                cardStatus.setCardBackgroundColor(getColor(R.color.status_off))
            }
            boostActive && connectedDevice != null -> {
                tvStatus.text = getString(R.string.status_boost_active, connectedDevice)
                cardStatus.setCardBackgroundColor(getColor(R.color.status_boost))
            }
            else -> {
                tvStatus.text = getString(R.string.status_monitoring)
                cardStatus.setCardBackgroundColor(getColor(R.color.status_monitoring))
            }
        }
    }

    private fun showDeviceSelectionDialog() {
        if (!hasBluetoothPermissions()) {
            requestAllPermissions()
            return
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter

        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, getString(R.string.bluetooth_not_enabled), Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            return
        }

        val bondedDevices = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    adapter.bondedDevices?.toList() ?: emptyList()
                } else emptyList()
            } else {
                adapter.bondedDevices?.toList() ?: emptyList()
            }
        } catch (e: SecurityException) {
            emptyList()
        }

        if (bondedDevices.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_paired_devices), Toast.LENGTH_LONG).show()
            return
        }

        pairedDeviceNames = bondedDevices.mapNotNull { it.name }
        val namesArray = pairedDeviceNames.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_device))
            .setItems(namesArray) { _, which ->
                val selectedName = namesArray[which]
                prefs.edit().putString(KEY_DEVICE_NAME, selectedName).apply()
                tvSavedDevice.text = selectedName
                Toast.makeText(
                    this,
                    getString(R.string.device_saved, selectedName),
                    Toast.LENGTH_SHORT
                ).show()
                // Notifica il servizio del cambio di dispositivo
                if (BluetoothMonitorService.isRunning) {
                    val intent = Intent(this, BluetoothMonitorService::class.java)
                    intent.action = BluetoothMonitorService.ACTION_UPDATE_DEVICE
                    startService(intent)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun startMonitorService() {
        val savedDevice = prefs.getString(KEY_DEVICE_NAME, null)
        if (savedDevice.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.select_device_first), Toast.LENGTH_SHORT).show()
            switchService.isChecked = false
            prefs.edit().putBoolean(KEY_SERVICE_ENABLED, false).apply()
            return
        }

        val intent = Intent(this, BluetoothMonitorService::class.java)
        intent.action = BluetoothMonitorService.ACTION_START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, getString(R.string.service_started), Toast.LENGTH_SHORT).show()
    }

    private fun stopMonitorService() {
        val intent = Intent(this, BluetoothMonitorService::class.java)
        intent.action = BluetoothMonitorService.ACTION_STOP
        startService(intent)
        updateStatusCard()
        Toast.makeText(this, getString(R.string.service_stopped), Toast.LENGTH_SHORT).show()
    }

    // ─── Permessi ───────────────────────────────────────────────────────────

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestAllPermissions() {
        val permsNeeded = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) permsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) permsNeeded.add(Manifest.permission.BLUETOOTH_SCAN)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) permsNeeded.add(Manifest.permission.READ_PHONE_STATE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) permsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permsNeeded.toTypedArray(), PERM_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                Toast.makeText(this, getString(R.string.permissions_required), Toast.LENGTH_LONG).show()
            }
        }
    }
}
