package com.vertin.teatalk

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.vertin.teatalk.host.HostActivity
import com.vertin.teatalk.join.JoinActivity
import com.vertin.teatalk.ui.TeaTalkTheme
import com.vertin.teatalk.ui.glass.setTeaSystemBarsLight
import com.vertin.teatalk.ui.glass.GlassButton
import com.vertin.teatalk.ui.glass.GlassCard
import com.vertin.teatalk.ui.glass.GlassSurface
import com.vertin.teatalk.ui.glass.TeaAmber
import com.vertin.teatalk.ui.glass.TeaGlassScaffold
import com.vertin.teatalk.ui.glass.TeaInk
import com.vertin.teatalk.ui.glass.TeaMuted
import com.vertin.teatalk.ui.glass.TeaTeal

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setTeaSystemBarsLight()
        setContent {
            TeaTalkTheme {
                TeaGlassScaffold { backdrop -> HomeScreen(backdrop) }
            }
        }
    }

    @Composable
    private fun HomeScreen(backdrop: Backdrop) {
        val context = LocalContext.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GlassSurface(
                    backdrop = backdrop,
                    modifier = Modifier.size(58.dp),
                    cornerRadius = 19.dp,
                    tint = Color(0xFFF6EBDD).copy(alpha = 0.34f),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "茶",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = TeaTeal,
                        )
                    }
                }
                Column {
                    Text(
                        "茶室",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = TeaInk,
                    )
                    Text(
                        "TeaTalk · 局域网慢慢聊",
                        style = MaterialTheme.typography.labelLarge,
                        color = TeaMuted,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "同一片 Wi-Fi，\n慢慢聊。",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = TeaInk,
                lineHeight = MaterialTheme.typography.displaySmall.lineHeight,
            )
            Text(
                "不用登录，不用外网。开一间房，朋友自动发现、扫码或输地址加入。",
                style = MaterialTheme.typography.bodyLarge,
                color = TeaMuted,
            )

            GlassCard(
                backdrop = backdrop,
                modifier = Modifier.fillMaxWidth(),
                tint = Color.White.copy(alpha = 0.24f),
            ) {
                Text(
                    "开一间房",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TeaInk,
                )
                Text(
                    "这台手机作为主机，显示二维码和局域网地址；其他手机自动搜索或扫码加入。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TeaMuted,
                )
                GlassButton(
                    backdrop = backdrop,
                    onClick = { context.startActivity(Intent(context, HostActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("开房间", fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }

            GlassCard(
                backdrop = backdrop,
                modifier = Modifier.fillMaxWidth(),
                tint = Color(0xFFF6EBDD).copy(alpha = 0.22f),
            ) {
                Text(
                    "加入房间",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TeaInk,
                )
                Text(
                    "自动搜索同一 Wi-Fi 下的茶室；搜不到也可以扫主机二维码或手动输入地址。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TeaMuted,
                )
                GlassButton(
                    backdrop = backdrop,
                    onClick = { context.startActivity(Intent(context, JoinActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth(),
                    prominent = false,
                ) {
                    Text("加入房间", fontWeight = FontWeight.SemiBold, color = TeaInk)
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "茶",
                    style = MaterialTheme.typography.labelLarge,
                    color = TeaAmber,
                )
                Text(
                    "版本 ${BuildConfig.VERSION_NAME} · 局域网明文传输",
                    style = MaterialTheme.typography.bodySmall,
                    color = TeaMuted,
                )
            }
            Text(
                "开房后会自动开启前台服务保活；可在通知栏看到「茶室正在开房」。",
                style = MaterialTheme.typography.bodySmall,
                color = TeaMuted,
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}
