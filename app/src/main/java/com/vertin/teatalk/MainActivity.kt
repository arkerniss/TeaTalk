package com.vertin.teatalk

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.vertin.teatalk.host.HostActivity
import com.vertin.teatalk.join.JoinActivity
import com.vertin.teatalk.ui.LocalTeaPalette
import com.vertin.teatalk.ui.TeaActionRow
import com.vertin.teatalk.ui.TeaRule
import com.vertin.teatalk.ui.TeaTagline
import com.vertin.teatalk.ui.TeaTalkTheme
import com.vertin.teatalk.ui.TeaType
import com.vertin.teatalk.ui.TeaWordmark

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TeaTalkTheme { HomeScreen() }
        }
    }

    @Composable
    private fun HomeScreen() {
        val context = LocalContext.current
        val palette = LocalTeaPalette.current
        val mono = TeaType.meta.copy(letterSpacing = 0.06.em)
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = palette.paper,
            contentColor = palette.ink,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = 22.dp, vertical = 20.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Spacer(Modifier.height(24.dp))
                    TeaWordmark("茶室")
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("TeaTalk", style = mono, color = palette.inkMuted)
                        Text(" · ", style = mono, color = palette.inkMuted)
                        Text("局域网优先", style = mono, color = palette.inkMuted)
                    }
                    Spacer(Modifier.height(14.dp))
                    TeaTagline("同一片 Wi-Fi，慢慢聊。")
                    Spacer(Modifier.height(24.dp))
                    TeaActionRow(
                        index = "01",
                        title = "开一间房",
                        description = "这台手机当主机，朋友扫码或输地址加入。",
                        onClick = {
                            context.startActivity(Intent(context, HostActivity::class.java))
                        },
                    )
                    TeaActionRow(
                        index = "02",
                        title = "加入房间",
                        description = "自动搜索同一 Wi-Fi 下的茶室，也能扫码。",
                        onClick = {
                            context.startActivity(Intent(context, JoinActivity::class.java))
                        },
                    )
                    Spacer(Modifier.height(24.dp))
                }
                TeaRule()
                Spacer(Modifier.height(14.dp))
                Text(
                    "版本 ${BuildConfig.VERSION_NAME}",
                    style = TeaType.meta.copy(fontSize = 11.sp),
                    color = palette.inkMuted,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "开房后自动前台保活 · 局域网明文传输",
                    style = TeaType.meta.copy(fontSize = 11.sp),
                    color = palette.inkMuted,
                )
            }
        }
    }
}
