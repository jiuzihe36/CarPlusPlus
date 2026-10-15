package com.carpp.sdk.ui.scifi

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlin.random.Random

/**
 * 星际背景：深空黑 + 漂移星点 + 呼吸星云 + 底部透视网格。
 *
 * 全部用 Canvas 逐帧绘制，不碰 OpenGL / RenderEffect —— 目标车机是 Android 9（API 28），
 * 这里用的 API 全部在 API 21+ 可用。星点种子固定，每次进入画面布局一致（不闪烁跳位）。
 */
@Composable
fun StarfieldBackground(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "starfield")

    // 星点整体漂移（一个 0→1 周期，各星按自身速度取相位，肉眼即为匀速下落）
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(60_000, easing = LinearEasing)),
        label = "drift",
    )

    // 全体星点同步微弱明暗呼吸
    val twinkle by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing), RepeatMode.Reverse),
        label = "twinkle",
    )

    // 彗星：7 秒一巡，前 18% 时间从右上斜掠而过
    val shot by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7_000, easing = LinearEasing)),
        label = "shot",
    )

    // 星云呼吸（半径与浓度同相位）
    val nebula by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(8_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "nebula",
    )

    // 固定种子：布局稳定
    val stars = remember {
        val rnd = Random(42)
        List(110) {
            Star(
                x = rnd.nextFloat(),
                y = rnd.nextFloat(),
                radiusPx = 1f + rnd.nextFloat() * 2f,
                alpha = 0.35f + rnd.nextFloat() * 0.65f,
                speed = 0.4f + rnd.nextFloat() * 0.8f,
            )
        }
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // 底色
        drawRect(SciFiTheme.SpaceBlack)

        // 星云：径向渐变，中心偏上，呼吸缩放
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    SciFiTheme.Violet.copy(alpha = 0.26f + 0.10f * nebula),
                    SciFiTheme.Cyan.copy(alpha = 0.08f + 0.04f * nebula),
                    Color.Transparent,
                ),
                center = Offset(w * 0.5f, h * 0.42f),
                radius = w * 0.45f * (0.94f + 0.06f * nebula),
            ),
            center = Offset(w * 0.5f, h * 0.42f),
            radius = w * 0.45f * (0.94f + 0.06f * nebula),
        )

        // 星点：按各自速度漂移，越界从另一侧回来
        for (s in stars) {
            val yNorm = (s.y + drift * s.speed) % 1f
            drawCircle(
                color = Color.White.copy(alpha = s.alpha * twinkle),
                radius = s.radiusPx,
                center = Offset(s.x * w, yNorm * h),
            )
        }

        // 彗星：右上 → 左下斜掠，头亮尾淡，行程约 1.2 秒
        if (shot < 0.18f) {
            val p = shot / 0.18f
            val hx = w * (0.82f - 0.34f * p)
            val hy = h * (0.10f + 0.24f * p)
            val tail = w * 0.16f
            // 方向（-0.86, 0.5）的反向即尾部
            val tx = hx + 0.86f * tail
            val ty = hy - 0.5f * tail
            val a = (kotlin.math.sin(p * Math.PI).toFloat()) * 0.85f
            drawLine(
                brush = Brush.linearGradient(
                    colors = listOf(SciFiTheme.Cyan.copy(alpha = a), SciFiTheme.Violet.copy(alpha = 0f)),
                    start = Offset(hx, hy),
                    end = Offset(tx, ty),
                ),
                start = Offset(hx, hy),
                end = Offset(tx, ty),
                strokeWidth = 2.5f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }

        // 底部透视网格：水平线（越靠下间距越大 = 近大远小）+ 向消失点收束的竖线
        val gridTop = h * 0.74f
        val vanish = Offset(w * 0.5f, gridTop - h * 0.25f)
        clipRect(top = gridTop) {
            // 竖线（8 根，向消失点收束）
            val steps = 8
            for (i in 0..steps) {
                val xBottom = w * i / steps
                drawLine(
                    color = SciFiTheme.Cyan.copy(alpha = 0.12f),
                    start = vanish,
                    end = Offset(xBottom, h),
                    strokeWidth = 1f,
                )
            }
        }
        // 水平线（6 根，二次分布）
        for (i in 1..6) {
            val t = i / 6f
            val y = gridTop + (h - gridTop) * t * t
            drawLine(
                color = SciFiTheme.Cyan.copy(alpha = 0.05f + 0.10f * t),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1f,
            )
        }
    }
}

/** 一颗背景星的静态属性（位置归一化 0–1，速度决定漂移相位）。 */
private data class Star(
    val x: Float,
    val y: Float,
    val radiusPx: Float,
    val alpha: Float,
    val speed: Float,
)
