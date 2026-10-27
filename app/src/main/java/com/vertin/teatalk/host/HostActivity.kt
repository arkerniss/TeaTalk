package com.vertin.teatalk.host

import androidx.activity.compose.BackHandler
import com.vertin.teatalk.AndroidAssetSource
import com.vertin.teatalk.BuildConfig
import com.vertin.teatalk.discovery.AndroidLanDiscovery
import com.vertin.teatalk.ui.TeaTalkTheme
import java.util.UUID
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vertin.teatalk.chat.ChatActivity
import com.vertin.teatalk.core.server.LanServer
import com.vertin.teatalk.core.server.ServerConfig
import com.vertin.teatalk.util.AddressKind
import com.vertin.teatalk.util.LanCandidate
import com.vertin.teatalk.util.NetworkAddresses
import com.vertin.teatalk.util.NetworkInspection
import com.vertin.teatalk.util.generateQrBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private data class ProbeResult(
    val ok: Boolean,
    val detail: String,
)

class HostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            BackHandler { finish() }
            TeaTalkTheme {
                HostScreen()
            }
        }
    }

    @Composable
    private fun HostScreen() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val server = remember {
            LanServer(
                ServerConfig(
                    port = 8520,
                    dataDir = File(filesDir, "teatalk"),
                    assetSource = AndroidAssetSource(applicationContext.assets),
                    roomName = "我的局域网",
                    appId = "teatalk",
                    appVersion = BuildConfig.VERSION_NAME,
                ),
            )
        }
        val discovery = remember { AndroidLanDiscovery(applicationContext) }
        val hostId = remember { UUID.randomUUID().toString() }
        var attempt by remember { mutableIntStateOf(0) }
        var running by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var refreshTick by remember { mutableIntStateOf(0) }
        var inspection by remember { mutableStateOf<NetworkInspection?>(null) }
        var selectedIndex by remember { mutableIntStateOf(0) }
        var localHealth by remember { mutableStateOf<ProbeResult?>(null) }
        var lanHealth by remember { mutableStateOf<ProbeResult?>(null) }

        val candidates = inspection?.candidates.orEmpty()
        val selected = candidates.getOrNull(selectedIndex)
        val lanUrl = selected?.let { "http://${it.ip}:${server.port}/" }
        val qr = remember(lanUrl) {
            lanUrl?.let { runCatching { generateQrBitmap(it) }.getOrNull() }
        }
        val roomState by server.state.collectAsStateWithLifecycle()

        LaunchedEffect(attempt) {
            running = false
            error = null
            discovery.stopAdvertising()
            runCatching {
                withContext(Dispatchers.IO) { server.start() }
                running = true
                runCatching {
                    discovery.startAdvertising(
                        roomName = server.state.value.room.name.ifBlank { "我的局域网" },
                        port = server.port,
                        hostId = hostId,
                    )
                }
            }.onFailure { error = it.message ?: it::class.java.simpleName }
        }

        DisposableEffect(Unit) {
            onDispose {
                discovery.close()
                runCatching { server.stop() }
            }
        }

        LifecycleResumeEffect(Unit) {
            // Wi-Fi can be enabled after launch; re-scan every time we come back.
            refreshTick++
            onPauseOrDispose { }
        }

        DisposableEffect(context) {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { refreshTick++ }
                }

                override fun onLost(network: Network) {
                    scope.launch { refreshTick++ }
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    scope.launch { refreshTick++ }
                }
            }
            val registered = manager != null && runCatching {
                manager.registerDefaultNetworkCallback(callback)
            }.isSuccess
            onDispose {
                if (registered) runCatching { manager?.unregisterNetworkCallback(callback) }
            }
        }

        LaunchedEffect(refreshTick) {
            val previousIp = inspection?.candidates?.getOrNull(selectedIndex)?.ip
            val next = withContext(Dispatchers.IO) { NetworkAddresses.inspect(context) }
            val matchingIndex = if (previousIp == null) -1 else next.candidates.indexOfFirst { it.ip == previousIp }
            selectedIndex = if (matchingIndex >= 0) matchingIndex else next.recommendedIndex
            inspection = next
        }

        LaunchedEffect(running, selected?.ip) {
            localHealth = null
            lanHealth = null
            if (!running) return@LaunchedEffect
            localHealth = probeHealth("http://127.0.0.1:${server.port}/api/health")
            lanHealth = selected?.let { probeHealth("http://${it.ip}:${server.port}/api/health") }
        }

        fun copyText(label: String, text: String) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
        }

        fun diagnosticsText(): String = buildString {
            appendLine("茶室诊断")
            appendLine("版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
            appendLine("服务器: ${if (running) "running" else "not running"} / port ${server.port}")
            appendLine("选中URL: ${lanUrl ?: "无"}")
            appendLine("localhost health: ${localHealth?.let { if (it.ok) "OK ${it.detail}" else "FAIL ${it.detail}" } ?: "未检测"}")
            appendLine("当前IP health: ${lanHealth?.let { if (it.ok) "OK ${it.detail}" else "FAIL ${it.detail}" } ?: "未检测"}")
            val current = inspection
            if (current != null) {
                appendLine("Wi-Fi: ${current.wifiActive}, VPN: ${current.vpnActive}, 蜂窝: ${current.cellularActive}")
                appendLine("候选地址:")
                current.candidates.forEachIndexed { index, candidate ->
                    appendLine("  [$index] ${candidate.kind.label} ${candidate.interfaceName} ${candidate.ip} private=${candidate.isPrivate}")
                }
                appendLine("建议:")
                current.advice.forEach { appendLine("  - $it") }
            }
            appendLine("提示: 旧版测试端口 18520 已不再监听，新版端口是 ${server.port}。")
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(onClick = { finish() }) { Text("← 返回首页") }
                Text(
                    "茶室",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "同一片 Wi-Fi，慢慢聊。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (!running) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (error == null) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                Text("正在启动房间…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text(
                                    "房间启动失败",
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    error ?: "未知错误",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                                Button(onClick = { attempt++ }) { Text("重试") }
                            }
                        }
                    }
                } else {
                    Text(
                        "房间已开启",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (qr != null && lanUrl != null) {
                                Image(
                                    bitmap = qr.asImageBitmap(),
                                    contentDescription = "房间二维码",
                                    modifier = Modifier.size(220.dp),
                                )
                                SelectionContainer {
                                    Text(
                                        lanUrl,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodyLarge,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                                Text(
                                    "${selected?.kind?.label ?: "地址"} · ${selected?.interfaceName ?: "未知网卡"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    "未检测到可用的局域网地址",
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    "请先连接 Wi-Fi，或打开手机热点后再刷新网络。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (lanUrl != null) {
                                    TextButton(onClick = { copyText("茶室房间", lanUrl) }) { Text("复制链接") }
                                    TextButton(onClick = {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(lanUrl)))
                                        }
                                    }) { Text("系统浏览器测试") }
                                }
                                TextButton(onClick = { refreshTick++ }) { Text("刷新网络") }
                            }

                            Text(
                                "本机自检（只证明主机自己能连上）",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            HealthLine("127.0.0.1:${server.port}", localHealth)
                            if (selected != null) {
                                HealthLine("${selected.ip}:${server.port}", lanHealth)
                            }
                            Text(
                                "旧版测试端口是 18520，新版是 ${server.port}；请确认另一台设备地址里是 :${server.port}。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "地址候选（点选切换）",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (candidates.isEmpty()) {
                                Text(
                                    "没有可用 IPv4 地址。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            } else {
                                candidates.forEachIndexed { index, candidate ->
                                    AddressCandidateRow(
                                        candidate = candidate,
                                        selected = index == selectedIndex,
                                        recommended = index == inspection?.recommendedIndex,
                                        onClick = { selectedIndex = index },
                                    )
                                }
                            }
                            val current = inspection
                            if (current != null && current.advice.isNotEmpty()) {
                                Spacer(Modifier.height(2.dp))
                                current.advice.forEach { line ->
                                    Text(
                                        "• $line",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                    )
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            TextButton(
                                onClick = {
                                    copyText("茶室诊断", diagnosticsText())
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("复制诊断信息")
                            }
                        }
                    }

                    Text(
                        "让朋友连同一个 Wi-Fi，扫码或输入上面的网址。如果主机“系统浏览器测试”能打开、但另一台设备不行，优先排查 VPN、访客 Wi-Fi/AP 隔离或路由器防火墙。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "在线 ${roomState.users.size} 人 · 局域网明文传输，请只在可信网络使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        val encodedName = URLEncoder.encode("房主", StandardCharsets.UTF_8.toString())
                        val chatUrl = "http://127.0.0.1:${server.port}/?name=$encodedName&embed=1"
                        context.startActivity(
                            Intent(context, ChatActivity::class.java)
                                .putExtra(ChatActivity.EXTRA_URL, chatUrl),
                        )
                    },
                    enabled = running,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text("进入聊天", fontWeight = FontWeight.SemiBold)
                }

                Text(
                    "当前版本还没有前台服务：测试时请让主机 App 保持前台/亮屏。文件保存在主机 App 私有目录，清空/卸载即删。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HealthLine(label: String, result: ProbeResult?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        val (text, color) = when {
            result == null -> "检测中…" to MaterialTheme.colorScheme.onSurfaceVariant
            result.ok -> "✓ 正常" to MaterialTheme.colorScheme.primary
            else -> "✗ 失败" to MaterialTheme.colorScheme.error
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
    if (result != null && !result.ok) {
        Text(
            result.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
        )
    }
}

@Composable
private fun AddressCandidateRow(
    candidate: LanCandidate,
    selected: Boolean,
    recommended: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(
                "${candidate.kind.label} · ${candidate.interfaceName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                candidate.ip + if (candidate.isPrivate) "" else "（非私网）",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (recommended) {
            Text(
                "推荐",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private suspend fun probeHealth(url: String): ProbeResult = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 2000
        connection.readTimeout = 2000
        connection.requestMethod = "GET"
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }?.take(160).orEmpty()
        ProbeResult(
            ok = code in 200..299,
            detail = "HTTP $code${if (body.isNotBlank()) " $body" else ""}",
        )
    } catch (error: Throwable) {
        ProbeResult(false, "${error::class.java.simpleName}: ${error.message ?: "无详情"}")
    } finally {
        connection?.disconnect()
    }
}
