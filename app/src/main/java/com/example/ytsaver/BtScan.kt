package com.example.ytsaver

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/** Runtime permissions needed to turn Bluetooth on and look for nearby devices. */
fun btScanPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

fun btHasScanPermission(ctx: Context): Boolean =
    btScanPermissions().all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

/** State of an in-place Bluetooth search (no extra window): turn Bluetooth on if needed, then list nearby devices. */
class BtScanner(
    val found: androidx.compose.runtime.snapshots.SnapshotStateList<Pair<String, String>>,
    scanningState: State<Boolean>,
    messageState: State<String>,
    val search: () -> Unit,
) {
    val scanning by scanningState
    val message by messageState
}

@SuppressLint("MissingPermission")
@Composable
fun rememberBtScanner(known: () -> List<Pair<String, String>>): BtScanner {
    val ctx = LocalContext.current
    val adapter = remember { ctx.getSystemService(BluetoothManager::class.java)?.adapter }
    val found = remember { mutableStateListOf<Pair<String, String>>() }
    val scanning = remember { mutableStateOf(false) }
    val message = remember { mutableStateOf("") }
    val knownNow by rememberUpdatedState(known)

    fun startScan() {
        val a = adapter ?: run { message.value = L.t("This device has no Bluetooth.", "هذا الجهاز لا يدعم البلوتوث."); return }
        if (!a.isEnabled) return
        try {
            if (a.isDiscovering) a.cancelDiscovery()
            found.clear()
            scanning.value = a.startDiscovery()
            message.value = if (scanning.value) "" else L.t("Could not start searching.", "تعذر بدء البحث.")
        } catch (_: Exception) {
            scanning.value = false
            message.value = L.t("Could not start searching.", "تعذر بدء البحث.")
        }
    }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (adapter?.isEnabled == true) startScan()
        else message.value = L.t("Bluetooth is still off.", "البلوتوث لا يزال متوقفًا.")
    }
    fun enableThenScan() {
        val a = adapter ?: run { message.value = L.t("This device has no Bluetooth.", "هذا الجهاز لا يدعم البلوتوث."); return }
        if (a.isEnabled) startScan()
        else try { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) {
            message.value = L.t("Turn Bluetooth on from the phone settings.", "فعّل البلوتوث من إعدادات الهاتف.")
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) enableThenScan()
        else message.value = L.t("Allow the Bluetooth permission to search for devices.", "اسمح بإذن البلوتوث للبحث عن الأجهزة.")
    }

    DisposableEffect(Unit) {
        val rec = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_NAME_CHANGED -> {
                        val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        if (d != null) {
                            val a = d.address
                            // the name is usually NOT on the device object while searching: it comes in the broadcast itself
                            val n = (i.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: try { d.name } catch (_: Exception) { null })?.takeIf { it.isNotBlank() }
                            if (a != null && knownNow().none { it.second == a }) {
                                val at = found.indexOfFirst { it.second == a }
                                if (at < 0) found.add((n ?: a) to a)
                                else if (n != null && found[at].first != n) found[at] = n to a      // the name arrived after the first sighting
                            }
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning.value = false
                }
            }
        }
        val f = IntentFilter(BluetoothDevice.ACTION_FOUND).apply {
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(ctx, rec, f, ContextCompat.RECEIVER_EXPORTED)
        onDispose {
            try { if (adapter?.isDiscovering == true && btHasScanPermission(ctx)) adapter.cancelDiscovery() } catch (_: Exception) { }
            try { ctx.unregisterReceiver(rec) } catch (_: Exception) { }
        }
    }
    return remember {
        BtScanner(found, scanning, message) {
            if (btHasScanPermission(ctx)) enableThenScan() else permission.launch(btScanPermissions())
        }
    }
}

/** A tappable device line (shared by the paired and nearby lists). */
@Composable
fun BtDeviceRow(name: String, addr: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Sym(R.drawable.ic_bluetooth, 18.dp, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(name, fontSize = 14.sp)
            Text(addr, color = Muted, fontSize = 11.sp)
        }
    }
}

/** A section heading that folds its content: "Paired devices ▾". */
@Composable
fun BtSectionHeader(title: String, open: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(if (open) "▴" else "▾", color = Muted, fontSize = 16.sp)
    }
}
