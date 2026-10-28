package com.vertin.teatalk

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import com.vertin.teatalk.host.HostActivity
import com.vertin.teatalk.join.JoinActivity
import com.vertin.teatalk.ui.LocalTeaPalette
import com.vertin.teatalk.ui.TeaActionRow
import com.vertin.teatalk.ui.TeaInlineButton
import com.vertin.teatalk.ui.TeaRule
import com.vertin.teatalk.ui.TeaSectionLabel
import com.vertin.teatalk.ui.TeaSettings
import com.vertin.teatalk.ui.TeaShapes
import com.vertin.teatalk.ui.TeaTagline
import com.vertin.teatalk.ui.TeaTalkTheme
import com.vertin.teatalk.ui.TeaThemeMode
import com.vertin.teatalk.ui.TeaType
import com.vertin.teatalk.ui.TeaWordmark

/** Bottom-sheet shape: [TeaShapes.lg]'s 20.dp radius, top corners only. */
private val SettingsSheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            // The preference is the source of truth on create; selecting a mode updates this
            // state so the home screen (and the open sheet) re-theme on the spot.
            var mode by remember { mutableStateOf(TeaSettings.themeMode(context)) }
            val dark = if (mode == TeaThemeMode.SYSTEM) {
                isSystemInDarkTheme()
            } else {
                mode == TeaThemeMode.DARK
            }
            LaunchedEffect(dark) {
                val controller = WindowCompat.getInsetsController(
                    this@MainActivity.window,
                    this@MainActivity.window.decorView,
                )
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
            TeaTalkTheme(darkTheme = dark) {
                HomeScreen(
                    mode = mode,
                    onModeSelected = { selected ->
                        TeaSettings.setThemeMode(context, selected)
                        mode = selected
                    },
                )
            }
        }
    }

    @Composable
    private fun HomeScreen(
        mode: TeaThemeMode,
        onModeSelected: (TeaThemeMode) -> Unit,
    ) {
        val context = LocalContext.current
        val palette = LocalTeaPalette.current
        val mono = TeaType.meta.copy(letterSpacing = 0.06.em)
        var showSettings by remember { mutableStateOf(false) }
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TeaWordmark("茶室")
                        Spacer(Modifier.weight(1f))
                        SettingsEntry(onClick = { showSettings = true })
                    }
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
        if (showSettings) {
            SettingsSheet(
                mode = mode,
                onSelect = onModeSelected,
                onDismiss = { showSettings = false },
            )
        }
    }

    /**
     * Header entry: the design language has no icon set (and no emoji glyphs), so "设置" is
     * set in the mono voice inside a hairline pill — the same quiet weight as the mono meta
     * row beneath it, but tall enough (44.dp) to read as a real control rather than a link.
     */
    @Composable
    private fun SettingsEntry(onClick: () -> Unit) {
        val palette = LocalTeaPalette.current
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val background by animateColorAsState(
            targetValue = if (pressed) palette.sunken else Color.Transparent,
            label = "settingsEntryBackground",
        )
        Box(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clip(TeaShapes.pill)
                .background(background)
                .border(1.dp, palette.rule, TeaShapes.pill)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "设置",
                style = TeaType.meta.copy(letterSpacing = 0.06.em),
                color = palette.inkSoft,
            )
        }
    }

    /**
     * Appearance sheet: a paper panel pinned to the bottom, same anatomy as the web app's
     * sheets — grabber, mono header with a "关闭" action, hairline rules instead of cards.
     */
    @Composable
    private fun SettingsSheet(
        mode: TeaThemeMode,
        onSelect: (TeaThemeMode) -> Unit,
        onDismiss: () -> Unit,
    ) {
        val palette = LocalTeaPalette.current
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                // Draw edge to edge so the paper can reach the screen bottom; the sheet then
                // pads itself by the bottom safe-drawing inset.
                decorFitsSystemWindows = false,
            ),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // The dialog window dims the page by itself; this transparent layer turns a
                // tap on that dimmed area into a dismiss (Compose's built-in outside-tap
                // detection cannot see past a full-bleed dialog layout).
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        ),
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                    shape = SettingsSheetShape,
                    color = palette.paper,
                    contentColor = palette.ink,
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(palette.rule),
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(
                                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
                                )
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp)
                                .padding(top = 9.dp, bottom = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(34.dp)
                                    .height(3.dp)
                                    .clip(TeaShapes.pill)
                                    .background(palette.deep),
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "设置",
                                    style = TeaType.label,
                                    color = palette.inkMuted,
                                )
                                Spacer(Modifier.weight(1f))
                                Box(
                                    modifier = Modifier.heightIn(min = 44.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    TeaInlineButton(
                                        text = "关闭",
                                        onClick = onDismiss,
                                        color = palette.inkMuted,
                                    )
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            TeaRule()
                            Spacer(Modifier.height(18.dp))
                            TeaSectionLabel("外观")
                            Spacer(Modifier.height(4.dp))
                            TeaThemeMode.entries.forEachIndexed { index, candidate ->
                                ThemeModeRow(
                                    mode = candidate,
                                    isSelected = candidate == mode,
                                    showDivider = index != TeaThemeMode.entries.lastIndex,
                                    onSelect = { onSelect(candidate) },
                                )
                            }
                            Spacer(Modifier.height(20.dp))
                            Text(
                                text = "聊天页和原生页面都会跟着变。\n" +
                                    "已经打开的聊天页要重新进一次才会变。",
                                style = TeaType.meta.copy(fontSize = 11.sp, lineHeight = 18.sp),
                                color = palette.inkMuted,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }

    /** One appearance option: label + hint on the left, a hand-drawn selection dot on the right. */
    @Composable
    private fun ThemeModeRow(
        mode: TeaThemeMode,
        isSelected: Boolean,
        showDivider: Boolean,
        onSelect: () -> Unit,
    ) {
        val palette = LocalTeaPalette.current
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val background by animateColorAsState(
            targetValue = if (pressed) palette.sunken else Color.Transparent,
            label = "themeModeRowBackground",
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .background(background)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.RadioButton,
                        onClick = onSelect,
                    )
                    .semantics { selected = isSelected }
                    .padding(vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = mode.label,
                        style = TeaType.body,
                        color = palette.ink,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = mode.hint,
                        style = TeaType.meta.copy(fontSize = 11.sp),
                        color = palette.inkMuted,
                    )
                }
                Spacer(Modifier.width(16.dp))
                SelectionDot(isSelected)
            }
            if (showDivider) {
                TeaRule()
            }
        }
    }

    /** 20.dp radio mark: a hand-drawn ring using palette colours only — no material radio control. */
    @Composable
    private fun SelectionDot(selected: Boolean) {
        val palette = LocalTeaPalette.current
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (selected) palette.clay else Color.Transparent)
                .border(1.dp, if (selected) palette.clay else palette.rule, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(palette.clayInk),
                )
            }
        }
    }
}
