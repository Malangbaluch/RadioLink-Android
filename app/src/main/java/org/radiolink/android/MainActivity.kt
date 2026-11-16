
package org.radiolink.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var manager: WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    private var status by mutableStateOf("آماده شروع آزمایش")
    private var peersText by mutableStateOf("هنوز دستگاهی پیدا نشده است")

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            if (result.values.all { it }) {
                startDiscovery()
            } else {
                status = "مجوزهای لازم تأیید نشدند"
            }
        }

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    val ch = channel ?: return

                    manager.requestPeers(ch) { list ->
                        peersText =
                            if (list.deviceList.isEmpty()) {
                                "هنوز گوشی دیگری پیدا نشده است"
                            } else {
                                list.deviceList.joinToString("\n") {
                                    "${it.deviceName} · ${it.deviceAddress}"
                                }
                            }
                    }
                }

                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        -1
                    ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED

                    if (!enabled) {
                        status = "Wi-Fi Direct فعال نیست"
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        manager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)

        setContent {
            MaterialTheme {
                RadioLinkScreen(
                    status = status,
                    peers = peersText,
                    onDiscover = { checkPermissions() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(receiver)
    }

    private fun checkPermissions() {
        val required = mutableListOf(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= 33) {
            required.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            required.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missing = required.filter {
            ContextCompat.checkSelfPermission(
                this,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startDiscovery()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        val ch = channel ?: return

        status = "در حال جست‌وجوی گوشی‌های نزدیک..."

        manager.discoverPeers(
            ch,
            object : WifiP2pManager.ActionListener {

                override fun onSuccess() {
                    status = "جست‌وجو فعال شد"
                }

                override fun onFailure(reason: Int) {
                    status = "جست‌وجو ناموفق بود. کد خطا: $reason"
                }
            }
        )
    }
}

@Composable
private fun RadioLinkScreen(
    status: String,
    peers: String,
    onDiscover: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text(
            "RadioLink",
            style = MaterialTheme.typography.headlineLarge
        )

        Text("ارتباط محلی بدون اینترنت")

        Text(status)

        Button(
            onClick = onDiscover,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("جست‌وجوی گوشی‌های نزدیک")
        }

        HorizontalDivider()

        Text(
            "دستگاه‌های پیدا شده",
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            peers,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            "نسخه آزمایشی؛ هنوز اتصال و مکالمه صوتی پیاده‌سازی نشده است.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
