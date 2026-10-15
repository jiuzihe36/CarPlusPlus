package com.carpp.sdk.ui.scifi

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SciFiTheme.SpaceBlack),
    ) {
        StarfieldBackground(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            TopBar(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                onBrandLongPress = onOpenSettings,
            )

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StatusPanel(
                    state = state,
                    onRefreshPin = onRefreshPin,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                HeroPanel(
                    state = state,
                    onOpenProjection = onOpenProjection,
                    modifier = Modifier
                        .weight(1.7f)
                        .fillMaxHeight(),
                )
                SystemPanel(
                    state = state,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }

            BottomBar(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
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
            Spacer(modifier = Modifier.width(8.dp))
            TextDim(statusText, 14.sp, 0.sp)
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
    onRefreshPin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HoloPanel(modifier = modifier, padding = PaddingValues(18.dp)) {
        TextDim(stringResource(R.string.sci_title_link), 12.sp, 2.sp)
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

        if (state.showRefresh) {
            HoloButton(
                text = stringResource(R.string.da_refresh_pin),
                onClick = onRefreshPin,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (state.connected) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color = SciFiTheme.Cyan, size = 8.dp)
                Spacer(modifier = Modifier.width(8.dp))
                TextDim(stringResource(R.string.da_connected), 13.sp, 0.sp)
            }
        }
    }
}

/* ------------------------------ 中：主舞台 ------------------------------ */

@Composable
private fun HeroPanel(
    state: HomeUiState,
    onOpenProjection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HoloPanel(modifier = modifier, padding = PaddingValues(24.dp)) {
        when {
            // 配对时刻：码是此刻唯一重要的东西 —— 大号金色可读
            state.pinCode != null && !state.connected -> {
                Spacer(modifier = Modifier.weight(0.3f))
                TextDim(
                    text = stringResource(R.string.da_pin_label),
                    fontSize = 13.sp,
                    letterSpacing = 6.sp,
                    color = SciFiTheme.Gold,
                    align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(14.dp))
                GlowText(
                    text = HomeFragment.groupPin(state.pinCode.orEmpty()),
                    style = TextStyle(
                        color = SciFiTheme.Gold,
                        fontSize = 58.sp,
                        fontWeight = FontWeight.W800,
                        letterSpacing = 4.sp,
                        textAlign = TextAlign.Center,
                    ),
                    glow = SciFiTheme.Gold,
                    blurRadius = 18f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(18.dp))
                HoloProgress(
                    percent = state.progressPercent,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.weight(0.3f))
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
                Spacer(modifier = Modifier.height(18.dp))
                HoloProgress(
                    percent = state.progressPercent,
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
                    StatusDot(
                        color = dotColor(state),
                        pulse = !state.degraded,
                        size = 44.dp,
                    )
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
                Spacer(modifier = Modifier.height(16.dp))
                if (!state.degraded) {
                    HoloProgress(
                        percent = state.progressPercent,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(modifier = Modifier.weight(0.4f))
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
        TextDim(stringResource(R.string.sci_title_system), 12.sp, 2.sp)
        Spacer(modifier = Modifier.height(16.dp))

        InfoRowRow(label = stringResource(R.string.sci_version), value = "v${appVersion(context)}")
        InfoRowRow(label = stringResource(R.string.sci_protocol), value = "ICCOA CarLink")
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
    onOpenSettings: () -> Unit,
    onRefreshPin: () -> Unit,
    onOpenProjection: () -> Unit,
    onExit: () -> Unit,
) {
    HoloPanel(
        modifier = modifier.height(84.dp),
        padding = PaddingValues(horizontal = 28.dp, vertical = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
        ) {
            HoloCircleButton(
                icon = SciFiIcons.settings,
                onClick = onOpenSettings,
                contentDescription = "settings",
            )
            if (state.showRefresh) {
                HoloCircleButton(
                    icon = SciFiIcons.refresh,
                    onClick = onRefreshPin,
                    contentDescription = "refresh",
                )
            }
            if (state.connected) {
                HoloCircleButton(
                    icon = SciFiIcons.projection,
                    onClick = onOpenProjection,
                    size = 68.dp,
                    primary = true,
                    contentDescription = "projection",
                )
            } else {
                // 没有可投的屏时，中位放等待提示（与旧版「等待手机连接」同义）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(
                        color = dotColor(state),
                        pulse = !state.degraded,
                        size = 10.dp,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    TextDim(stringResource(state.statusRes), 14.sp, 1.sp)
                }
            }
            HoloCircleButton(
                icon = SciFiIcons.home,
                onClick = onExit,
                contentDescription = "exit",
            )
        }
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
