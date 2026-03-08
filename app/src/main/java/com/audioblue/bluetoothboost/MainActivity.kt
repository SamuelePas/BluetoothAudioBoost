package com.audioblue.bluetoothboost

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
        const val ACTION_UI_UPDATE = "com.audioblue.bluetoothboost.UI_UPDATE"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var tvSavedDevice: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvBoostValue: TextView
    private lateinit var sliderBoost: Slider
    private lateinit var btnSelectDevice: Button
    private lateinit var switchService: SwitchMaterial
    private lateinit var cardStatus: MaterialCardView

    // Evita loop nel listener dello switch
    private var isUpdatingSwitch = false

    // Receiver per aggiornamenti live dal servizio
    private val uiUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshStatusCard()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        initViews()
        requestAllPermissions()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(ACTION_UI_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(uiUpdateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(uiUpdateReceiver, filter)
        }
        refreshAll()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(uiUpdateReceiver) } catch (e: Exception) { }
    }

    // ─── Init ────────────────────────────────────────────────────────────────

    private fun initViews() {
        tvSavedDevice   = findViewById(R.id.tv_saved_device)
        tvStatus        = findViewById(R.id.tv_status)
        tvBoostValue    = findViewById(R.id.tv_boost_value)
        sliderBoost     = findViewById(R.id.slider_boost)
        btnSelectDevice = findViewById(R.id.btn_select_device)
        switchService   = findViewById(R.id.switch_service)
        cardStatus      = findViewById(R.id.card_status)

        // Slider esteso 100–200%
        sliderBoost.valueFrom = 100f
        sliderBoost.valueTo   = 300f
        sliderBoost.stepSize  = 1f
        sliderBoost.value     = prefs.getInt(KEY_BOOST_PERCENT, DEFAULT_BOOST).toFloat()
        updateBoostLabel(sliderBoost.value.toInt())

        sliderBoost.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val percent = value.toInt()
                prefs.edit().putInt(KEY_BOOST_PERCENT, percent).apply()
                updateBoostLabel(percent)
                if (BluetoothMonitorService.isRunning) {
                    val i = Intent(this, BluetoothMonitorService::class.java)
                    i.action = BluetoothMonitorService.ACTION_UPDATE_BOOST
                    i.putExtra(BluetoothMonitorService.EXTRA_BOOST_PERCENT, percent)
                    startService(i)
                }
            }
        }

        btnSelectDevice.setOnClickListener { showDeviceSelectionDialog() }

        switchService.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitch) return@setOnCheckedChangeListener
            prefs.edit().putBoolean(KEY_SERVICE_ENABLED, isChecked).apply()
            if (isChecked) startMonitorService() else stopMonitorService()
        }
    }

    // ─── Refresh UI ──────────────────────────────────────────────────────────

    private fun refreshAll() {
        val savedDevice = prefs.getString(KEY_DEVICE_NAME, null)
        tvSavedDevice.text = if (savedDevice.isNullOrEmpty())
            getString(R.string.no_device_saved) else savedDevice

        sliderBoost.value = prefs.getInt(KEY_BOOST_PERCENT, DEFAULT_BOOST).toFloat()
        updateBoostLabel(sliderBoost.value.toInt())
        refreshStatusCard()
    }

    private fun refreshStatusCard() {
        // Aggiorna lo switch rispecchiando lo stato reale del servizio
        isUpdatingSwitch = true
        switchService.isChecked = BluetoothMonitorService.isRunning
        isUpdatingSwitch = false

        when {
            !BluetoothMonitorService.isRunning -> {
                tvStatus.text = getString(R.string.status_service_off)
                cardStatus.setCardBackgroundColor(getColor(R.color.status_off))
            }
            BluetoothMonitorService.isBoostActive -> {
                tvStatus.text = getString(
                    R.string.status_boost_active,
                    BluetoothMonitorService.connectedDeviceName ?: ""
                )
                cardStatus.setCardBackgroundColor(getColor(R.color.status_boost))
            }
            else -> {
                tvStatus.text = getString(R.string.status_monitoring)
                cardStatus.setCardBackgroundColor(getColor(R.color.status_monitoring))
            }
        }
    }

    private fun updateBoostLabel(percent: Int) {
        if (percent <= 100) {
            tvBoostValue.text = "Nessun boost (100%)"
        } else {
            val db = (20.0 * log10(percent / 100.0) * 10).roundToInt() / 10.0
            tvBoostValue.text = "+${String.format("%.1f", db)} dB  (${percent}%)"
        }
    }

    // ─── Service control ─────────────────────────────────────────────────────

    private fun startMonitorService() {
        val savedDevice = prefs.getString(KEY_DEVICE_NAME, null)
        if (savedDevice.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.select_device_first), Toast.LENGTH_SHORT).show()
            isUpdatingSwitch = true
            switchService.isChecked = false
            isUpdatingSwitch = false
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
        // Piccolo delay: il servizio impiega un momento ad avviarsi
        cardStatus.postDelayed({ refreshStatusCard() }, 500)
        Toast.makeText(this, getString(R.string.service_started), Toast.LENGTH_SHORT).show()
    }

    private fun stopMonitorService() {
        val intent = Intent(this, BluetoothMonitorService::class.java)
        intent.action = BluetoothMonitorService.ACTION_STOP
        startService(intent)
        cardStatus.postDelayed({ refreshStatusCard() }, 300)
        Toast.makeText(this, getString(R.string.service_stopped), Toast.LENGTH_SHORT).show()
    }

    // ─── Device selection ────────────────────────────────────────────────────

    private fun showDeviceSelectionDialog() {
        if (!hasBluetoothPermissions()) { requestAllPermissions(); return }
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, getString(R.string.bluetooth_not_enabled), Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            return
        }
        val bondedDevices = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
                    adapter.bondedDevices?.toList() ?: emptyList()
                else emptyList()
            } else {
                adapter.bondedDevices?.toList() ?: emptyList()
            }
        } catch (e: SecurityException) { emptyList() }

        if (bondedDevices.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_paired_devices), Toast.LENGTH_LONG).show()
            return
        }
        val names = bondedDevices.mapNotNull { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_device))
            .setItems(names) { _, which ->
                val selected = names[which]
                prefs.edit().putString(KEY_DEVICE_NAME, selected).apply()
                tvSavedDevice.text = selected
                Toast.makeText(this, getString(R.string.device_saved, selected), Toast.LENGTH_SHORT).show()
                if (BluetoothMonitorService.isRunning) {
                    val i = Intent(this, BluetoothMonitorService::class.java)
                    i.action = BluetoothMonitorService.ACTION_UPDATE_DEVICE
                    startService(i)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ─── Permissions ─────────────────────────────────────────────────────────

    private fun hasBluetoothPermissions() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        else
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED

    private fun requestAllPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty())
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERM_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST_CODE && grantResults.any { it != PackageManager.PERMISSION_GRANTED })
            Toast.makeText(this, getString(R.string.permissions_required), Toast.LENGTH_LONG).show()
    }
}
