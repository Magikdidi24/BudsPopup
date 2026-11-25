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
import io.github.magikdidi24.budspopup.bluetooth.SonyTransport
import androidx.compose.runtime.collectAsState
import io.github.magikdidi24.budspopup.overlay.BudsCardContent
import io.github.magikdidi24.budspopup.overlay.HeadphonesCardContent
import io.github.magikdidi24.budspopup.overlay.OverlayController
import io.github.magikdidi24.budspopup.protocol.BudsStatus
import io.github.magikdidi24.budspopup.protocol.HeadphonesStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Lives for one case opening: reads the buds over RFCOMM, shows the card if a bud sits in the case,
 * and stops when the case closes (ACL drops or a bud reports a closed case), 1 s after both buds are
 * in the ears (30 s if they are only held out), when the user dismisses it, or after 5 min.
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
            if (intent.getBooleanExtra(EXTRA_HEADPHONES, false)) watchHeadphones(address) else watch(address)
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
        closeOnDisconnect(address, "case closed (buds disconnected)")
        scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (overlay == null) finish("no status from the buds")
        }
        scope.launch {
            var retakes = 0
            var closing: Job? = null
            var closingAfter: Long? = null
            while (true) {
                RfcommTransport.statuses(device).collect { s ->
                    Log.d(TAG, "status $s")
                    s.color?.let { prefs.color = it }
                    status.value = s
                    when {
                        s.shutInCase -> finish("lid closed on a bud")
                        overlay == null && !finishing && s.anyInCase -> {
                            val color = prefs.color
                            overlay = OverlayController(this@PopupService).also {
                                it.show(name, { finish("dismissed") }) { fg -> BudsCardContent(status.collectAsState().value, color, fg) }
                            }
                            launch { delay(MAX_SHOWN_MS); finish("shown for 5 min") }
                        }
                    }
                    // An empty case closing sends nothing and both buds stay connected. Buds going into the
                    // ears is when the case gets closed, so the card goes then; buds only held out get longer.
                    val after = when {
                        !s.bothOut -> null
                        s.bothWorn -> WORN_CLOSE_MS
                        else -> OUT_CLOSE_MS
                    }
                    if (after != closingAfter) {
                        closing?.cancel()
                        closingAfter = after
                        closing = after?.let { ms -> launch { delay(ms); finish(if (ms == WORN_CLOSE_MS) "both buds worn" else "both buds out for 30 s") } }
                    }
                }
                Log.i(TAG, "RFCOMM link ended")
                if (overlay == null) { finish("no bud in the case"); break }
                // Samsung's buds manager wants the only SPP channel too: each of its 3 tries over ~6 s
                // drops ours, then it gives up. Taking the link back until then keeps the card live.
                if (finishing || ++retakes > MAX_RETAKES) break
                delay(RETAKE_DELAY_MS)
                Log.i(TAG, "retaking the SPP link ($retakes/$MAX_RETAKES)")
            }
        }
    }

    /** Headphones: the card comes up with the battery once they connect and leaves after a few seconds. */
    @SuppressLint("MissingPermission")
    private fun watchHeadphones(address: String) {
        val device = getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
        val name = device.alias ?: device.name ?: "Headphones"
        val status = MutableStateFlow<HeadphonesStatus?>(null)
        closeOnDisconnect(address, "headphones disconnected")
        scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (overlay == null) finish("no battery from the headphones")
        }
        scope.launch {
            SonyTransport.battery(device).collect { s ->
                Log.d(TAG, "headphones $s")
                status.value = s
                if (overlay == null && !finishing) {
                    overlay = OverlayController(this@PopupService).also {
                        it.show(name, { finish("dismissed") }) { fg -> HeadphonesCardContent(status.collectAsState().value, fg) }
                    }
                    launch { delay(HEADPHONES_SHOWN_MS); finish("shown for 8 s") }
                }
            }
            if (overlay == null) finish("no battery from the headphones")
        }
    }

    private fun closeOnDisconnect(address: String, reason: String) {
        caseClosed = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val gone = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (gone?.address == address) finish(reason)
            }
        }.also { registerReceiver(it, IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED), RECEIVER_EXPORTED) }
    }

    private fun finish(reason: String) {
        if (finishing) return
        finishing = true
        Log.i(TAG, "closing the card: $reason")
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
        private const val EXTRA_HEADPHONES = "headphones"
        private const val CHANNEL = "popup"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val MAX_SHOWN_MS = 5 * 60_000L
        private const val WORN_CLOSE_MS = 1_000L
        private const val OUT_CLOSE_MS = 30_000L
        private const val HEADPHONES_SHOWN_MS = 8_000L
        private const val MAX_RETAKES = 8
        private const val RETAKE_DELAY_MS = 300L // short: until Samsung gives up, every drop leaves the card blind

        fun start(context: Context, address: String, headphones: Boolean) {
            context.startForegroundService(
                Intent(context, PopupService::class.java).putExtra(EXTRA_ADDRESS, address).putExtra(EXTRA_HEADPHONES, headphones),
            )
        }
    }
}
