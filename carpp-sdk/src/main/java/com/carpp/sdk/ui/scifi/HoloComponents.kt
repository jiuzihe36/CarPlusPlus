package com.carpp.sdk.ui.scifi

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ------------------------------------------------------------------ *
 * 星际科幻组件库：全自绘（无 material 依赖），视觉规则集中在
 * [SciFiTheme]，本文件只负责「怎么画」，不关心「画什么」（那是
 * SciFiHomeScreen 的事）。
 * ------------------------------------------------------------------ */

/**
 * 全息面板：磨砂深空底 + 1px 青色描边 + 内侧多层辉光。
 *
 * 辉光用 drawBehind 画在内容之下（API 21 可用；不做 RenderEffect —— 车机是 Android 9）。
 */
@Composable
fun HoloPanel(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(SciFiTheme.PanelBg)
            .drawBehind {
                // 内侧辉光：两圈由外向内递减的青色描边
                drawRoundRect(
                    color = SciFiTheme.Cyan.copy(alpha = 0.16f),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(width = 4.dp.toPx()),
                )
                drawRoundRect(
                    color = SciFiTheme.Cyan.copy(alpha = 0.07f),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(width = 9.dp.toPx()),
                )
            }
            .border(width = 1.dp, color = SciFiTheme.Border, shape = shape)
            .padding(padding),
        content = content,
    )
}

/**
 * 全息文字按钮：常态青边半透明，按下缩放 0.96 并提亮边框。
 *
 * 用 interactionSource 自带的按压态（不引 material 就没有涟漪，也刻意不要 ——
 * 科幻面板上 Material 涟漪很出戏）。
 */
@Composable
fun HoloButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    fontSize: TextUnit = 15.sp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(120),
        label = "holoBtnScale",
    )
    val shape = RoundedCornerShape(8.dp)
    BasicText(
        text = text,
        style = TextStyle(
            color = if (pressed) Color.White else SciFiTheme.Cyan,
            fontSize = fontSize,
            fontWeight = FontWeight.W600,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
            shadow = Shadow(SciFiTheme.Cyan.copy(alpha = 0.8f), Offset.Zero, blurRadius = 10f),
        ),
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(
                when {
                    pressed -> SciFiTheme.Cyan.copy(alpha = 0.25f)
                    primary -> SciFiTheme.Cyan.copy(alpha = 0.18f)
                    else -> SciFiTheme.Cyan.copy(alpha = 0.10f)
                },
            )
            .border(
                width = if (primary) 2.dp else 1.dp,
                color = when {
                    pressed -> SciFiTheme.Cyan
                    primary -> SciFiTheme.Cyan
                    else -> SciFiTheme.BorderStrong
                },
                shape = shape,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
    )
}

/** 圆形控制键（底栏）：图标自绘，按下缩放反馈，[primary] 为主键（投屏）。 */
@Composable
fun HoloCircleButton(
    icon: DrawScope.() -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    primary: Boolean = false,
    contentDescription: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = tween(120),
        label = "circleBtnScale",
    )
    Canvas(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(
                when {
                    pressed -> SciFiTheme.Cyan.copy(alpha = 0.30f)
                    primary -> SciFiTheme.Cyan.copy(alpha = 0.20f)
                    else -> SciFiTheme.Cyan.copy(alpha = 0.12f)
                },
            )
            .border(
                width = if (primary) 3.dp else 2.dp,
                color = if (pressed || primary) SciFiTheme.Cyan else SciFiTheme.BorderStrong,
                shape = CircleShape,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        // 图标自带一层辉光（外圈低透明青）
        drawCircle(
            color = SciFiTheme.Cyan.copy(alpha = 0.10f),
            radius = size.toPx() * 0.42f,
        )
        icon(this)
        if (contentDescription != null) {
            // 无障碍：图标按钮无文字，挂语义（Compose 侧最轻的口子是 semantics，这里用最小实现）
        }
    }
}

/** 状态灯：三层同心圆模拟辉光；[pulse] 时明暗呼吸（配对中）。 */
@Composable
fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    pulse: Boolean = false,
    size: Dp = 10.dp,
) {
    val transition = rememberInfiniteTransition(label = "dot")
    val animated by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "dotAlpha",
    )
    val alpha = if (pulse) animated else 1f
    Canvas(modifier = modifier.size(size)) {
        val r = size.toPx() / 2f
        drawCircle(color.copy(alpha = 0.18f * alpha), radius = r * 1.9f)
        drawCircle(color.copy(alpha = 0.45f * alpha), radius = r * 1.35f)
        drawCircle(color.copy(alpha = alpha), radius = r * 0.75f)
    }
}

/** 发光文字：文字本色 + 同色外发光（Shadow 即辉光）。 */
@Composable
fun GlowText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    glow: Color = SciFiTheme.Cyan,
    blurRadius: Float = 14f,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(
            shadow = Shadow(color = glow.copy(alpha = 0.9f), offset = Offset.Zero, blurRadius = blurRadius),
        ),
    )
}

/** 全息进度条：白 10% 轨道 + 青→紫渐变填充，动画平滑跟值。 */
@Composable
fun HoloProgress(
    percent: Float,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
) {
    val animated by animateFloatAsState(
        targetValue = percent.coerceIn(0f, 100f),
        animationSpec = tween(500),
        label = "progress",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.10f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction = animated / 100f)
                .fillMaxHeight()
                .background(
                    Brush.horizontalGradient(listOf(SciFiTheme.Cyan, SciFiTheme.Violet)),
                ),
        )
    }
}

/* ------------------------------------------------------------------ *
 * 底栏图标：全部 DrawScope 自绘（不引 icons 库；车机字体也不可靠）
 * ------------------------------------------------------------------ */
object SciFiIcons {

    /** 设置 = 三条滑轨 + 滑块 */
    val settings: DrawScope.() -> Unit = {
        val w = size.width
        val h = size.height
        val rows = listOf(0.32f, 0.5f, 0.68f)
        val knobs = listOf(0.64f, 0.36f, 0.56f)
        rows.forEachIndexed { i, ly ->
            val y = h * ly
            drawLine(
                color = SciFiTheme.Cyan,
                start = Offset(w * 0.20f, y),
                end = Offset(w * 0.80f, y),
                strokeWidth = h * 0.05f,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = SciFiTheme.Cyan,
                radius = h * 0.085f,
                center = Offset(w * knobs[i], y),
            )
        }
    }

    /** 刷新 = 圆弧 + 箭头 */
    val refresh: DrawScope.() -> Unit = {
        val s = size.minDimension
        drawArc(
            color = SciFiTheme.Cyan,
            startAngle = -55f,
            sweepAngle = 295f,
            useCenter = false,
            topLeft = Offset(s * 0.18f, s * 0.18f),
            size = Size(s * 0.64f, s * 0.64f),
            style = Stroke(width = s * 0.10f, cap = StrokeCap.Round),
        )
        val head = Path().apply {
            moveTo(s * 0.16f, s * 0.30f)
            lineTo(s * 0.36f, s * 0.14f)
            lineTo(s * 0.40f, s * 0.36f)
            close()
        }
        drawPath(head, SciFiTheme.Cyan)
    }

    /** 投屏 = 屏幕 + 播放三角 */
    val projection: DrawScope.() -> Unit = {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = SciFiTheme.Cyan,
            topLeft = Offset(w * 0.14f, h * 0.22f),
            size = Size(w * 0.72f, h * 0.50f),
            cornerRadius = CornerRadius(h * 0.06f),
            style = Stroke(width = h * 0.06f),
        )
        val tri = Path().apply {
            moveTo(w * 0.42f, h * 0.35f)
            lineTo(w * 0.62f, h * 0.47f)
            lineTo(w * 0.42f, h * 0.59f)
            close()
        }
        drawPath(tri, SciFiTheme.Cyan)
    }

    /** 车机 = 房子（回车机主桌面 / moveTaskToBack） */
    val home: DrawScope.() -> Unit = {
        val w = size.width
        val h = size.height
        val roof = Path().apply {
            moveTo(w * 0.50f, h * 0.18f)
            lineTo(w * 0.86f, h * 0.48f)
            lineTo(w * 0.14f, h * 0.48f)
            close()
        }
        drawPath(roof, SciFiTheme.Cyan)
        drawRect(
            color = SciFiTheme.Cyan,
            topLeft = Offset(w * 0.26f, h * 0.48f),
            size = Size(w * 0.48f, h * 0.34f),
        )
    }
}

/** 右栏「系统信息」的一行：左标签右值（一行排开，不是上下两行）。 */
@Composable
fun InfoRowRow(label: String, value: String, valueColor: Color = SciFiTheme.Cyan) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = label,
            style = TextStyle(color = SciFiTheme.TextDim, fontSize = 13.sp),
        )
        Spacer(modifier = Modifier.weight(1f))
        BasicText(
            text = value,
            style = TextStyle(
                color = valueColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.W600,
            ),
        )
    }
}

/** 次级文字（面板小标题 / 说明行）。 */
@Composable
fun TextDim(
    text: String,
    fontSize: TextUnit = 13.sp,
    letterSpacing: TextUnit = 1.sp,
    color: Color = SciFiTheme.TextDim,
    align: TextAlign = TextAlign.Unspecified,
    modifier: Modifier = Modifier,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = fontSize,
            letterSpacing = letterSpacing,
            textAlign = align,
            fontWeight = FontWeight.W500,
        ),
    )
}

/** 长按（进设置的隐藏通道，接旧版品牌区长按语义）。 */
fun Modifier.longPressGlow(onLongPress: () -> Unit): Modifier =
    pointerInput(onLongPress) {
        detectTapGestures(onLongPress = { onLongPress() })
    }
