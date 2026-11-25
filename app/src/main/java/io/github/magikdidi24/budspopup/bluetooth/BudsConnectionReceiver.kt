package io.github.magikdidi24.budspopup.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.IntentCompat
import io.github.magikdidi24.budspopup.Prefs
import io.github.magikdidi24.budspopup.TAG
import io.github.magikdidi24.budspopup.service.PopupService

/** Opening the buds case, or turning the headphones on, connects them: that ACL link is our trigger. */
class BudsConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        val prefs = Prefs(context)
        val headphones = when (device.address) {
            prefs.budsAddress -> false
            prefs.headphonesAddress -> true
            else -> return
        }
        Log.i(TAG, "ACL connected: ${device.address}")
        try {
            PopupService.start(context, device.address, headphones)
        } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException without the CDM exemption
            Log.w(TAG, "cannot start the popup service from the background", e)
        }
    }
}
