package com.carpp.sdk.ui.scifi

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carpp.sdk.R
import com.carpp.sdk.main.HomeFragment
import kotlinx.coroutines.delay
import java.util.Date

/**
 * 星际科幻主页：顶栏 + 三栏主体 + 底部控制栏。
 *
 * 这是用户批准的原型（interstellar-car-ui-prototype.html）的实现，数据全部来自
 * [HomeUiState]（真实协议状态）—— 原型里纯装饰的温度/油耗一类假数据一律不上车。
 *
 * 语义与旧 fragment_home 完全一致：长按品牌区仍是进设置的隐藏通道（顶栏品牌可长按），
 * 四个动作（设置/刷新/投屏/回车机）在这里都有明路。
 */
@Composable
fun SciFiHomeScreen(
    state: HomeUiState,
    onOpenSettings: () -> Unit,
    onExit: () -> Unit,
    onOpenProjection: () -> Unit,
    onRefreshPin: () -> Unit,
) {
    val context = LocalContext.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(SciFiTheme.SpaceBlack),
    ) {
        StarfieldBackground(modifier = Modifier.fillMaxSize())

        // 自适应：宽度 <500dp（竖屏手机）三栏挤不下 → 主舞台独占，
        // 侧栏收起（四个动作全在底栏，功能一个不丢）；车机横屏走三栏。
        val compact = maxWidth < 500.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            TopBar(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .holoEntrance(0),
                compact = compact,
                onBrandLongPress = onOpenSettings,
            )

            if (compact) {
                HeroPanel(
                    state = state,
                    onOpenProjection = onOpenProjection,
                    compact = true,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                        .holoEntrance(2),
                )
            } else {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    StatusPanel(
                        state = state,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .holoEntrance(1),
                    )
                    HeroPanel(
                        state = state,
                        onOpenProjection = onOpenProjection,
                        modifier = Modifier
                            .weight(1.7f)
                            .fillMaxHeight()
                            .holoEntrance(2),
                    )
                    SystemPanel(
                        state = state,
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .holoEntrance(3),
                    )
                }
            }

            BottomBar(
                state = state,
                compact = compact,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .holoEntrance(4),
                onOpenSettings = onOpenSettings,
                onRefreshPin = onRefreshPin,
                onOpenProjection = onOpenProjection,
                onExit = onExit,
            )
        }
    }
}

/* ------------------------------- 顶栏 ------------------------------- */

@Composable
private fun TopBar(
    state: HomeUiState,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onBrandLongPress: () -> Unit,
) {
    val statusText = stringResource(state.statusRes)
    var clock by remember { mutableStateOf(nowClock()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = nowClock()
            delay(1_000)
        }
    }

    HoloPanel(
        modifier = modifier,
        padding = PaddingValues(horizontal = 22.dp, vertical = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 品牌区：长按进设置（旧版隐藏通道，保留）
            GlowText(
                text = "Car++",
                style = TextStyle(
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.W800,
                    letterSpacing = 5.sp,
                ),
                modifier = Modifier
                    .padding(end = 8.dp)
                    .longPressGlow(onLongPress = onBrandLongPress),
            )

            Spacer(modifier = Modifier.weight(1f))

            StatusDot(
                color = dotColor(state),
                pulse = !state.degraded && !state.connected && state.statusRes == R.string.da_connecting,
                size = 10.dp,
            )
            // 窄屏状态文字放不进顶栏（主舞台已有同款状态），只留灯和时钟
            if (!compact) {
                Spacer(modifier = Modifier.width(8.dp))
                TextDim(statusText, 14.sp, 0.sp)
            }
            Spacer(modifier = Modifier.width(20.dp))
            GlowText(
                text = clock,
                style = TextStyle(
                    color = SciFiTheme.Cyan,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.W700,
                    letterSpacing = 2.sp,
                ),
                blurRadius = 8f,
            )
        }
    }
}

/* ------------------------------ 左：状态 ------------------------------ */

@Composable
private fun StatusPanel(
    state: HomeUiState,
    modifier: Modifier = Modifier,
) {
    HoloPanel(modifier = modifier, padding = PaddingValues(18.dp)) {
        PanelTitle(stringResource(R.string.sci_title_link))
        Spacer(modifier = Modifier.height(16.dp))

        val percent = state.progressPercent.toInt()
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlowText(
                text = "$percent%",
                style = TextStyle(
                    color = Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.W700,
                ),
                blurRadius = 10f,
            )
            Spacer(modifier = Modifier.weight(1f))
            StatusDot(
                color = dotColor(state),
                pulse = state.statusRes == R.string.da_connecting,
                size = 8.dp,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        HoloProgress(percent = state.progressPercent, modifier = Modifier.fillMaxWidth())

        if (!state.degraded) {
            Spacer(modifier = Modifier.height(10.dp))
            TextDim(stringResource(state.statusRes), 14.sp, 0.sp)
        }

        Spacer(modifier = Modifier.weight(1f))


    }
}

/* ------------------------------ 中：主舞台 ------------------------------ */

@Composable
private fun HeroPanel(
    state: HomeUiState,
    onOpenProjection: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    HoloPanel(modifier = modifier, padding = PaddingValues(24.dp)) {
        // 状态头：窄屏没有侧栏，连接状态必须在主面板露脸（否则整屏只剩一个状态灯，没法读）
        if (compact) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                StatusDot(
                    color = dotColor(state),
                    pulse = state.statusRes == R.string.da_connecting,
                    size = 10.dp,
                )
                Spacer(modifier = Modifier.width(10.dp))
                TextDim(stringResource(state.statusRes), 16.sp, 1.sp, color = Color.White)
            }
            Spacer(modifier = Modifier.height(18.dp))
        }
        when {
            // 配对时刻：码是此刻唯一重要的东西 —— 大号金色可读
            state.pinCode != null && !state.connected -> {
                Spacer(modifier = Modifier.weight(0.2f))
                TextDim(
                    text = stringResource(R.string.da_pin_label),
                    fontSize = 13.sp,
                    letterSpacing = 6.sp,
                    color = SciFiTheme.Gold,
                    align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(14.dp))
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    // 码字号随面板宽度自适应：窄屏不折行，车机大屏封顶 60sp（一行读完）。
                    // 宽度→像素→反推 sp（Dp 没有直给的 toSp；BoxWithConstraintsScope 也
                    // 不是 Density，要借 LocalDensity）。
                    val pinSp = with(LocalDensity.current) {
                        ((maxWidth * 0.10f).toPx() / (density * fontScale)).coerceAtMost(60f)
                    }
                    GlowText(
                        text = HomeFragment.groupPin(state.pinCode.orEmpty()),
                        style = TextStyle(
                            color = SciFiTheme.Gold,
                            fontSize = pinSp.sp,
                            fontWeight = FontWeight.W800,
                            letterSpacing = 3.sp,
                            textAlign = TextAlign.Center,
                        ),
                        glow = SciFiTheme.Gold,
                        blurRadius = 18f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (compact) {
                    // 窄屏补一句动作指引（码摆着，得告诉司机下一步做什么）
                    Spacer(modifier = Modifier.height(12.dp))
                    TextDim(
                        text = stringResource(R.string.sci_pin_hint),
                        fontSize = 13.sp,
                        letterSpacing = 1.sp,
                        align = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(modifier = Modifier.weight(0.2f))
            }

            // 已连接：渐变大字 + 回到投屏
            state.connected -> {
                Spacer(modifier = Modifier.weight(0.35f))
                GlowText(
                    text = stringResource(R.string.da_connected),
                    style = TextStyle(
                        color = SciFiTheme.Cyan,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.W800,
                        letterSpacing = 3.sp,
                        textAlign = TextAlign.Center,
                    ),
                    glow = SciFiTheme.Violet,
                    blurRadius = 20f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(22.dp))
                state.projectionLabelRes?.let { label ->
                    HoloButton(
                        text = stringResource(label),
                        onClick = onOpenProjection,
                        primary = true,
                        fontSize = 17.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(modifier = Modifier.weight(0.35f))
            }

            // 等待 / 降级：脉冲状态灯 + 状态大字
            else -> {
                Spacer(modifier = Modifier.weight(0.4f))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    HaloStatusDot(color = dotColor(state), size = 44.dp)
                }
                Spacer(modifier = Modifier.height(20.dp))
                GlowText(
                    text = stringResource(state.statusRes),
                    style = TextStyle(
                        color = if (state.degraded) SciFiTheme.Alert else Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.W700,
                        letterSpacing = 2.sp,
                        textAlign = TextAlign.Center,
                    ),
                    glow = dotColor(state),
                    blurRadius = 16f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.weight(0.4f))
            }
        }

        // 底部块（窄屏专用）：系统信息双列 + 带标注的进度条 —— 高面板上下都有内容，
        // 不再是一座孤岛（数据都是真的：版本来自 PackageInfo，协议是注册的实现名）。
        if (compact) {
            Spacer(modifier = Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    InfoRowRow(
                        label = stringResource(R.string.sci_version),
                        value = "v${appVersion(LocalContext.current)}",
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    InfoRowRow(
                        label = stringResource(R.string.sci_protocol),
                        value = "ICCOA CarLink",
                    )
                }
            }
            InfoRowRow(label = stringResource(R.string.sci_date), value = today())
            if (!state.degraded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextDim(stringResource(R.string.sci_progress), 11.sp, 2.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    TextDim(
                        text = "${state.progressPercent.toInt()}%",
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                        color = SciFiTheme.Cyan,
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                HoloProgress(
                    percent = state.progressPercent,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/* ------------------------------ 右：系统信息 ------------------------------ */

@Composable
private fun SystemPanel(
    state: HomeUiState,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    HoloPanel(modifier = modifier, padding = PaddingValues(18.dp)) {
        PanelTitle(stringResource(R.string.sci_title_system))
        Spacer(modifier = Modifier.height(16.dp))

        InfoRowRow(label = stringResource(R.string.sci_version), value = "v${appVersion(context)}")
        InfoRowRow(label = stringResource(R.string.sci_protocol), value = "ICCOA CarLink")
        InfoRowRow(label = stringResource(R.string.sci_date), value = today())
        if (state.degraded) {
            InfoRowRow(
                label = stringResource(R.string.sci_title_link),
                value = stringResource(R.string.da_unavailable),
                valueColor = SciFiTheme.Alert,
            )
        }

        Spacer(modifier = Modifier.weight(1f))
        HoloButton(
            text = stringResource(R.string.settings),
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/* ------------------------------ 底栏 ------------------------------ */

@Composable
private fun BottomBar(
    state: HomeUiState,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onOpenSettings: () -> Unit,
    onRefreshPin: () -> Unit,
    onOpenProjection: () -> Unit,
    onExit: () -> Unit,
) {
    val circleSize = if (compact) 44.dp else 56.dp
    val barHeight = if (compact) 80.dp else 96.dp
    HoloPanel(
        modifier = modifier.height(barHeight),
        padding = PaddingValues(horizontal = if (compact) 16.dp else 28.dp, vertical = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(
                if (compact) 14.dp else 26.dp,
                Alignment.CenterHorizontally,
            ),
        ) {
            // 圆钮 + 小字标签：图标认不出时文字兜底（车机上没人有耐心猜图标）
            BottomAction(
                icon = SciFiIcons.settings,
                label = stringResource(R.string.settings),
                onClick = onOpenSettings,
                size = circleSize,
                contentDescription = "settings",
            )
            if (state.showRefresh) {
                BottomAction(
                    icon = SciFiIcons.refresh,
                    label = stringResource(R.string.sci_label_refresh),
                    onClick = onRefreshPin,
                    size = circleSize,
                    contentDescription = "refresh",
                )
            }
            if (state.connected) {
                BottomAction(
                    icon = SciFiIcons.projection,
                    label = stringResource(R.string.projection_reopen),
                    onClick = onOpenProjection,
                    size = if (compact) 54.dp else 68.dp,
                    primary = true,
                    contentDescription = "projection",
                )
            }
            BottomAction(
                icon = SciFiIcons.home,
                label = stringResource(R.string.sci_label_exit),
                onClick = onExit,
                size = circleSize,
                contentDescription = "exit",
            )
        }
    }
}

/** 底栏一个动作：圆钮 + 下方小字标签。 */
@Composable
private fun BottomAction(
    icon: DrawScope.() -> Unit,
    label: String,
    onClick: () -> Unit,
    size: Dp,
    primary: Boolean = false,
    contentDescription: String? = null,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        HoloCircleButton(
            icon = icon,
            onClick = onClick,
            size = size,
            primary = primary,
            contentDescription = contentDescription,
        )
        Spacer(modifier = Modifier.height(4.dp))
        TextDim(
            text = label,
            fontSize = 10.sp,
            letterSpacing = 0.5.sp,
            align = TextAlign.Center,
        )
    }
}

/* ------------------------------ 小工具 ------------------------------ */

/** 状态灯配色：降级=警示红，配对=脉冲金，已连/等待=星光青。 */
private fun dotColor(state: HomeUiState): Color = when {
    state.degraded -> SciFiTheme.Alert
    state.statusRes == R.string.da_connecting -> SciFiTheme.Gold
    else -> SciFiTheme.Cyan
}

private fun nowClock(): String =
    DateFormat.format("HH:mm", Date()).toString()

private fun appVersion(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "-"
} catch (e: Exception) {
    "-"
}


private fun today(): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())

