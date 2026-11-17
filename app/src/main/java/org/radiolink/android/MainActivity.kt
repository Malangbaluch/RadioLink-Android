package org.radiolink.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
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
    private var transport: LocalChatTransport? = null

    private var status by mutableStateOf("آماده شروع آزمایش")
    private var peers by mutableStateOf<List<WifiP2pDevice>>(emptyList())
    private var selectedAddress by mutableStateOf<String?>(null)
    private var isWifiP2pEnabled by mutableStateOf(false)
    private var isDiscovering by mutableStateOf(false)
    private var isConnected by mutableStateOf(false)
    private var isChatReady by mutableStateOf(false)
    private var messageDraft by mutableStateOf("")
    private var chatLog by mutableStateOf("")

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                pendingAction?.invoke()
                pendingAction = null
            } else {
                pendingAction = null
                status = "مجوز دستگاه‌های نزدیک تأیید نشد؛ از تنظیمات برنامه آن را فعال کن."
            }
        }

    private var pendingAction: (() -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    isWifiP2pEnabled = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED
                    ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    status = if (isWifiP2pEnabled) {
                        "Wi-Fi Direct روشن است؛ آماده جست‌وجو"
                    } else {
                        isDiscovering = false
                        peers = emptyList()
                        "Wi-Fi Direct خاموش است؛ Wi-Fi گوشی را روشن کن."
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeerList()

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_NETWORK_INFO,
                            android.net.NetworkInfo::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }
                    if (networkInfo?.isConnected == true) {
                        requestConnectionInfo()
                    } else {
                        isConnected = false
                        isChatReady = false
                        transport?.close()
                        transport = null
                        status = "اتصال Wi-Fi Direct برقرار نیست."
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper) {
            channel = null
            isDiscovering = false
            isConnected = false
            isChatReady = false
            transport?.close()
            transport = null
            status = "ارتباط داخلی Wi-Fi Direct قطع شد؛ برنامه را دوباره باز کن."
        }
        if (channel == null) status = "راه‌اندازی Wi-Fi Direct ناموفق بود."

        setContent {
            MaterialTheme {
                RadioLinkScreen(
                    status = status,
                    peers = peers,
                    selectedAddress = selectedAddress,
                    wifiP2pEnabled = isWifiP2pEnabled,
                    discovering = isDiscovering,
                    connected = isConnected,
                    chatReady = isChatReady,
                    messageDraft = messageDraft,
                    chatLog = chatLog,
                    onSelectPeer = { selectedAddress = it },
                    onDiscover = { withNearbyPermission { startDiscovery() } },
                    onConnect = { withNearbyPermission { connectToSelectedPeer() } },
                    onMessageChange = { messageDraft = it },
                    onSend = { sendChatMessage() }
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
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
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

    override fun onDestroy() {
        transport?.close()
        transport = null
        super.onDestroy()
    }

    private fun withNearbyPermission(action: () -> Unit) {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingAction = action
            permissionLauncher.launch(arrayOf(permission))
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        val ch = channel ?: run {
            status = "کانال Wi-Fi Direct آماده نیست؛ برنامه را دوباره باز کن."
            return
        }
        if (!isWifiP2pEnabled) {
            status = "ابتدا Wi-Fi گوشی را روشن کن."
            return
        }
        isDiscovering = true
        status = "در حال جست‌وجوی گوشی‌های نزدیک..."
        manager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                status = "جست‌وجو شروع شد؛ چند لحظه صبر کن."
                requestPeerList()
            }
            override fun onFailure(reason: Int) {
                isDiscovering = false
                status = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "این گوشی از Wi-Fi Direct پشتیبانی نمی‌کند."
                    WifiP2pManager.BUSY -> "Wi-Fi Direct مشغول است؛ کمی بعد دوباره امتحان کن."
                    WifiP2pManager.ERROR -> "خطای داخلی Wi-Fi Direct رخ داد."
                    else -> "شروع جست‌وجو ناموفق بود؛ کد خطا: $reason"
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestPeerList() {
        val ch = channel ?: return
        manager.requestPeers(ch) { list ->
            peers = list.deviceList.toList()
            if (selectedAddress != null && peers.none { it.deviceAddress == selectedAddress }) {
                selectedAddress = null
            }
            isDiscovering = false
            if (peers.isEmpty()) status = "گوشی دیگری پیدا نشد؛ Wi-Fi هر دو گوشی را روشن نگه دار."
            else status = "${peers.size} دستگاه پیدا شد؛ یکی را انتخاب و متصل کن."
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToSelectedPeer() {
        val ch = channel ?: run {
            status = "کانال Wi-Fi Direct آماده نیست."
            return
        }
        val address = selectedAddress ?: run {
            status = "ابتدا یک گوشی از فهرست انتخاب کن."
            return
        }
        val config = WifiP2pConfig().apply { deviceAddress = address }
        status = "درخواست اتصال ارسال شد..."
        manager.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                status = "درخواست اتصال پذیرفته شد؛ منتظر تأیید گوشی مقابل..."
            }
            override fun onFailure(reason: Int) {
                status = "درخواست اتصال ناموفق بود؛ کد خطا: $reason"
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo() {
        val ch = channel ?: return
        manager.requestConnectionInfo(ch) { info: WifiP2pInfo ->
            if (!info.groupFormed) {
                status = "گروه Wi-Fi Direct هنوز تشکیل نشده است."
                return@requestConnectionInfo
            }
            if (isConnected) return@requestConnectionInfo
            isConnected = true
            transport?.close()
            val link = LocalChatTransport(
                onStatus = { text -> runOnUiThread { status = text } },
                onMessage = { text -> runOnUiThread { chatLog = (chatLog + "\nدریافتی: " + text).trim() } }
            )
            transport = link
            if (info.isGroupOwner) {
                status = "این گوشی میزبان گروه شد؛ راه‌اندازی کانال پیام..."
                link.startAsGroupOwner()
            } else {
                val host = info.groupOwnerAddress?.hostAddress
                if (host.isNullOrBlank()) {
                    isConnected = false
                    status = "نشانی میزبان دریافت نشد."
                } else {
                    status = "گوشی به گروه پیوست؛ اتصال به میزبان..."
                    link.connectToGroupOwner(host)
                }
            }
        }
    }

    private fun sendChatMessage() {
        val message = messageDraft.trim()
        if (message.isEmpty()) return
        val sent = transport?.send(message) == true
        if (sent) {
            chatLog = (chatLog + "\nارسالی: " + message).trim()
            messageDraft = ""
        } else {
            status = "هنوز کانال پیام آماده نیست؛ کمی صبر کن یا دوباره وصل شو."
        }
    }
}

@Composable
private fun RadioLinkScreen(
    status: String,
    peers: List<WifiP2pDevice>,
    selectedAddress: String?,
    wifiP2pEnabled: Boolean,
    discovering: Boolean,
    connected: Boolean,
    chatReady: Boolean,
    messageDraft: String,
    chatLog: String,
    onSelectPeer: (String) -> Unit,
    onDiscover: () -> Unit,
    onConnect: () -> Unit,
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("RadioLink", style = MaterialTheme.typography.headlineLarge)
        Text("ارتباط محلی بدون اینترنت")

        AssistChip(
            onClick = {},
            label = { Text(if (wifiP2pEnabled) "Wi-Fi Direct روشن" else "Wi-Fi Direct خاموش") }
        )
        Text(status, style = MaterialTheme.typography.bodyMedium)

        Button(onClick = onDiscover, enabled = wifiP2pEnabled && !discovering,
            modifier = Modifier.fillMaxWidth()) {
            Text(if (discovering) "در حال جست‌وجو..." else "جست‌وجوی گوشی‌های نزدیک")
        }

        HorizontalDivider()
        Text("دستگاه‌های پیدا شده", style = MaterialTheme.typography.titleMedium)
        if (peers.isEmpty()) {
            Text("هنوز دستگاهی پیدا نشده است.")
        } else {
            peers.forEach { device ->
                val title = device.deviceName?.takeIf { it.isNotBlank() } ?: "دستگاه ناشناس"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.bodyLarge)
                        Text(device.deviceAddress, style = MaterialTheme.typography.bodySmall)
                    }
                    RadioButton(
                        selected = selectedAddress == device.deviceAddress,
                        onClick = { onSelectPeer(device.deviceAddress) }
                    )
                }
            }
        }

        Button(
            onClick = onConnect,
            enabled = wifiP2pEnabled && selectedAddress != null && !connected,
            modifier = Modifier.fillMaxWidth()
        ) { Text("اتصال به گوشی انتخاب‌شده") }

        HorizontalDivider()
        Text(if (chatReady) "کانال پیام آماده است" else "آزمایش پیام متنی", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = messageDraft,
            onValueChange = onMessageChange,
            enabled = chatReady,
            label = { Text("پیام آزمایشی") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = onSend, enabled = chatReady && messageDraft.isNotBlank(),
            modifier = Modifier.fillMaxWidth()) { Text("ارسال پیام") }
        if (chatLog.isNotBlank()) {
            Text(chatLog, modifier = Modifier.fillMaxWidth())
        }
        Text(
            "این مرحله فقط اتصال و پیام متنی را می‌آزماید؛ مکالمه صوتی هنوز پیاده‌سازی نشده است.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
