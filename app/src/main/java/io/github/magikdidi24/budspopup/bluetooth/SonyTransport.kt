package io.github.magikdidi24.budspopup.bluetooth

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.util.Log
import androidx.annotation.RequiresPermission
import io.github.magikdidi24.budspopup.TAG
import io.github.magikdidi24.budspopup.protocol.HeadphonesStatus
import io.github.magikdidi24.budspopup.protocol.SonyFrame
import io.github.magikdidi24.budspopup.protocol.SonyFrameSplitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Battery of Sony over-ear headphones (WH-1000XM4 and other "v1" models). */
object SonyTransport {
    private val SERVICE: UUID = UUID.fromString("96CC203E-5068-46AD-B32D-E316F5E069BA")

    /**
     * Says hello, then asks for the battery once the headphones have both acknowledged the hello
     * and answered it. Every message they send is acknowledged with the other sequence number;
     * ours use the number from their last acknowledgement.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun battery(device: BluetoothDevice): Flow<HeadphonesStatus> = channelFlow {
        val socket = withContext(Dispatchers.IO) { RfcommTransport.connect(device, SERVICE) } ?: return@channelFlow
        val out = socket.outputStream
        fun write(frame: SonyFrame) = synchronized(out) { out.write(frame.encode()); out.flush() }
        val acked = AtomicBoolean(false)
        launch(Dispatchers.IO) {
            // Measured on a WH-1000XM4: a hello sent as the link opens is ignored while the headphones
            // are still starting up (they first speak ~1.6 s later), so repeat it until they ack it.
            repeat(6) {
                if (acked.get()) return@launch
                runCatching { write(SonyFrame(SonyFrame.COMMAND, 0, HeadphonesStatus.INIT_REQUEST)) }
                delay(1_500)
            }
        }
        launch(Dispatchers.IO) {
            var sequence = 0
            var initialised = false
            var asked = false
            val splitter = SonyFrameSplitter()
            val buf = ByteArray(1024)
            try {
                val input = socket.inputStream
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    Log.d(TAG, "rx ${buf.copyOf(n).joinToString(" ") { "%02x".format(it) }}")
                    for (frame in splitter.feed(buf, n)) {
                        if (frame.type == SonyFrame.ACK) {
                            sequence = frame.sequence
                            acked.set(true)
                        } else {
                            write(SonyFrame(SonyFrame.ACK, 1 - frame.sequence, ByteArray(0)))
                            if (HeadphonesStatus.isInitReply(frame)) initialised = true
                            HeadphonesStatus.parse(frame)?.let { send(it) }
                        }
                        if (acked.get() && initialised && !asked) {
                            asked = true
                            write(SonyFrame(SonyFrame.COMMAND, sequence, HeadphonesStatus.BATTERY_REQUEST))
                        }
                    }
                }
            } catch (e: IOException) {
                Log.i(TAG, "Sony link closed: ${e.message}")
            } finally {
                this@channelFlow.close()
            }
        }
        awaitClose { socket.close() }
    }
}
