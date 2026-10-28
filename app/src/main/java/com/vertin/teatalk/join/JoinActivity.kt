package com.vertin.teatalk.join

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.vertin.teatalk.chat.ChatActivity
import com.vertin.teatalk.core.discovery.DiscoveredRoom
import com.vertin.teatalk.discovery.AndroidLanDiscovery
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
import com.vertin.teatalk.ui.TeaSpinner
import com.vertin.teatalk.ui.TeaTalkTheme
import com.vertin.teatalk.ui.TeaTextField
import com.vertin.teatalk.ui.TeaType
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class JoinActivity : ComponentActivity() {
    private val rooms = MutableStateFlow<List<DiscoveredRoom>>(emptyList())
    private val discoveryError = MutableStateFlow<String?>(null)
    private var discovery: AndroidLanDiscovery? = null
    private var collectJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TeaTalkTheme { JoinScreen() }
        }
    }

    override fun onStart() {
        super.onStart()
        startDiscovery()
    }

    override fun onStop() {
        stopDiscovery()
        super.onStop()
    }

    private fun startDiscovery() {
        stopDiscovery()
        rooms.value = emptyList()
        discoveryError.value = null
        val manager = AndroidLanDiscovery(applicationContext)
        runCatching { manager.startScanning() }
            .onSuccess { flow ->
                discovery = manager
                collectJob = lifecycleScope.launch {
                    flow.collectLatest { rooms.value = it }
                }
            }
            .onFailure { error ->
                manager.close()
                discoveryError.value = error.message ?: "无法启动局域网搜索"
            }
    }

    private fun stopDiscovery() {
        collectJob?.cancel()
        collectJob = null
        discovery?.close()
        discovery = null
        rooms.value = emptyList()
    }

    private fun refreshDiscovery() {
        startDiscovery()
    }

    private fun savedName(): String =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_NAME, "").orEmpty()

    private fun saveName(name: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_NAME, name).apply()
    }

    private fun openChat(base: String, nickname: String) {
        val safeName = nickname.trim().take(MAX_NAME).ifBlank { "茶友" }
        saveName(safeName)
        val encoded = URLEncoder.encode(safeName, StandardCharsets.UTF_8.toString())
        val chatUrl = "${base}?name=$encoded&embed=1"
        startActivity(
            Intent(this, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_URL, chatUrl),
        )
    }

    private suspend fun checkHost(base: String): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URI.create(base + "api/health").toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 2500
            connection.readTimeout = 2500
            connection.requestMethod = "GET"
            val code = connection.responseCode
            if (code !in 200..299) return@withContext "主机返回 HTTP $code"
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (json.optString("app") != "teatalk" || json.optInt("protocol") != 1) {
                return@withContext "这个地址不是茶室房间"
            }
            null
        } catch (error: Throwable) {
            "连不上主机：${error.message ?: error::class.java.simpleName}"
        } finally {
            connection?.disconnect()
        }
    }

    private fun normalizeBase(raw: String): String? {
        var candidate = raw.trim()
        if (candidate.isEmpty()) return null
        if (!candidate.startsWith("http://", ignoreCase = true) &&
            !candidate.startsWith("https://", ignoreCase = true)
        ) {
            candidate = "http://$candidate"
        }
        val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        val port = when {
            uri.port != -1 -> uri.port
            scheme == "https" -> 443
            else -> 80
        }
        if (port !in 1..65535) return null
        return "$scheme://$host:$port/"
    }

    @Composable
    private fun JoinScreen() {
        val context = LocalContext.current
        val palette = LocalTeaPalette.current
        val roomList by rooms.collectAsStateWithLifecycle()
        val searchError by discoveryError.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        var nickname by remember { mutableStateOf(savedName().ifBlank { "茶友" }) }
        var manualAddress by remember { mutableStateOf("") }
        var showManual by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var joining by remember { mutableStateOf(false) }

        fun joinBase(base: String) {
            if (joining) return
            joining = true
            error = null
            scope.launch {
                val problem = checkHost(base)
                joining = false
                if (problem == null) {
                    openChat(base, nickname)
                } else {
                    error = problem
                }
            }
        }

        val scanLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val raw = result.data?.getStringExtra(ScanQrActivity.EXTRA_RESULT)
                val base = raw?.let(::normalizeBase)
                if (base == null) {
                    error = "二维码里不是有效的茶室地址"
                } else {
                    showManual = false
                    manualAddress = base
                    joinBase(base)
                }
            }
        }

        val manualBlock: @Composable () -> Unit = {
            Column {
                Spacer(Modifier.height(12.dp))
                TeaTextField(
                    value = manualAddress,
                    onValueChange = { manualAddress = it },
                    placeholder = "例如 192.168.3.46:8520",
                    label = "主机地址",
                    keyboardType = KeyboardType.Uri,
                )
                Spacer(Modifier.height(10.dp))
                TeaPrimaryButton(
                    text = "连接",
                    onClick = {
                        val base = normalizeBase(manualAddress)
                        if (base == null) error = "地址格式不对" else joinBase(base)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        val scanAndManual: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TeaPrimaryButton(
                    text = "扫二维码",
                    onClick = {
                        scanLauncher.launch(Intent(context, ScanQrActivity::class.java))
                    },
                    modifier = Modifier.weight(1f),
                )
                TeaGhostButton(
                    text = if (showManual) "收起地址" else "手动地址",
                    onClick = { showManual = !showManual },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (roomList.isEmpty()) {
            TeaScreen {
                JoinHeader(onBack = { finish() })
                Spacer(Modifier.height(20.dp))
                TeaTextField(
                    value = nickname,
                    onValueChange = { nickname = it.take(MAX_NAME) },
                    placeholder = "茶友",
                    label = "你的昵称",
                )

                if (searchError != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = searchError.orEmpty(),
                        style = TeaType.bodySmall,
                        color = palette.danger,
                    )
                } else {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TeaSpinner(size = 14.dp)
                        Spacer(Modifier.width(10.dp))
                        TeaMeta("正在搜索附近的茶室…")
                    }
                }

                if (joining) {
                    Spacer(Modifier.height(14.dp))
                    JoiningRow()
                }
                error?.let { message ->
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = message,
                        style = TeaType.bodySmall,
                        color = palette.danger,
                    )
                }

                Spacer(Modifier.height(20.dp))
                TeaCard(Modifier.fillMaxWidth()) {
                    Text(
                        text = "还没有搜到茶室",
                        fontFamily = FontFamily.Serif,
                        fontSize = 18.sp,
                        color = palette.ink,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "· 两台设备需要连同一个 Wi-Fi\n· 路由器可能开启了 AP 隔离\n· 也可以直接扫主机屏幕上的二维码",
                        style = TeaType.meta.copy(fontSize = 11.sp, lineHeight = 18.sp),
                        color = palette.inkMuted,
                    )
                }

                Spacer(Modifier.height(20.dp))
                TeaPrimaryButton(
                    text = "扫二维码加入",
                    onClick = {
                        scanLauncher.launch(Intent(context, ScanQrActivity::class.java))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                TeaGhostButton(
                    text = if (showManual) "收起手动地址" else "手动输入地址",
                    onClick = { showManual = !showManual },
                    modifier = Modifier.fillMaxWidth(),
                )
                AnimatedVisibility(visible = showManual) {
                    manualBlock()
                }

                Spacer(Modifier.height(16.dp))
                TeaInlineButton(text = "重新搜索", onClick = { refreshDiscovery() })
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "自动发现需要两台设备在同一 Wi-Fi，且路由器没有开启 AP 隔离。搜不到时用二维码或手动地址。",
                    style = TeaType.meta.copy(fontSize = 11.sp, lineHeight = 18.sp),
                    color = palette.inkMuted,
                )
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = palette.paper,
                contentColor = palette.ink,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .padding(horizontal = 22.dp, vertical = 18.dp),
                ) {
                    JoinHeader(onBack = { finish() })
                    Spacer(Modifier.height(18.dp))
                    TeaTextField(
                        value = nickname,
                        onValueChange = { nickname = it.take(MAX_NAME) },
                        placeholder = "茶友",
                        label = "你的昵称",
                    )
                    Spacer(Modifier.height(18.dp))
                    TeaSectionLabel("附近茶室 · ${roomList.size}")
                    Spacer(Modifier.height(10.dp))
                    if (searchError != null) {
                        Text(
                            text = searchError.orEmpty(),
                            style = TeaType.bodySmall,
                            color = palette.danger,
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TeaSpinner(size = 14.dp)
                            Spacer(Modifier.width(10.dp))
                            TeaMeta("正在搜索附近的茶室…")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) {
                        items(roomList, key = { it.hostId }) { room ->
                            RoomRow(
                                room = room,
                                enabled = !joining,
                                onClick = { joinBase("http://${room.ip}:${room.port}/") },
                            )
                        }
                    }
                    if (joining) {
                        JoiningRow()
                        Spacer(Modifier.height(10.dp))
                    }
                    error?.let { message ->
                        Text(
                            text = message,
                            style = TeaType.bodySmall,
                            color = palette.danger,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    scanAndManual()
                    AnimatedVisibility(visible = showManual) {
                        manualBlock()
                    }
                    Spacer(Modifier.height(6.dp))
                    TeaInlineButton(text = "重新搜索", onClick = { refreshDiscovery() })
                }
            }
        }
    }

    @Composable
    private fun JoinHeader(onBack: () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TeaGlyphButton(glyph = "←", label = "返回", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text(
                text = "加入房间",
                style = TeaType.title,
                color = LocalTeaPalette.current.ink,
            )
        }
    }

    @Composable
    private fun JoiningRow() {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TeaSpinner(size = 14.dp)
            Spacer(Modifier.width(10.dp))
            TeaMeta("正在连接房间…")
        }
    }

    @Composable
    private fun RoomRow(room: DiscoveredRoom, enabled: Boolean, onClick: () -> Unit) {
        val palette = LocalTeaPalette.current
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val background by animateColorAsState(
            targetValue = if (pressed && enabled) palette.sunken else Color.Transparent,
            label = "roomRowBackground",
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(background)
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = room.room,
                        fontFamily = FontFamily.Serif,
                        fontSize = 17.sp,
                        color = palette.ink,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${room.ip}:${room.port}",
                        style = TeaType.meta,
                        color = palette.inkMuted,
                    )
                }
                Text(
                    text = if (enabled) "加入" else "加入中",
                    style = TeaType.meta,
                    color = if (enabled) palette.clay else palette.inkMuted,
                )
            }
            TeaRule()
        }
    }

    companion object {
        private const val PREFS = "teatalk"
        private const val KEY_NAME = "join_name"
        private const val MAX_NAME = 20
    }
}
