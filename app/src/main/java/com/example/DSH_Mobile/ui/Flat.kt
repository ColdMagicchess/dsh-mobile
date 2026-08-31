package com.example.DSH_Mobile.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Flat light design system ported 1:1 from design/connect-mockup.html
 * (Uiverse alexruix input as the base language): grey fill -> white on
 * focus, 8dp radius, sky-blue border + soft glow ring.
 */
internal object Flat {
    val PageBg = Color(0xFFFAF7F1)
    val Fill = Color(0xFFF1EEE6)
    val Ink = Color(0xFF0D0C22)
    val Label = Color(0xFF6B6B76)
    val Muted = Color(0xFF9E9EA7)
    val Accent = Color(0xFF0EA5E9)
    val AccentBorder = Color(0x660EA5E9)
    val Glow = Color(0x1A0EA5E9)
    val White = Color(0xFFFFFFFF)
    val Danger = Color(0xFFD96A6A)
    val TrackOff = Color(0xFFE5E1D8)
    val Shape = RoundedCornerShape(8.dp)
}

/** CSS "box-shadow: 0 0 0 4px glow" equivalent: a flush ring outside the border. */
private fun Modifier.glowRing(active: Boolean, corner: Dp = 8.dp): Modifier = drawBehind {
    if (!active) return@drawBehind
    val inflate = 4.dp.toPx()
    drawRoundRect(
        color = Flat.Glow,
        topLeft = Offset(-inflate, -inflate),
        size = Size(size.width + 2 * inflate, size.height + 2 * inflate),
        cornerRadius = CornerRadius((corner + 4.dp).toPx()),
        style = Stroke(width = 4.dp.toPx()),
    )
}

/** Base input: grey fill, white + sky border + glow on focus; leading icon tints with focus. */
@Composable
fun FlatTextField(
    label: String?,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    icon: ImageVector? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    corner: Dp = 8.dp,
    compact: Boolean = false,
    elevation: Dp = 0.dp,
) {
    val fieldShape = RoundedCornerShape(corner)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg by animateColorAsState(if (focused) Flat.White else Flat.Fill, tween(300), label = "flat-bg")
    val borderColor by animateColorAsState(
        if (focused) Flat.AccentBorder else Color.Transparent,
        tween(300),
        label = "flat-border",
    )
    val iconTint by animateColorAsState(if (focused) Flat.Accent else Flat.Muted, tween(300), label = "flat-icon")
    Column(modifier) {
        if (label != null) {
            Text(
                label,
                fontSize = 13.sp,
                color = Flat.Label,
                modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .glowRing(focused, corner)
                .then(
                    if (elevation > 0.dp) {
                        Modifier.shadow(
                            elevation, fieldShape, clip = false,
                            ambientColor = Color(0x2E000000), spotColor = Color(0x59000000),
                        )
                    } else {
                        Modifier
                    },
                )
                .border(2.dp, borderColor, fieldShape)
                .background(bg, fieldShape)
                .heightIn(min = if (compact || singleLine) 44.dp else 88.dp),
            contentAlignment = if (compact || singleLine) Alignment.CenterStart else Alignment.TopStart,
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier
                        .padding(start = 14.dp, top = if (compact || singleLine) 0.dp else 12.dp)
                        .size(18.dp),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                interactionSource = interaction,
                singleLine = singleLine,
                minLines = minLines,
                maxLines = if (singleLine) 1 else 6,
                textStyle = TextStyle(
                    color = Flat.Ink,
                    fontSize = 14.sp,
                    lineHeight = if (singleLine) 20.sp else 22.sp,
                ),
                cursorBrush = SolidColor(Flat.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (icon != null) 44.dp else 16.dp,
                        end = 16.dp,
                        top = if (compact || singleLine) 0.dp else 12.dp,
                        bottom = if (compact || singleLine) 0.dp else 12.dp,
                    ),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(placeholder, fontSize = 14.sp, color = Flat.Muted)
                    }
                    inner()
                },
            )
        }
    }
}

/** A white pill that slides between options; text colors crossfade. */
@Composable
fun FlatSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pill = RoundedCornerShape(6.dp)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .background(Flat.Fill, Flat.Shape)
            .padding(4.dp),
    ) {
        val gap = 4.dp
        val pillWidth = (maxWidth - gap) / 2
        val slide by animateFloatAsState(
            selectedIndex.toFloat(),
            spring(dampingRatio = 0.75f, stiffness = 520f),
            label = "seg-slide",
        )
        // Sliding indicator underneath the labels.
        Box(
            Modifier
                .offset(x = (pillWidth + gap) * slide)
                .width(pillWidth)
                .height(34.dp)
                .shadow(
                    2.dp, pill, clip = false,
                    ambientColor = Color(0x140D0C22), spotColor = Color(0x170D0C22),
                )
                .clip(pill)
                .background(Flat.White),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val textColor by animateColorAsState(
                    if (selected) Flat.Ink else Flat.Muted,
                    tween(200),
                    label = "seg-text",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = textColor)
                }
            }
        }
    }
}

/** iOS-style flat switch: grey track -> sky fill, bouncy white thumb. */
@Composable
fun FlatSwitch(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val track by animateColorAsState(if (on) Flat.Accent else Flat.TrackOff, tween(300), label = "switch-track")
    val thumbX by animateFloatAsState(
        if (on) 23f else 3f,
        spring(dampingRatio = 0.5f, stiffness = 600f),
        label = "switch-thumb",
    )
    Box(
        modifier
            .size(width = 44.dp, height = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(track)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onToggle() },
    ) {
        Box(
            Modifier
                .offset(x = thumbX.dp, y = 3.dp)
                .size(18.dp)
                .shadow(2.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(Flat.White),
        )
    }
}

/** Primary action: solid sky fill, glow ring on press, slight squeeze. */
@Composable
fun FlatButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        if (enabled) Flat.Accent else Flat.Fill,
        tween(200),
        label = "btn-bg",
    )
    val scale by animateFloatAsState(
        if (pressed) 0.985f else 1f,
        spring(dampingRatio = 0.6f, stiffness = 800f),
        label = "btn-scale",
    )
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .glowRing(pressed && enabled)
            .height(48.dp)
            .background(bg, Flat.Shape)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Build a tintable ImageVector from raw SVG path data (Material 24dp viewport). */
internal fun flatVector(pathData: String): ImageVector =
    ImageVector.Builder(
        name = "flat-icon",
        defaultWidth = 18.dp,
        defaultHeight = 18.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        val nodes = PathParser().parsePathString(pathData).toNodes()
        addPath(pathData = nodes, fill = SolidColor(Color.Black))
    }.build()

val GlobeIcon = flatVector(
    "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm7.93 9h-3.02a15.7 15.7 0 0 0-1.2-5.36A8.03 8.03 0 0 1 19.93 11zM12 4.04c.86 1.16 1.84 3.4 2.04 6.96H9.96c.2-3.56 1.18-5.8 2.04-6.96zM8.29 5.64A15.7 15.7 0 0 0 7.09 11H4.07a8.03 8.03 0 0 1 4.22-5.36zM4.07 13h3.02c.09 1.98.5 4 1.2 5.36A8.03 8.03 0 0 1 4.07 13zM12 19.96c-.86-1.16-1.84-3.4-2.04-6.96h4.08c-.2 3.56-1.18 5.8-2.04 6.96zm3.71-1.6A15.7 15.7 0 0 0 16.91 13h3.02a8.03 8.03 0 0 1-4.22 5.36z",
)

val LinkIcon = flatVector(
    "M3.9 12a3.1 3.1 0 0 1 3.1-3.1h4V7H7a5 5 0 0 0 0 10h4v-1.9H7A3.1 3.1 0 0 1 3.9 12zM8 13h8v-2H8v2zm9-6h-4v1.9h4a3.1 3.1 0 0 1 0 6.2h-4V19h4a5 5 0 0 0 0-12z",
)

val FolderIcon = flatVector(
    "M20 6h-8l-2-2H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm0 12H4V8h16v10z",
)

val MenuLines = flatVector(
    "M3 6h18v2.2H3zM3 10.9h18v2.2H3zM3 15.8h18v2.2H3z",
)
