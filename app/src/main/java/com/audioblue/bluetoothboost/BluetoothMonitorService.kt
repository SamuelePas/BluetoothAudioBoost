package com.audioblue.bluetoothboost

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import kotlin.math.log10
import kotlin.math.roundToInt

class BluetoothMonitorService : LifecycleService() {

    companion object {
        private const val TAG = "BTBoostService"
        private const val CHANNEL_ID = "bt_boost_channel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_UPDATE_BOOST = "ACTION_UPDATE_BOOST"
        const val ACTION_UPDATE_DEVICE = "ACTION_UPDATE_DEVICE"
        const val EXTRA_BOOST_PERCENT = "boost_percent"

        // Stato visibile globalmente per la MainActivity
        @Volatile var isRunning = false
        @Volatile var isBoostActive = false
        @Volatile var connectedDeviceName: String? = null
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var audioManager: AudioManager
    private lateinit var telephonyManager: TelephonyManager

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var savedMediaVolume: Int = -1
    private var targetDeviceName: String? = null
    private var boostPercent: Int = 130

    // Stato chiamata attiva
    private var isCallActive = false

    // ─── BroadcastReceiver per eventi Bluetooth A2DP ────────────────────────
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            android.bluetooth.BluetoothDevice.EXTRA_DEVICE,
                            android.bluetooth.BluetoothDevice::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                    }
                    val deviceName = try { device?.name } catch (e: SecurityException) { null }
                    Log.d(TAG, "Device connected: $deviceName")
                    onBluetoothDeviceConnected(deviceName)
                }

                android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            android.bluetooth.BluetoothDevice.EXTRA_DEVICE,
                            android.bluetooth.BluetoothDevice::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                    }
                    val deviceName = try { device?.name } catch (e: SecurityException) { null }
                    Log.d(TAG, "Device disconnected: $deviceName")
                    onBluetoothDeviceDisconnected(deviceName)
                }

                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    // Cuffie disconnesse improvvisamente – ripristina
                    Log.d(TAG, "Audio becoming noisy – restoring volume")
                    disableBoost()
                }
            }
        }
    }

    // ─── PhoneStateListener (API < 31) ──────────────────────────────────────
    @Suppress("DEPRECATION")
    private val phoneStateListener = object : PhoneStateListener() {
        @Deprecated("Deprecated in Java")
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            handleCallState(state)
        }
    }

    // ─── TelephonyCallback (API 31+) ────────────────────────────────────────
    private val telephonyCallback: Any? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleCallState(state)
                }
            }
        } else null
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        createNotificationChannel()
        registerReceivers()
        registerPhoneListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> {
                Log.d(TAG, "Service START")
                loadPrefs()
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_monitoring)))
                isRunning = true
                checkCurrentlyConnected()
            }
            ACTION_STOP -> {
                Log.d(TAG, "Service STOP")
                disableBoost()
                isRunning = false
                connectedDeviceName = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_UPDATE_BOOST -> {
                boostPercent = intent.getIntExtra(EXTRA_BOOST_PERCENT, boostPercent)
                prefs.edit().putInt(MainActivity.KEY_BOOST_PERCENT, boostPercent).apply()
                if (isBoostActive && !isCallActive) {
                    applyBoost()
                }
            }
            ACTION_UPDATE_DEVICE -> {
                loadPrefs()
                // Se il dispositivo corrente corrisponde ora al nuovo nome salvato
                checkCurrentlyConnected()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return Binder()
    }

    override fun onDestroy() {
        super.onDestroy()
        disableBoost()
        unregisterReceivers()
        unregisterPhoneListener()
        isRunning = false
        isBoostActive = false
        connectedDeviceName = null
        Log.d(TAG, "Service destroyed")
    }

    // ─── Logica principale ──────────────────────────────────────────────────

    private fun loadPrefs() {
        targetDeviceName = prefs.getString(MainActivity.KEY_DEVICE_NAME, null)
        boostPercent = prefs.getInt(MainActivity.KEY_BOOST_PERCENT, 130)
        Log.d(TAG, "Loaded prefs – target: $targetDeviceName, boost: $boostPercent%")
    }

    private fun onBluetoothDeviceConnected(deviceName: String?) {
        loadPrefs()
        if (deviceName == null || targetDeviceName.isNullOrEmpty()) return
        if (deviceName == targetDeviceName) {
            Log.d(TAG, "Target device connected – activating boost")
            connectedDeviceName = deviceName
            if (!isCallActive) {
                enableBoost()
            }
            updateNotification(getString(R.string.notif_boost_active, deviceName))
        }
    }

    private fun onBluetoothDeviceDisconnected(deviceName: String?) {
        if (deviceName == targetDeviceName || connectedDeviceName == deviceName) {
            Log.d(TAG, "Target device disconnected – restoring volume")
            disableBoost()
            connectedDeviceName = null
            updateNotification(getString(R.string.notif_monitoring))
        }
    }

    /** Verifica se il dispositivo target è già connesso all'avvio del servizio */
    private fun checkCurrentlyConnected() {
        loadPrefs()
        if (targetDeviceName.isNullOrEmpty()) return

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter ?: return

        try {
            // Controlla profili A2DP e HEADSET
            val profiles = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
            for (profile in profiles) {
                adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                        val connected = proxy.connectedDevices
                        for (device in connected) {
                            val name = try { device.name } catch (e: SecurityException) { null }
                            if (name == targetDeviceName) {
                                Log.d(TAG, "Already connected to target: $name")
                                connectedDeviceName = name
                                if (!isCallActive) enableBoost()
                                updateNotification(getString(R.string.notif_boost_active, name))
                            }
                        }
                        adapter.closeProfileProxy(profileId, proxy)
                    }
                    override fun onServiceDisconnected(profileId: Int) {}
                }, profile)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception checking connected devices", e)
        }
    }

    // ─── Boost audio ────────────────────────────────────────────────────────

    private fun enableBoost() {
        // 1. Salva volume corrente
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (savedMediaVolume < 0) {
            savedMediaVolume = currentVol
            Log.d(TAG, "Saved media volume: $savedMediaVolume")
        }

        // 2. Porta il volume di sistema al massimo
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)

        // 3. Applica LoudnessEnhancer per boost aggiuntivo
        applyBoost()

        isBoostActive = true
        Log.d(TAG, "Boost enabled at $boostPercent%")
    }

    private fun applyBoost() {
        try {
            loudnessEnhancer?.release()
            loudnessEnhancer = null

            val gainMb = percentToMillibels(boostPercent)
            if (gainMb > 0) {
                // Sessione 0 = effetto globale su tutto il mix audio
                val enhancer = LoudnessEnhancer(0)
                enhancer.setTargetGain(gainMb)
                enhancer.enabled = true
                loudnessEnhancer = enhancer
                Log.d(TAG, "LoudnessEnhancer applied: ${gainMb}mB gain")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply LoudnessEnhancer", e)
        }
    }

    private fun disableBoost() {
        try {
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
            loudnessEnhancer = null
            Log.d(TAG, "LoudnessEnhancer released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing LoudnessEnhancer", e)
        }

        // Ripristina volume salvato
        if (savedMediaVolume >= 0) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMediaVolume, 0)
                Log.d(TAG, "Restored media volume to $savedMediaVolume")
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring volume", e)
            }
            savedMediaVolume = -1
        }

        isBoostActive = false
    }

    /** Converte percentuale (100–160) in millibel per LoudnessEnhancer */
    private fun percentToMillibels(percent: Int): Int {
        if (percent <= 100) return 0
        val db = 20.0 * log10(percent / 100.0)
        return (db * 100).roundToInt()  // 1 dB = 100 mB
    }

    // ─── Gestione chiamate ──────────────────────────────────────────────────

    private fun handleCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (!isCallActive) {
                    isCallActive = true
                    Log.d(TAG, "Call active – pausing boost, routing audio to earpiece")
                    pauseBoostForCall()
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (isCallActive) {
                    isCallActive = false
                    Log.d(TAG, "Call ended – resuming boost if device still connected")
                    resumeBoostAfterCall()
                }
            }
        }
    }

    private fun pauseBoostForCall() {
        try {
            // Disabilita temporaneamente il boost senza azzerare savedMediaVolume
            loudnessEnhancer?.enabled = false

            // Ripristina volume normale durante la chiamata
            if (savedMediaVolume >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMediaVolume, 0)
            }

            // Tenta di disabilitare BT SCO (audio chiamata → altoparlante telefono)
            if (audioManager.isBluetoothScoAvailableOffCall) {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error pausing boost for call", e)
        }
    }

    private fun resumeBoostAfterCall() {
        // Se il dispositivo target è ancora connesso, riattiva il boost
        if (connectedDeviceName != null && isRunning) {
            try {
                Thread.sleep(1000) // Attendi che la chiamata finisca completamente
            } catch (e: InterruptedException) { /* ignore */ }
            enableBoost()
        }
    }

    // ─── Receivers & Listeners ──────────────────────────────────────────────

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }
        Log.d(TAG, "Bluetooth receiver registered")
    }

    private fun unregisterReceivers() {
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Receiver already unregistered")
        }
    }

    @Suppress("DEPRECATION")
    private fun registerPhoneListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = telephonyCallback
                if (cb is TelephonyCallback) {
                    telephonyManager.registerTelephonyCallback(mainExecutor, cb)
                }
            } else {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering phone listener", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun unregisterPhoneListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = telephonyCallback
                if (cb is TelephonyCallback) {
                    telephonyManager.unregisterTelephonyCallback(cb)
                }
            } else {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering phone listener", e)
        }
    }

    // ─── Notifiche ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
