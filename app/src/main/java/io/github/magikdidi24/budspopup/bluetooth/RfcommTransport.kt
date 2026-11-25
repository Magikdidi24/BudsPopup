package io.github.magikdidi24.budspopup.bluetooth

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import androidx.annotation.RequiresPermission
import io.github.magikdidi24.budspopup.TAG
import io.github.magikdidi24.budspopup.protocol.BudsStatus
import io.github.magikdidi24.budspopup.protocol.SppFrameSplitter
import io.github.magikdidi24.budspopup.protocol.StatusParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

object RfcommTransport {
    /** SPP service of the Buds+ and newer (seen in the Buds4 Pro SDP records). */
    private val SPP_UUID: UUID = UUID.fromString("2e73a4ad-332d-41fc-90e2-16bef06523f2")

    /** Status messages from the buds until the link drops or the collector stops. Empty if it cannot connect. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun statuses(device: BluetoothDevice): Flow<BudsStatus> = channelFlow {
        val socket = withContext(Dispatchers.IO) { connect(device) } ?: return@channelFlow
        launch(Dispatchers.IO) {
            val splitter = SppFrameSplitter()
            val buf = ByteArray(2048)
            try {
                val input = socket.inputStream
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    Log.d(TAG, "rx ${buf.copyOf(n).joinToString(" ") { "%02x".format(it) }}")
                    for (frame in splitter.feed(buf, n)) {
                        StatusParser.parse(frame)?.let { send(it) }
                    }
                }
            } catch (e: IOException) {
                Log.i(TAG, "RFCOMM closed: ${e.message}")
            } finally {
                this@channelFlow.close()
            }
        }
        awaitClose { socket.close() } // also unblocks the read when the collector goes away
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun connect(device: BluetoothDevice): BluetoothSocket? {
        // The Galaxy Wearable app may hold the only SPP link: retry briefly, then give up quietly.
        repeat(3) { attempt ->
            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            try {
                socket.connect()
                Log.i(TAG, "RFCOMM connected to ${device.address}")
                return socket
            } catch (e: IOException) {
                socket.close()
                Log.w(TAG, "RFCOMM connect ${attempt + 1}/3 failed: ${e.message}")
                delay(250) // the first try right after the buds reconnect often fails
            }
        }
        return null
    }
}
