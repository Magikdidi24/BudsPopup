package io.github.magikdidi24.budspopup.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.content.IntentCompat
import io.github.magikdidi24.budspopup.Prefs
import io.github.magikdidi24.budspopup.R
import io.github.magikdidi24.budspopup.TAG
import io.github.magikdidi24.budspopup.bluetooth.RfcommTransport
import io.github.magikdidi24.budspopup.overlay.OverlayController
import io.github.magikdidi24.budspopup.protocol.BudsStatus
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Lives for one case opening: reads the buds over RFCOMM, shows the card if a bud sits in the case,
 * and stops when the case closes (ACL drops), the user dismisses it, or after 5 min.
 */
class PopupService : Service() {
    private val scope = MainScope()
    private var overlay: OverlayController? = null
    private var started = false
    private var finishing = false
    private var caseClosed: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        val address = intent?.getStringExtra(EXTRA_ADDRESS)
        if (!started && address != null) {
            started = true
            watch(address)
        } else if (!started) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission") // the receiver only fires once BLUETOOTH_CONNECT is granted
    private fun watch(address: String) {
        val device = getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
        val name = device.alias ?: device.name ?: "Galaxy Buds"
        val prefs = Prefs(this)
        val status = MutableStateFlow<BudsStatus?>(null)
        // Closing the case with the buds inside drops their link: that is when the card goes away.
        caseClosed = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val gone = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (gone?.address == address) {
                    Log.i(TAG, "ACL disconnected: case closed")
                    finish()
                }
            }
        }.also { registerReceiver(it, IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED), RECEIVER_EXPORTED) }
        scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (overlay == null) finish() // buds stayed silent or the link never came up
        }
        scope.launch {
            var retakes = 0
            while (true) {
                RfcommTransport.statuses(device).collect { s ->
                    Log.d(TAG, "status $s")
                    s.color?.let { prefs.color = it }
                    status.value = s
                    if (overlay == null && !finishing && s.anyInCase) {
                        overlay = OverlayController(this@PopupService).also { it.show(name, status, prefs.color, ::finish) }
                        // shortcut: no signal when the buds are worn and the case shut empty, so a cap; replace with BLE adverts if it bothers
                        launch { delay(MAX_SHOWN_MS); finish() }
                    }
                }
                Log.i(TAG, "RFCOMM link ended")
                if (overlay == null) { finish(); break }
                // Samsung's buds manager wants the only SPP channel too: each of its 3 tries over ~6 s
                // drops ours, then it gives up. Taking the link back until then keeps the card live.
                if (finishing || ++retakes > MAX_RETAKES) break
                delay(RETAKE_DELAY_MS)
                Log.i(TAG, "retaking the SPP link ($retakes/$MAX_RETAKES)")
            }
        }
    }

    private fun finish() {
        if (finishing) return
        finishing = true
        overlay?.hide { stopSelf() } ?: stopSelf()
    }

    override fun onDestroy() {
        scope.cancel() // closes the RFCOMM socket
        caseClosed?.let { unregisterReceiver(it) }
        overlay?.remove()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val channel = NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentText(getString(R.string.notification_text))
            .build()
    }

    companion object {
        private const val EXTRA_ADDRESS = "address"
        private const val CHANNEL = "popup"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val MAX_SHOWN_MS = 5 * 60_000L
        private const val MAX_RETAKES = 8
        private const val RETAKE_DELAY_MS = 2_000L

        fun start(context: Context, address: String) {
            context.startForegroundService(Intent(context, PopupService::class.java).putExtra(EXTRA_ADDRESS, address))
        }
    }
}
