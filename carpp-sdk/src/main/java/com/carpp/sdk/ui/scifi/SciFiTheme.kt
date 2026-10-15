package com.carpp.sdk.ui.scifi

import androidx.compose.ui.graphics.Color

/**
 * 星际科幻 UI 色板 —— 唯一取值源头。
 *
 * 数值全部来自用户批准的原型（interstellar-car-ui-prototype.html）：
 * 深空黑底 + 星光青主色 + 晶格紫辅色 + 脉冲金强调。改配色只改这里。
 */
object SciFiTheme {
    /** 页面底色：最深的太空黑。 */
    val SpaceBlack = Color(0xFF010210)

    /** 面板底色：70% 不透明的深空黑，露出背后的星空制造玻璃感。 */
    val PanelBg = Color(0xB3010210)

    /** 主色：星光青 —— 文字高亮、边框、按钮、状态灯。 */
    val Cyan = Color(0xFF00F0FF)

    /** 辅色：晶格紫 —— 进度条尾端、渐变收束。 */
    val Violet = Color(0xFF7B68EE)

    /** 强调色：脉冲金 —— 配对码等「此刻最重要的一行字」。 */
    val Gold = Color(0xFFFFD200)

    /** 警示色：降级态（协议实现没起来）。 */
    val Alert = Color(0xFFFF5A6E)

    /** 次级文字。 */
    val TextDim = Color(0xB3FFFFFF)

    /** 面板描边 = 主色 35%。 */
    val Border = Cyan.copy(alpha = 0.35f)

    /** 强描边（按钮常态）= 主色 55%。 */
    val BorderStrong = Cyan.copy(alpha = 0.55f)
}
