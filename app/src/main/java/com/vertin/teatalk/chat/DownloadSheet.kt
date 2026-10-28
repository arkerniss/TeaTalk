package com.vertin.teatalk.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vertin.teatalk.ui.LocalTeaPalette
import com.vertin.teatalk.ui.TeaInlineButton
import com.vertin.teatalk.ui.TeaRule
import com.vertin.teatalk.ui.TeaShapes
import com.vertin.teatalk.ui.TeaType
import java.util.Locale

/** 20.dp top corners — the same radius as [TeaShapes.lg], squared at the screen edge. */
private val SheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

/**
 * Download surface drawn over the chat WebView: a bottom sheet while expanded, a single
 * floating pill while collapsed. Colors, type, radii and hairline rules all come from
 * the Paper & Clay design system; there are no cards, shadows or looping animations.
 *
 * The host (ChatActivity) already applies the window insets to the overlay's parent, so
 * this composable intentionally does not add [androidx.compose.foundation.layout.safeDrawingPadding]
 * again — that would double the bottom inset. The pill's clearance above the page
 * composer is applied by the host as the overlay View's bottom margin.
 *
 * Collapsed, this composable must stay as small as the pill: the host sizes the overlay
 * View to WRAP_CONTENT so the untouched WebView keeps every touch that is not the pill.
 *
 * @param downloads live task list in creation order (newest last).
 * @param expanded whether the full sheet is showing.
 */
@Composable
fun DownloadOverlay(
    downloads: List<DownloadTask>,
    expanded: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onCancel: (Long) -> Unit,
    onOpen: (DownloadTask) -> Unit,
    onDismissTask: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (downloads.isEmpty()) return

    if (expanded) {
        BoxWithConstraints(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val rowCap: Dp =
                if (maxHeight.value.isFinite()) {
                    (maxHeight * 0.45f).coerceAtLeast(120.dp)
                } else {
                    300.dp
                }
            DownloadSheet(
                downloads = downloads,
                rowCap = rowCap,
                onCollapse = onCollapse,
                onCancel = onCancel,
                onOpen = onOpen,
                onDismissTask = onDismissTask,
            )
        }
    } else {
        DownloadPill(downloads = downloads, onExpand = onExpand, modifier = modifier)
    }
}

@Composable
private fun DownloadSheet(
    downloads: List<DownloadTask>,
    rowCap: Dp,
    onCollapse: () -> Unit,
    onCancel: (Long) -> Unit,
    onOpen: (DownloadTask) -> Unit,
    onDismissTask: (Long) -> Unit,
) {
    val palette = LocalTeaPalette.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .blockTouchFallThrough(),
        shape = SheetShape,
        color = palette.paper,
        contentColor = palette.ink,
    ) {
        Column(Modifier.fillMaxWidth()) {
            // 1px hairline top border, clipped by the surface's rounded corners.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(palette.rule),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "下载", style = TeaType.title, color = palette.ink)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${downloads.size} 个文件",
                    style = TeaType.meta,
                    color = palette.inkMuted,
                )
                Spacer(Modifier.width(4.dp))
                TeaInlineButton(
                    text = "收起",
                    onClick = onCollapse,
                    color = palette.inkMuted,
                )
            }
            TeaRule()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = rowCap)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
            ) {
                downloads.forEachIndexed { index, task ->
                    if (index > 0) TeaRule()
                    DownloadRow(
                        task = task,
                        onCancel = onCancel,
                        onOpen = onOpen,
                        onDismissTask = onDismissTask,
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    task: DownloadTask,
    onCancel: (Long) -> Unit,
    onOpen: (DownloadTask) -> Unit,
    onDismissTask: (Long) -> Unit,
) {
    val palette = LocalTeaPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = task.name,
                    style = TeaType.body,
                    color = palette.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = statusText(task),
                    style = TeaType.meta,
                    color = if (task.state == DownloadState.FAILED) palette.danger else palette.inkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (task.state == DownloadState.RUNNING) {
                    TeaInlineButton(
                        text = "取消",
                        onClick = { onCancel(task.id) },
                        color = palette.danger,
                    )
                } else {
                    if (task.contentUri != null) {
                        TeaInlineButton(
                            text = "打开",
                            onClick = { onOpen(task) },
                        )
                    }
                    TeaInlineButton(
                        text = "移除",
                        onClick = { onDismissTask(task.id) },
                        color = palette.inkMuted,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        ProgressTrack(fraction = progressFraction(task))
    }
}

/** 2.dp track in deep with a clay fill; unknown length is a static clay stub, never animated. */
@Composable
private fun ProgressTrack(fraction: Float?) {
    val palette = LocalTeaPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(palette.deep),
    ) {
        val fill = if (fraction == null) 0.3f else fraction.coerceIn(0f, 1f)
        if (fill > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fill)
                    .fillMaxHeight()
                    .background(palette.clay),
            )
        }
    }
}

@Composable
private fun DownloadPill(
    downloads: List<DownloadTask>,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTeaPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = if (pressed) palette.sunken else palette.raised,
        label = "downloadPillBackground",
    )
    Box(
        // Deliberately NOT fillMaxWidth: the collapsed overlay View is WRAP_CONTENT so the
        // WebView underneath keeps the touches beside the pill.
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(TeaShapes.pill)
                .background(background)
                .border(1.dp, palette.rule, TeaShapes.pill)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = onExpand,
                )
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "↓", style = TeaType.meta, color = palette.clay)
            Spacer(Modifier.width(7.dp))
            Text(
                text = pillLabel(downloads),
                style = TeaType.meta,
                color = palette.inkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun pillLabel(downloads: List<DownloadTask>): String {
    val running = downloads.count { it.state == DownloadState.RUNNING }
    if (running > 0) return "$running 个下载中"
    return when (val latest = downloads.lastOrNull()) {
        null -> "下载"
        else -> when (latest.state) {
            DownloadState.DONE -> "已下载 ${latest.name}"
            DownloadState.FAILED -> "下载失败"
            DownloadState.CANCELED -> "已取消"
            DownloadState.RUNNING -> "$running 个下载中"
        }
    }
}

/**
 * The overlay is a sibling View stacked over the WebView, so a tap that Compose does not
 * handle would fall through to the page underneath. The sheet is an opaque surface:
 * consume every pointer it receives. Children are hit before this node (Main pass goes
 * leaf-to-root), so rows, buttons and scrolling keep working.
 */
private fun Modifier.blockTouchFallThrough(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}

private fun statusText(task: DownloadTask): String = when (task.state) {
    DownloadState.RUNNING -> if (task.totalBytes > 0L) {
        "已下载 ${formatBytes(task.bytesRead)} / ${formatBytes(task.totalBytes)}"
    } else {
        "已下载 ${formatBytes(task.bytesRead)}"
    }

    DownloadState.DONE -> task.savedTo?.let { "已保存 · $it" } ?: "已保存"
    DownloadState.FAILED -> task.error ?: "下载失败"
    DownloadState.CANCELED -> "已取消"
}

private fun progressFraction(task: DownloadTask): Float? = when (task.state) {
    DownloadState.DONE -> 1f
    DownloadState.RUNNING -> if (task.totalBytes > 0L) {
        (task.bytesRead.toFloat() / task.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        null // Unknown length: a static clay stub instead of an indeterminate animation.
    }

    DownloadState.FAILED, DownloadState.CANCELED -> 0f
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return "—"
    if (bytes < 1024L) return "$bytes B"
    val kilobytes = bytes / 1024.0
    if (kilobytes < 1024.0) return String.format(Locale.US, "%.1f KB", kilobytes)
    val megabytes = kilobytes / 1024.0
    if (megabytes < 1024.0) return String.format(Locale.US, "%.1f MB", megabytes)
    return String.format(Locale.US, "%.2f GB", megabytes / 1024.0)
}
