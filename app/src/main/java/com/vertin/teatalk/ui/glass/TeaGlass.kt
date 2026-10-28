package com.vertin.teatalk.ui.glass

import android.view.Window
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

val TeaInk: Color = Color(0xFF14201F)
val TeaMuted: Color = Color(0xFF536562)
val TeaTeal: Color = Color(0xFF00696D)
val TeaAmber: Color = Color(0xFFB8732E)
val TeaDanger: Color = Color(0xFFB42318)

private val ScreenBrush = Brush.linearGradient(
    colors = listOf(
        Color(0xFFF8F3E8),
        Color(0xFFEAF4ED),
        Color(0xFFF3EADF),
    ),
    start = Offset.Zero,
    end = Offset(1100f, 1800f),
)

/**
 * Liquid-glass screen shell: an ambient tea backdrop is recorded once and every
 * glass surface samples it. The chat WebView is untouched and lives outside this
 * native shell.
 */
@Composable
fun TeaGlassScaffold(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(Backdrop) -> Unit,
) {
    val backdrop = rememberLayerBackdrop()
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop),
        ) {
            AmbientTeaBackdrop()
        }
        content(backdrop)
    }
}

@Composable
private fun AmbientTeaBackdrop() {
    Box(Modifier.fillMaxSize().background(ScreenBrush)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x9932A79A), Color(0x0032A79A)),
                    center = Offset(w * 0.12f, h * 0.13f),
                    radius = w * 0.68f,
                ),
                radius = w * 0.68f,
                center = Offset(w * 0.12f, h * 0.13f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x66E7B76D), Color(0x00E7B76D)),
                    center = Offset(w * 0.92f, h * 0.18f),
                    radius = w * 0.52f,
                ),
                radius = w * 0.52f,
                center = Offset(w * 0.92f, h * 0.18f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x660B6D61), Color(0x000B6D61)),
                    center = Offset(w * 0.94f, h * 0.84f),
                    radius = w * 0.72f,
                ),
                radius = w * 0.72f,
                center = Offset(w * 0.94f, h * 0.84f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x5535B8A6), Color(0x0035B8A6)),
                    center = Offset(w * 0.05f, h * 0.86f),
                    radius = w * 0.58f,
                ),
                radius = w * 0.58f,
                center = Offset(w * 0.05f, h * 0.86f),
            )

            // Faint tea ripples in the paper.
            repeat(3) { i ->
                val radius = w * (0.34f + i * 0.075f)
                drawCircle(
                    color = Color(0x180E3B35),
                    radius = radius,
                    center = Offset(w * 0.84f, h * 0.44f),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }

            // A loose steam/leaf line, kept very quiet so text stays readable.
            val steam = Path().apply {
                moveTo(w * 0.14f, h * 0.76f)
                cubicTo(w * 0.24f, h * 0.64f, w * 0.08f, h * 0.54f, w * 0.22f, h * 0.40f)
            }
            drawPath(
                path = steam,
                color = Color(0x264F9F91),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
fun GlassSurface(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 26.dp,
    tint: Color = Color.White.copy(alpha = 0.22f),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape: Shape = RoundedCornerShape(cornerRadius)
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }
    Box(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(12.dp.toPx())
                    lens(20.dp.toPx(), 30.dp.toPx(), depthEffect = true)
                },
                highlight = { Highlight.Default.copy(alpha = 0.82f) },
                shadow = { Shadow.Default.copy(radius = 22.dp, alpha = 0.62f) },
                onDrawSurface = { drawRect(tint) },
            )
            .clip(shape)
            .then(clickableModifier)
            .padding(contentPadding),
    ) {
        content()
    }
}

@Composable
fun GlassCard(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 26.dp,
    tint: Color = Color.White.copy(alpha = 0.25f),
    contentPadding: PaddingValues = PaddingValues(20.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    GlassSurface(
        backdrop = backdrop,
        modifier = modifier,
        cornerRadius = cornerRadius,
        tint = tint,
        onClick = onClick,
    ) {
        androidx.compose.foundation.layout.Column(
            Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
fun GlassButton(
    backdrop: Backdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    prominent: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val shape: Shape = RoundedCornerShape(50)
    val tint = if (prominent) {
        TeaTeal.copy(alpha = 0.80f)
    } else {
        Color.White.copy(alpha = 0.26f)
    }
    Row(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(10.dp.toPx())
                    lens(16.dp.toPx(), 24.dp.toPx())
                },
                highlight = { Highlight.Default.copy(alpha = 0.9f) },
                shadow = { Shadow.Default.copy(radius = 16.dp, alpha = 0.55f) },
                onDrawSurface = { drawRect(tint) },
            )
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 50.dp)
            .padding(horizontal = 22.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun GlassTextButton(
    backdrop: Backdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    GlassButton(
        backdrop = backdrop,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        prominent = false,
        content = content,
    )
}


/** Light native chrome over the light tea backdrop: dark status/navigation icons. */
fun Window.setTeaSystemBarsLight() {
    WindowCompat.getInsetsController(this, decorView).apply {
        isAppearanceLightStatusBars = true
        isAppearanceLightNavigationBars = true
    }
}
