package org.radiolink.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var manager: WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false

    private var status by mutableStateOf("آماده شروع آزمایش")
    private var peersText by mutableStateOf("هنوز دستگاهی پیدا نشده است")
    private var isWifiP2pEnabled by mutableStateOf(false)
    private var isDiscovering by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            if (result.values.all { it }) {
                startDiscovery()
            } else {
                status = "مجوز دستگاه‌های نزدیک تأیید نشد؛ از تنظیمات برنامه آن را فعال کن."
            }
        }

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    isWifiP2pEnabled = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED
                    ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED

                    if (isWifiP2pEnabled) {
                        status = "Wi-Fi Direct روشن است؛ آماده جست‌وجو"
                    } else {
                        isDiscovering = false
                        peersText = "برای پیدا کردن دستگاه‌ها، Wi-Fi را روشن کن."
                        status = "Wi-Fi Direct خاموش است"
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeerList()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        manager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper) {
            channel = null
            isDiscovering = false
            status = "ارتباط داخلی Wi-Fi Direct قطع شد؛ برنامه را دوباره باز کن."
        }

        if (channel == null) {
            status = "راه‌اندازی Wi-Fi Direct ناموفق بود."
        }

        setContent {
            MaterialTheme {
                RadioLinkScreen(
                    status = status,
                    peers = peersText,
                    wifiP2pEnabled = isWifiP2pEnabled,
                    discovering = isDiscovering,
                    onDiscover = { checkPermissionsAndDiscover() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
            receiverRegistered = true
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(receiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun checkPermissionsAndDiscover() {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

        if (ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startDiscovery()
        } else {
            permissionLauncher.launch(arrayOf(permission))
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        val ch = channel
        if (ch == null) {
            status = "کانال Wi-Fi Direct آماده نیست؛ برنامه را دوباره باز کن."
            return
        }
        if (!isWifiP2pEnabled) {
            status = "ابتدا Wi-Fi گوشی را روشن کن."
            return
        }

        isDiscovering = true
        status = "در حال جست‌وجوی گوشی‌های نزدیک..."
        peersText = "در حال دریافت فهرست دستگاه‌ها..."

        manager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                status = "جست‌وجو شروع شد؛ چند لحظه صبر کن."
                requestPeerList()
            }

            override fun onFailure(reason: Int) {
                isDiscovering = false
                status = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED ->
                        "این گوشی از Wi-Fi Direct پشتیبانی نمی‌کند."
                    WifiP2pManager.BUSY ->
                        "Wi-Fi Direct مشغول است؛ چند لحظه دیگر دوباره امتحان کن."
                    WifiP2pManager.ERROR ->
                        "خطای داخلی Wi-Fi Direct رخ داد."
                    else -> "شروع جست‌وجو ناموفق بود؛ کد خطا: $reason"
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestPeerList() {
        val ch = channel ?: return
        manager.requestPeers(ch) { list ->
            peersText = if (list.deviceList.isEmpty()) {
                "هنوز گوشی دیگری پیدا نشده است. Wi-Fi هر دو گوشی را روشن نگه دار و دوباره جست‌وجو کن."
            } else {
                list.deviceList.joinToString("\n\n") { device ->
                    val name = device.deviceName?.takeIf { it.isNotBlank() } ?: "دستگاه ناشناس"
                    name + "\n" + device.deviceAddress + "\nوضعیت: " +
                        deviceStatusLabel(device.status)
                }
            }
        }
    }

    private fun deviceStatusLabel(status: Int): String = when (status) {
        WifiP2pDevice.AVAILABLE -> "آماده اتصال"
        WifiP2pDevice.INVITED -> "دعوت‌شده"
        WifiP2pDevice.CONNECTED -> "متصل"
        WifiP2pDevice.FAILED -> "ناموفق"
        WifiP2pDevice.UNAVAILABLE -> "در دسترس نیست"
        else -> "نامشخص"
    }
}

@Composable
private fun RadioLinkScreen(
    status: String,
    peers: String,
    wifiP2pEnabled: Boolean,
    discovering: Boolean,
    onDiscover: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("RadioLink", style = MaterialTheme.typography.headlineLarge)
        Text("ارتباط محلی بدون اینترنت")

        Surface(
            color = if (wifiP2pEnabled) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                if (wifiP2pEnabled) "Wi-Fi Direct روشن" else "Wi-Fi Direct خاموش",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium
            )
        }

        Text(status, style = MaterialTheme.typography.bodyMedium)

        Button(
            onClick = onDiscover,
            enabled = wifiP2pEnabled && !discovering,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (discovering) "در حال جست‌وجو..." else "جست‌وجوی گوشی‌های نزدیک")
        }

        HorizontalDivider()
        Text("دستگاه‌های پیدا شده", style = MaterialTheme.typography.titleMedium)
        Text(peers, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "این نسخه فقط دستگاه‌های نزدیک را شناسایی می‌کند؛ اتصال و مکالمه صوتی در مرحله بعدی توسعه است.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
