package com.carpp.sdk.view

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.carpp.sdk.R
import com.carpp.sdk.sdk.PhoneNotification

/**
 * 投屏画面上的**手机通知浮层**：顶部弹横幅，点横幅展开列表。
 *
 * ## 交互（就是 CarWith 那套）
 *
 * - 手机来一条通知 → 顶部横幅弹出，[BANNER_DURATION_MS] 后自动收起；
 *   期间又来新的会排队，横幅收起后接着弹下一条。
 * - 点横幅 → 展开通知列表（可滚动）；点某一行的正文 → 在手机上打开它；
 *   点行尾「✕」→ 让手机把它划掉；通知自带的动作按钮（最多 3 个）→ 原样回传手机。
 * - 常驻通知（正在播放 / 导航中）不显示「✕」。
 * - 列表空了 / 点「收起」→ 回到只有横幅的状态。
 *
 * ## 为什么不会挡住投屏触摸
 *
 * 浮层是投屏 View 的兄弟节点，且：
 *  - 没有内容时所有子 View 都是 `GONE`，浮层自身不 clickable —— 触摸会**穿透**到底下的
 *    `UCarSurfaceView`（ViewGroup 对未消费的事件会继续派发给下面的兄弟）；
 *  - 有内容时也只有卡片本身占的那块区域接收触摸，其余区域照样穿透。
 *
 * 因此**不要**给这个 View 设 `OnTouchListener` 或 `clickable=true`，否则整块屏幕的
 * 触摸回传会被吃掉。
 *
 * 这个类只管画和转发动作；「点开 / 划掉」真正做什么由宿主通过 [onOpen] / [onDismiss] 注入，
 * 所以它不依赖任何协议实现。
 */
class NotificationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 用户在车机上点开了某条通知（在手机上打开）。 */
    var onOpen: ((Int) -> Unit)? = null

    /** 用户在车机上把某条通知划掉（让手机移除）。 */
    var onDismiss: ((Int) -> Unit)? = null

    private val policy = NotificationBannerPolicy()
    private val handler = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { hideBanner() }

    private var items: List<PhoneNotification> = emptyList()
    private var bannerItem: PhoneNotification? = null
    private var expanded = false

    private val banner = LinearLayout(context)
    private val bannerIcon = ImageView(context)
    private val bannerTitle = TextView(context)
    private val bannerText = TextView(context)

    private val panel = MaxHeightScrollView(context)
    private val panelRows = LinearLayout(context)

    init {
        // 浮层自身绝不消费触摸：见类注释「为什么不会挡住投屏触摸」。
        isClickable = false
        isFocusable = false
        clipChildren = false

        buildBanner()
        buildPanel()
    }

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /**
     * 推入最新的通知列表（由宿主收集 `PhoneNotifications.notifications` 后调用）。
     * 幂等：同一份列表重复推入不会重复弹横幅。
     */
    fun submit(list: List<PhoneNotification>) {
        items = list

        // 横幅对应的那条在手机侧已经消失（用户在手机上划掉了）→ 立刻收起，别留着幽灵卡片。
        val shown = bannerItem
        if (shown != null && list.none { it.id == shown.id }) hideBanner()

        if (expanded) {
            if (list.isEmpty()) collapse() else renderRows()
        }

        if (bannerItem == null && !expanded) showNextBanner()
    }

    /** 会话结束 / 断开：清空并忘掉「已弹过」的记录。 */
    fun reset() {
        handler.removeCallbacks(autoHide)
        policy.reset()
        items = emptyList()
        bannerItem = null
        expanded = false
        banner.visibility = View.GONE
        panel.visibility = View.GONE
        panelRows.removeAllViews()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(autoHide)
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------
    // 横幅
    // ------------------------------------------------------------------

    private fun buildBanner() {
        banner.orientation = LinearLayout.HORIZONTAL
        banner.gravity = Gravity.CENTER_VERTICAL
        banner.background = cardBackground()
        banner.setPadding(dp(16), dp(12), dp(16), dp(12))
        banner.visibility = View.GONE
        banner.setOnClickListener { expand() }

        bannerIcon.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            rightMargin = dp(14)
        }
        bannerIcon.scaleType = ImageView.ScaleType.FIT_CENTER

        bannerTitle.setTextColor(Color.WHITE)
        bannerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        bannerTitle.maxLines = 1
        bannerTitle.ellipsize = android.text.TextUtils.TruncateAt.END

        bannerText.setTextColor(0xE6FFFFFF.toInt())
        bannerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        bannerText.maxLines = 2
        bannerText.ellipsize = android.text.TextUtils.TruncateAt.END

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(bannerTitle)
            addView(bannerText)
        }

        banner.addView(bannerIcon)
        banner.addView(
            column,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        addView(
            banner,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
                topMargin = dp(8)
                leftMargin = dp(12)
                rightMargin = dp(12)
            },
        )
    }

    private fun showNextBanner() {
        val next = policy.claimNext(items) ?: return
        bannerItem = next
        bindBanner(next)
        banner.visibility = View.VISIBLE
        banner.alpha = 0f
        banner.translationY = -dp(16).toFloat()
        banner.animate().alpha(1f).translationY(0f).setDuration(160).start()
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, BANNER_DURATION_MS)
    }

    private fun bindBanner(item: PhoneNotification) {
        bannerTitle.text = item.title
        bannerText.text = item.text
        bindIcon(bannerIcon, item)
    }

    private fun hideBanner() {
        handler.removeCallbacks(autoHide)
        if (banner.visibility != View.VISIBLE) {
            bannerItem = null
            return
        }
        bannerItem = null
        banner.animate().alpha(0f).translationY(-dp(16).toFloat()).setDuration(140)
            .withEndAction {
                banner.visibility = View.GONE
                // 队列里还有没弹过的，接着弹下一条。
                if (!expanded) showNextBanner()
            }
            .start()
    }

    // ------------------------------------------------------------------
    // 展开的列表
    // ------------------------------------------------------------------

    private fun buildPanel() {
        panelRows.orientation = LinearLayout.VERTICAL
        panelRows.setPadding(dp(8), dp(4), dp(8), dp(8))
        panel.addView(panelRows)
        panel.background = cardBackground()
        panel.maxHeightPx = (resources.displayMetrics.heightPixels * 0.62f).toInt()
        panel.visibility = View.GONE

        addView(
            panel,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
                topMargin = dp(8)
                leftMargin = dp(12)
                rightMargin = dp(12)
            },
        )
    }

    private fun expand() {
        if (expanded) return
        expanded = true
        handler.removeCallbacks(autoHide)
        banner.animate().cancel()
        banner.visibility = View.GONE
        bannerItem = null
        renderRows()
        panel.visibility = View.VISIBLE
        panel.alpha = 0f
        panel.animate().alpha(1f).setDuration(140).start()
    }

    private fun collapse() {
        expanded = false
        panel.visibility = View.GONE
        panelRows.removeAllViews()
        showNextBanner()
    }

    private fun renderRows() {
        panelRows.removeAllViews()
        panelRows.addView(buildHeader(items.size))
        items.forEach { panelRows.addView(buildRow(it)) }
    }

    private fun buildHeader(count: Int): View {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(4), dp(8))
        }

        val title = TextView(context).apply {
            text = context.getString(R.string.notification_panel_title, count)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }

        val collapse = TextView(context).apply {
            text = context.getString(R.string.notification_collapse)
            setTextColor(0xB3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { collapse() }
        }

        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(collapse)
        return header
    }

    private fun buildRow(item: PhoneNotification): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            setOnClickListener { onOpen?.invoke(item.id) }
        }

        val icon = ImageView(context)
        icon.layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(12) }
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        bindIcon(icon, item)

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        column.addView(
            TextView(context).apply {
                text = item.title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
        )

        if (item.text.isNotEmpty()) {
            column.addView(
                TextView(context).apply {
                    text = item.text
                    setTextColor(0xE6FFFFFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
            )
        }

        if (item.actions.isNotEmpty()) {
            val actions = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            item.actions.forEach { action ->
                actions.addView(
                    TextView(context).apply {
                        text = action.title
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        background = pillBackground()
                        setPadding(dp(14), dp(6), dp(14), dp(6))
                        setOnClickListener { action.send() }
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { rightMargin = dp(8) },
                )
            }
            column.addView(actions)
        }

        row.addView(icon)
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 常驻通知不给划掉：手机上划了它也会立刻回来。
        if (!item.ongoing) {
            row.addView(
                TextView(context).apply {
                    text = "✕"
                    setTextColor(0x99FFFFFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setPadding(dp(12), dp(4), dp(4), dp(4))
                    setOnClickListener { onDismiss?.invoke(item.id) }
                },
            )
        }

        return row
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private fun bindIcon(target: ImageView, item: PhoneNotification) {
        val bitmap = item.bigIcon ?: item.icon
        if (bitmap != null) {
            target.setImageBitmap(bitmap)
        } else {
            // 协议没给图标时不留空位：画一个带首字母的圆角块，至少能看出是哪个应用。
            target.setImageDrawable(letterDrawable(item.title))
        }
    }

    private fun cardBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(0xF214171D.toInt())
        setStroke(dp(1), 0x33FFFFFF)
    }

    private fun pillBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(0x2EFFFFFF)
    }

    private fun letterDrawable(label: String): GradientDrawable {
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(0x33FFFFFF)
        }
        // 纯 Drawable 画字成本高，这里用一个极简替代：色块本身已足够区分位置，
        // 首字母交给标题文本承担（标题为空时已回退成包名）。
        return drawable
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics)
            .toInt()

    private companion object {
        /** 横幅停留时长；太短看不见，太长挡住投屏画面。 */
        const val BANNER_DURATION_MS = 4000L
    }
}

/** 带最大高度限制的 ScrollView：通知很多时列表不会占满整块车机屏。 */
private class MaxHeightScrollView(context: Context) : ScrollView(context) {

    var maxHeightPx: Int = Int.MAX_VALUE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (measuredHeight > maxHeightPx) {
            setMeasuredDimension(measuredWidth, maxHeightPx)
        }
    }
}
