package com.audioblue.bluetoothboost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Receiver che gestisce:
 *  - Boot completato → riavvia il servizio se era attivo
 *  - ACL Connected/Disconnected (backup per quando l'app è in background)
 */
class BootAndBluetoothReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BTBoostReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                val prefs = context.getSharedPreferences(
                    MainActivity.PREFS_NAME, Context.MODE_PRIVATE
                )
                val serviceEnabled = prefs.getBoolean(MainActivity.KEY_SERVICE_ENABLED, false)
                val deviceName = prefs.getString(MainActivity.KEY_DEVICE_NAME, null)

                if (serviceEnabled && !deviceName.isNullOrEmpty()) {
                    Log.d(TAG, "Boot completed – restarting service for device: $deviceName")
                    val serviceIntent = Intent(context, BluetoothMonitorService::class.java).apply {
                        action = BluetoothMonitorService.ACTION_START
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                }
            }

            android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED,
            android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                // Se il servizio non è in esecuzione ma è abilitato, avvialo
                // (il servizio gestirà internamente l'evento tramite il suo receiver)
                val prefs = context.getSharedPreferences(
                    MainActivity.PREFS_NAME, Context.MODE_PRIVATE
                )
                val serviceEnabled = prefs.getBoolean(MainActivity.KEY_SERVICE_ENABLED, false)

                if (serviceEnabled && !BluetoothMonitorService.isRunning) {
                    Log.d(TAG, "BT event received, service not running – starting it")
                    val serviceIntent = Intent(context, BluetoothMonitorService::class.java).apply {
                        action = BluetoothMonitorService.ACTION_START
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                }
            }
        }
    }
}
