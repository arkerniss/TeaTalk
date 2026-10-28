package com.vertin.teatalk.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Full-screen paper canvas: safe insets, 22.dp gutters, one scroll container.
 */
@Composable
fun TeaScreen(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalTeaPalette.current
    Surface(
        modifier = modifier.fillMaxSize(),
        color = palette.paper,
        contentColor = palette.ink,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 20.dp),
            content = content,
        )
    }
}

@Composable
fun TeaWordmark(
    text: String = "茶室",
    modifier: Modifier = Modifier,
    style: TextStyle = TeaType.display,
) {
    Text(
        text = text,
        style = style,
        color = LocalTeaPalette.current.ink,
        modifier = modifier,
    )
}

@Composable
fun TeaTagline(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = TeaType.bodySmall,
        color = LocalTeaPalette.current.inkMuted,
        modifier = modifier,
    )
}

@Composable
fun TeaSectionLabel(text: String, modifier: Modifier = Modifier) {
    val palette = LocalTeaPalette.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, style = TeaType.label, color = palette.inkMuted)
        Spacer(Modifier.width(12.dp))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = palette.rule,
        )
    }
}

@Composable
fun TeaRule(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.fillMaxWidth(),
        thickness = 1.dp,
        color = LocalTeaPalette.current.rule,
    )
}

@Composable
fun TeaCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalTeaPalette.current
    Surface(
        modifier = modifier,
        shape = TeaShapes.md,
        color = palette.raised,
        contentColor = palette.ink,
        border = BorderStroke(1.dp, palette.rule),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            content = content,
        )
    }
}

@Composable
fun TeaPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val palette = LocalTeaPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val active = pressed && enabled
    val background by animateColorAsState(
        targetValue = when {
            !enabled -> palette.sunken
            active -> lerp(palette.clay, palette.deep, 0.18f)
            else -> palette.clay
        },
        label = "teaPrimaryBackground",
    )
    val scale by animateFloatAsState(
        targetValue = if (active) 0.99f else 1f,
        label = "teaPrimaryScale",
    )
    Box(
        modifier = modifier
            .heightIn(min = 52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(TeaShapes.md)
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) palette.clayInk else palette.inkMuted,
            fontFamily = FontFamily.SansSerif,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun TeaGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val palette = LocalTeaPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = if (pressed && enabled) palette.sunken else Color.Transparent,
        label = "teaGhostBackground",
    )
    Box(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(TeaShapes.md)
            .background(background)
            .border(1.dp, palette.rule, TeaShapes.md)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) palette.ink else palette.inkMuted,
            fontFamily = FontFamily.SansSerif,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Small mono text action ("关闭房间", "复制链接", "重新搜索").
 * Deliberately not a material3 TextButton.
 */
@Composable
fun TeaInlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = LocalTeaPalette.current.clay,
) {
    val palette = LocalTeaPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = if (pressed) palette.sunken else Color.Transparent,
        label = "teaInlineBackground",
    )
    Text(
        text = text,
        style = TeaType.meta,
        color = color,
        modifier = modifier
            .clip(TeaShapes.sm)
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 7.dp),
    )
}

/**
 * Home-screen navigation: mono index, serif title, quiet description, clay chevron,
 * hairline rule underneath. Not a card.
 */
@Composable
fun TeaActionRow(
    index: String,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTeaPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = if (pressed) palette.sunken else Color.Transparent,
        label = "teaActionRowBackground",
    )
    Column(
        modifier = modifier
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
                .padding(vertical = 18.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = index,
                style = TeaType.meta,
                color = palette.inkMuted,
                modifier = Modifier.padding(top = 7.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontFamily = FontFamily.Serif,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = (-0.01).em,
                    color = palette.ink,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = description,
                    style = TeaType.bodySmall,
                    color = palette.inkMuted,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "›",
                fontFamily = FontFamily.Monospace,
                fontSize = 20.sp,
                color = palette.clay,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        TeaRule()
    }
}

/**
 * Sunken field with a hairline border that turns clay on focus. No glow, no shadow.
 */
@Composable
fun TeaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val palette = LocalTeaPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor by animateColorAsState(
        targetValue = if (focused) palette.clay else palette.rule,
        label = "teaFieldBorder",
    )
    Column(modifier) {
        if (label != null) {
            Text(
                text = label,
                style = TeaType.label,
                color = palette.inkMuted,
                modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            textStyle = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 16.sp,
                color = palette.ink,
            ),
            singleLine = singleLine,
            cursorBrush = SolidColor(palette.clay),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            interactionSource = interaction,
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clip(TeaShapes.md)
                        .background(palette.sunken)
                        .border(1.dp, borderColor, TeaShapes.md)
                        .padding(horizontal = 16.dp, vertical = 15.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 16.sp,
                            color = palette.inkMuted,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

@Composable
fun TeaGlyphButton(
    glyph: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalTeaPalette.current.ink,
) {
    val palette = LocalTeaPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = if (pressed) palette.sunken else Color.Transparent,
        label = "teaGlyphBackground",
    )
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(TeaShapes.sm)
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            fontFamily = FontFamily.Monospace,
            fontSize = 18.sp,
            color = tint,
        )
    }
}

@Composable
fun TeaMeta(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalTeaPalette.current.inkMuted,
) {
    Text(
        text = text,
        style = TeaType.meta,
        color = color,
        modifier = modifier,
    )
}

@Composable
fun TeaStatusDot(active: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalTeaPalette.current
    val color = if (active) palette.clay else palette.danger
    Box(
        modifier = modifier.size(13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(color.copy(alpha = 0.16f)),
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

@Composable
fun TeaChip(text: String, modifier: Modifier = Modifier) {
    val palette = LocalTeaPalette.current
    Text(
        text = text,
        style = TeaType.meta,
        color = palette.inkSoft,
        modifier = modifier
            .clip(TeaShapes.pill)
            .background(palette.sunken)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** 2.dp monospace-ish spinner for transient work only. */
@Composable
fun TeaSpinner(modifier: Modifier = Modifier, size: Dp = 16.dp) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        color = LocalTeaPalette.current.clay,
        strokeWidth = 2.dp,
    )
}
