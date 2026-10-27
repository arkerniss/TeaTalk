package com.vertin.teatalk.join

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.vertin.teatalk.chat.ChatActivity
import com.vertin.teatalk.core.discovery.DiscoveredRoom
import com.vertin.teatalk.discovery.AndroidLanDiscovery
import com.vertin.teatalk.ui.TeaTalkTheme
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

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (roomList.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    JoinHeader(onBack = { finish() })
                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { nickname = it.take(MAX_NAME) },
                        label = { Text("你的昵称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SearchStatus(searchError)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("正在搜索附近的茶室…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (joining) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("正在连接房间…")
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = {
                            scanLauncher.launch(Intent(context, ScanQrActivity::class.java))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("扫二维码加入") }
                    OutlinedButton(
                        onClick = { showManual = !showManual },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (showManual) "收起手动地址" else "手动输入地址") }
                    if (showManual) {
                        OutlinedTextField(
                            value = manualAddress,
                            onValueChange = { manualAddress = it },
                            label = { Text("例如 192.168.3.46:8520") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                val base = normalizeBase(manualAddress)
                                if (base == null) error = "地址格式不对" else joinBase(base)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("连接") }
                    }
                    TextButton(onClick = { refreshDiscovery() }) { Text("重新搜索") }
                    Text(
                        "自动发现需要两台设备在同一 Wi-Fi，且路由器没有开启 AP 隔离。搜不到时用二维码或手动地址。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    JoinHeader(onBack = { finish() })
                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { nickname = it.take(MAX_NAME) },
                        label = { Text("你的昵称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "附近茶室（${roomList.size}）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    SearchStatus(searchError)
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(roomList, key = { it.hostId }) { room ->
                            RoomCard(room = room, enabled = !joining) {
                                joinBase("http://${room.ip}:${room.port}/")
                            }
                        }
                    }
                    if (joining) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("正在连接房间…")
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scanLauncher.launch(Intent(context, ScanQrActivity::class.java))
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("扫二维码") }
                        OutlinedButton(
                            onClick = { showManual = !showManual },
                            modifier = Modifier.weight(1f),
                        ) { Text("手动地址") }
                    }
                    if (showManual) {
                        OutlinedTextField(
                            value = manualAddress,
                            onValueChange = { manualAddress = it },
                            label = { Text("例如 192.168.3.46:8520") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                val base = normalizeBase(manualAddress)
                                if (base == null) error = "地址格式不对" else joinBase(base)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("连接") }
                    }
                    TextButton(onClick = { refreshDiscovery() }) { Text("重新搜索") }
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
            TextButton(onClick = onBack) { Text("← 返回") }
            Text(
                "加入房间",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }
    }

    @Composable
    private fun SearchStatus(searchError: String?) {
        if (searchError != null) {
            Text(
                searchError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    @Composable
    private fun RoomCard(room: DiscoveredRoom, enabled: Boolean, onClick: () -> Unit) {
        Card(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(room.room, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${room.ip}:${room.port}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(if (enabled) "在线" else "加入中", color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    companion object {
        private const val PREFS = "teatalk"
        private const val KEY_NAME = "join_name"
        private const val MAX_NAME = 20
    }
}
