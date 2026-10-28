package com.vertin.teatalk.host

import androidx.activity.compose.BackHandler
import com.vertin.teatalk.BuildConfig
import com.vertin.teatalk.ui.LocalTeaPalette
import com.vertin.teatalk.ui.TeaCard
import com.vertin.teatalk.ui.TeaGhostButton
import com.vertin.teatalk.ui.TeaGlyphButton
import com.vertin.teatalk.ui.TeaInlineButton
import com.vertin.teatalk.ui.TeaMeta
import com.vertin.teatalk.ui.TeaPrimaryButton
import com.vertin.teatalk.ui.TeaRule
import com.vertin.teatalk.ui.TeaScreen
import com.vertin.teatalk.ui.TeaSectionLabel
import com.vertin.teatalk.ui.TeaShapes
import com.vertin.teatalk.ui.TeaSpinner
import com.vertin.teatalk.ui.TeaStatusDot
import com.vertin.teatalk.ui.TeaTagline
import com.vertin.teatalk.ui.TeaTalkTheme
import com.vertin.teatalk.ui.TeaType
import com.vertin.teatalk.ui.TeaWordmark
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vertin.teatalk.chat.ChatActivity
import com.vertin.teatalk.ui.LightTeaPalette
import com.vertin.teatalk.util.LanCandidate
import com.vertin.teatalk.util.NetworkAddresses
import com.vertin.teatalk.util.NetworkInspection
import com.vertin.teatalk.util.generateQrBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private data class ProbeResult(
    val ok: Boolean,
    val detail: String,
)

// The QR panel is the one surface that stays white in both themes so codes keep
// scanning, so its contents read the light palette explicitly instead of the
// ambient one (dark-mode ink on white would be unreadable).
private val QrInk = LightTeaPalette.ink
private val QrInkMuted = LightTeaPalette.inkMuted
private val QrRule = LightTeaPalette.rule
private val QrDanger = LightTeaPalette.danger

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
        val notificationPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { }
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val server = remember { HostRuntime.ensureServer(applicationContext) }
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
            runCatching {
                withContext(Dispatchers.IO) { HostRuntime.start(applicationContext) }
                running = true
            }.onFailure { error = it.message ?: it::class.java.simpleName }
        }

        LaunchedEffect(running) {
            val serviceIntent = Intent(context, HostKeepAliveService::class.java)
            if (running) {
                runCatching { ContextCompat.startForegroundService(context, serviceIntent) }
            } else {
                runCatching { context.stopService(serviceIntent) }
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
                if (registered) runCatching { manager.unregisterNetworkCallback(callback) }
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

        val palette = LocalTeaPalette.current

        TeaScreen {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TeaGlyphButton(
                    glyph = "←",
                    label = "返回首页",
                    onClick = { finish() },
                )
                Spacer(Modifier.weight(1f))
                if (running) {
                    TeaInlineButton(
                        text = "关闭房间",
                        color = palette.danger,
                        onClick = {
                            HostRuntime.stop()
                            context.stopService(Intent(context, HostKeepAliveService::class.java))
                            finish()
                        },
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            TeaWordmark("茶室", style = TeaType.title)
            Spacer(Modifier.height(8.dp))
            TeaTagline("同一片 Wi-Fi，慢慢聊。")
            Spacer(Modifier.height(24.dp))

            if (!running) {
                TeaCard(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (error == null) {
                            TeaSpinner(size = 20.dp)
                            Spacer(Modifier.height(14.dp))
                            TeaMeta("正在启动房间…")
                        } else {
                            Text(
                                text = "房间启动失败",
                                style = TeaType.heading,
                                color = palette.danger,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = error ?: "未知错误",
                                style = TeaType.meta,
                                color = palette.inkMuted,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(18.dp))
                            TeaPrimaryButton(
                                text = "重试",
                                onClick = { attempt++ },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TeaStatusDot(active = true)
                    Spacer(Modifier.width(10.dp))
                    TeaMeta("房间已开启", color = palette.clay)
                }
                Spacer(Modifier.height(14.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = TeaShapes.lg,
                    color = palette.qrSurface,
                    contentColor = QrInk,
                    border = BorderStroke(1.dp, QrRule),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (qr != null && lanUrl != null) {
                            Image(
                                bitmap = qr.asImageBitmap(),
                                contentDescription = "房间二维码",
                                modifier = Modifier.size(220.dp),
                            )
                            Spacer(Modifier.height(16.dp))
                            SelectionContainer {
                                Text(
                                    text = lanUrl,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 15.sp,
                                    lineHeight = 21.sp,
                                    color = QrInk,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "${selected.kind.label} · ${selected.interfaceName}",
                                style = TeaType.meta,
                                color = QrInkMuted,
                                textAlign = TextAlign.Center,
                            )
                        } else {
                            Text(
                                text = "未检测到可用的局域网地址",
                                style = TeaType.heading,
                                color = QrDanger,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "请先连接 Wi-Fi，或打开手机热点后再刷新网络。",
                                style = TeaType.bodySmall,
                                color = QrInkMuted,
                                textAlign = TextAlign.Center,
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                        TeaRule()
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (lanUrl != null) {
                                TeaInlineButton(
                                    text = "复制链接",
                                    onClick = { copyText("茶室房间", lanUrl) },
                                )
                                Text(" · ", style = TeaType.meta, color = QrRule)
                                TeaInlineButton(
                                    text = "浏览器测试",
                                    onClick = {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(lanUrl)))
                                        }
                                    },
                                )
                                Text(" · ", style = TeaType.meta, color = QrRule)
                            }
                            TeaInlineButton(
                                text = "刷新网络",
                                onClick = { refreshTick++ },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                TeaSectionLabel("本机自检")
                Spacer(Modifier.height(8.dp))
                HealthLine("127.0.0.1:${server.port}", localHealth)
                if (selected != null) {
                    HealthLine("${selected.ip}:${server.port}", lanHealth)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "旧版测试端口是 18520，新版是 ${server.port}；请确认另一台设备地址里是 :${server.port}。",
                    style = TeaType.meta.copy(fontSize = 11.sp, lineHeight = 18.sp),
                    color = palette.inkMuted,
                )

                Spacer(Modifier.height(24.dp))
                TeaSectionLabel("网络地址")
                Spacer(Modifier.height(6.dp))
                if (candidates.isEmpty()) {
                    Text(
                        text = "没有可用 IPv4 地址。",
                        style = TeaType.meta,
                        color = palette.danger,
                        modifier = Modifier.padding(vertical = 10.dp),
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
                    Spacer(Modifier.height(10.dp))
                    current.advice.forEach { line ->
                        Text(
                            text = "· $line",
                            style = TeaType.meta.copy(fontSize = 11.sp, lineHeight = 18.sp),
                            color = palette.inkSoft,
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                TeaGhostButton(
                    text = "复制诊断信息",
                    onClick = { copyText("茶室诊断", diagnosticsText()) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(20.dp))
                Text(
                    text = "让朋友连同一个 Wi-Fi，扫码或输入上面的网址。如果主机“系统浏览器测试”能打开、但另一台设备不行，优先排查 VPN、访客 Wi-Fi/AP 隔离或路由器防火墙。",
                    style = TeaType.bodySmall,
                    color = palette.inkSoft,
                )
                Spacer(Modifier.height(12.dp))
                TeaMeta("在线 ${roomState.users.size} 人 · 局域网明文传输，请只在可信网络使用。")
            }

            Spacer(Modifier.height(28.dp))
            TeaPrimaryButton(
                text = "进入聊天",
                onClick = {
                    val encodedName = URLEncoder.encode("房主", StandardCharsets.UTF_8.toString())
                    val chatUrl = "http://127.0.0.1:${server.port}/?name=$encodedName&embed=1"
                    context.startActivity(
                        Intent(context, ChatActivity::class.java)
                            .putExtra(ChatActivity.EXTRA_URL, chatUrl),
                    )
                },
                enabled = running,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "开房后会自动开启前台服务保活：切到后台或锁屏也能继续开房，通知栏会显示「茶室正在开房」。极端省电策略下系统仍可能停止服务。文件保存在主机 App 私有目录，清空/卸载即删。",
                style = TeaType.meta.copy(fontSize = 11.sp, lineHeight = 18.sp),
                color = palette.inkMuted,
            )
        }
    }
}

@Composable
private fun HealthLine(label: String, result: ProbeResult?) {
    val palette = LocalTeaPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = TeaType.meta,
                color = palette.ink,
                modifier = Modifier.weight(1f),
            )
            val status = when {
                result == null -> "检测中…" to palette.inkMuted
                result.ok -> "✓ 正常" to palette.clay
                else -> "✗ 失败" to palette.danger
            }
            Text(text = status.first, style = TeaType.meta, color = status.second)
        }
        if (result != null && !result.ok) {
            Text(
                text = result.detail,
                style = TeaType.meta.copy(fontSize = 11.sp),
                color = palette.danger,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun AddressCandidateRow(
    candidate: LanCandidate,
    selected: Boolean,
    recommended: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalTeaPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = if (pressed) palette.sunken else Color.Transparent,
        label = "candidateRowBackground",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .then(
                        if (selected) {
                            Modifier.background(palette.clay)
                        } else {
                            Modifier.border(1.dp, palette.rule, CircleShape)
                        },
                    ),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = candidate.ip + if (candidate.isPrivate) "" else "（非私网）",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = palette.ink,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "${candidate.kind.label} · ${candidate.interfaceName}",
                    style = TeaType.meta.copy(fontSize = 11.sp),
                    color = palette.inkMuted,
                )
            }
            if (recommended) {
                Text(
                    text = "推荐",
                    style = TeaType.meta,
                    color = palette.clay,
                )
            }
        }
        TeaRule()
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
