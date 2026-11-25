package io.github.magikdidi24.budspopup.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.magikdidi24.budspopup.Prefs
import io.github.magikdidi24.budspopup.TAG
import io.github.magikdidi24.budspopup.overlay.OverlayController
import io.github.magikdidi24.budspopup.protocol.BudsColor
import io.github.magikdidi24.budspopup.protocol.BudsStatus
import io.github.magikdidi24.budspopup.protocol.Placement
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val prefs by lazy { Prefs(this) }
    private val refresh = mutableIntStateOf(0) // bumped on resume: permissions may have changed in Settings
    private var testOverlay: OverlayController? = null
    private var testScript: Job? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh.intValue++ }
    private val association = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        Log.i(TAG, "companion association result ${it.resultCode}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh.intValue++
    }

    @Composable
    private fun Screen() {
        refresh.intValue // read so the screen recomposes after a refresh
        val bluetooth = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val overlay = Settings.canDrawOverlays(this)
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Buds Popup", style = MaterialTheme.typography.headlineMedium)
            Text("Ouvre le boîtier : la carte batterie s'affiche en bas de l'écran.", style = MaterialTheme.typography.bodyMedium)

            Section("Autorisations")
            Requirement("Bluetooth et notifications", bluetooth) {
                permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS))
            }
            Requirement("Afficher par-dessus les autres apps", overlay) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
            }
            Requirement("Batterie non optimisée (One UI tue l'app sinon)", battery) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri()))
            }

            Section("Écouteurs")
            if (bluetooth) Devices(prefs.budsAddress) else Text("Autorise le Bluetooth pour voir les appareils appairés.")

            Section("Tester la popup")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { testPopup(BudsColor.White) }) { Text("Blanc") }
                OutlinedButton(onClick = { testPopup(BudsColor.Black) }) { Text("Noir") }
            }
        }
    }

    @SuppressLint("MissingPermission") // only composed once BLUETOOTH_CONNECT is granted
    @Composable
    private fun Devices(chosen: String?) {
        val bonded = getSystemService(BluetoothManager::class.java).adapter?.bondedDevices.orEmpty()
            .filter { it.type != BluetoothDevice.DEVICE_TYPE_LE } // LE Audio identities duplicate the classic entry
            .sortedByDescending { "buds" in (it.name ?: "").lowercase() }
        if (bonded.isEmpty()) Text("Aucun appareil appairé.")
        bonded.forEach { device ->
            val selected = device.address == chosen
            Row(
                Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton) { choose(device) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(device.alias ?: device.name ?: device.address)
                    Text(device.address, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    private fun choose(device: BluetoothDevice) {
        prefs.budsAddress = device.address
        refresh.intValue++
        // A companion association lets the ACL receiver start the foreground service from the background.
        val cdm = getSystemService(CompanionDeviceManager::class.java) ?: return
        @Suppress("DEPRECATION") // getMyAssociations() needs API 33
        if (cdm.associations.any { it.equals(device.address, ignoreCase = true) }) return
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(device.address).build())
            .setSingleDevice(true)
            .build()
        @Suppress("DEPRECATION") // the executor overload needs API 33; this one still works there
        cdm.associate(request, object : CompanionDeviceManager.Callback() {
            @Deprecated("API 33 routes onAssociationPending here by default")
            override fun onDeviceFound(intentSender: IntentSender) {
                association.launch(IntentSenderRequest.Builder(intentSender).build())
            }

            override fun onFailure(error: CharSequence?) {
                Log.w(TAG, "companion association failed: $error")
            }
        }, null)
    }

    private fun testPopup(color: BudsColor) {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
            return
        }
        testOverlay?.remove()
        testScript?.cancel()
        val status = MutableStateFlow<BudsStatus?>(
            BudsStatus(82, 35, 0, Placement.InCase, Placement.InCase, leftCharging = true, rightCharging = true, color = color),
        )
        testOverlay = OverlayController(applicationContext).also { overlay ->
            overlay.show("Galaxy Buds4 Pro", status, color) { overlay.hide {} }
        }
        // Take each bud out and put it back at its own time, like a real case.
        testScript = lifecycleScope.launch {
            delay(2500); status.update { it?.copy(placementLeft = Placement.Idle, leftCharging = false) }
            delay(2000); status.update { it?.copy(placementRight = Placement.Idle, rightCharging = false) }
            delay(3000); status.update { it?.copy(placementLeft = Placement.InCase, leftCharging = true) }
            delay(2000); status.update { it?.copy(placementRight = Placement.InCase, rightCharging = true) }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun Requirement(label: String, granted: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text((if (granted) "✓ " else "✗ ") + label, Modifier.weight(1f))
        if (!granted) Button(onClick = onFix) { Text("Autoriser") }
    }
}
