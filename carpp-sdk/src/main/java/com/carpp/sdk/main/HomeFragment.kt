package com.carpp.sdk.main

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.carpp.sdk.App
import com.carpp.sdk.R
import com.carpp.sdk.app.CarppService
import com.carpp.sdk.sdk.LinkPhase
import com.carpp.sdk.sdk.LinkState
import com.carpp.sdk.sdk.PhoneLinkRegistry
import com.carpp.sdk.view.RingProgressView
import kotlinx.coroutines.launch

/**
 * The car's standby screen for Car++.
 *
 * Unlike Car++, whose standby screen drives an Android Auto connection, this screen is a status
 * board for the ICCOA CarLink (Xiaomi CarWith) link. The link advertises itself continuously, so
 * there is nothing to start: the screen simply mirrors the phone link's phase and surfaces the
 * pairing code the moment the SDK issues one.
 *
 * That is why there are no "start pairing" / "pair with Bluetooth" buttons — the phone initiates
 * everything, exactly like HiCar and Honor CarLink. The only control that can appear is a
 * contextual "Open projection" button, and only while a phone is already connected, so that
 * backing out of the projection screen is not a dead end.
 */
class HomeFragment : Fragment() {
    private val app get() = App.provide(requireContext())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        view.findViewById<View>(R.id.brand_area).setOnLongClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
            true
        }
        // Going back to the car does not tear down a connection or disable reconnect.
        view.findViewById<View>(R.id.exit_button).setOnClickListener {
            requireActivity().moveTaskToBack(true)
        }
        view.findViewById<View>(R.id.wifi_button).setOnClickListener { openProjection() }
        // Manual "refresh pairing code". The link already re-broadcasts itself every 30 seconds,
        // but a car has no keyboard and a phone whose dialog was dismissed will not come back on
        // its own — this gives the driver an immediate retry without waiting out the cycle.
        view.findViewById<View>(R.id.refresh_pin_button)?.setOnClickListener {
            app.phoneLink?.refreshPairing()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val link = app.phoneLink
                if (link == null) {
                    // 协议实现没起来（典型：缺 icar-sdk.dex）。这时没有任何 StateFlow 可收集，
                    // 若不显式渲染，界面会一直停在「等待手机连接」，用户只会以为配对码坏了。
                    renderDegraded()
                } else {
                    link.state.collect { render(it) }
                }
            }
        }

        // Keep the AA service alive so the process (and with it the BLE advertisement) is not
        // reaped while the car sits on the standby screen.
        if (app.settings.autoStartOnBoot || app.settings.autoStartOnScreenOn) {
            CarppService.start(requireContext())
        }
    }

    /**
     * Paints one link state onto the standby screen.
     *
     * The pairing code is driven purely by the presence of a code, not by the phase: the SDK hands
     * one out a moment after the phone starts pairing and withdraws it once the session is up, so
     * keying the visibility off the value itself keeps the card from flickering through phases.
     */
    private fun render(state: LinkState) {
        val view = view ?: return

        val status = when (state.phase) {
            LinkPhase.Idle,
            LinkPhase.Advertising -> getString(R.string.da_ready)

            LinkPhase.Pairing -> getString(R.string.da_connecting)
            LinkPhase.Connected -> getString(R.string.da_connected)
        }
        view.findViewById<TextView>(R.id.connection_status)?.text = status

        // 进度环：优先用协议下发的真实协商进度（0–100）；协议没给时回退到阶段映射。
        val pct = state.progress?.toFloat() ?: progressPercentFor(state.phase)
        android.util.Log.i("Carpp/UI", "ring pct=$pct phase=${state.phase} real=${state.progress}")
        view.findViewById<RingProgressView>(R.id.ring_progress)
            ?.setProgress(pct)

        // 顶部品牌已是 Car++，车机名不再单独成行显示（避免与品牌重复出现两个 Car++）。
        view.findViewById<TextView>(R.id.device_name)?.visibility = View.GONE

        // Null-tolerant lookups: the layout has an orientation-specific variant, and a missing view
        // must never take the standby screen down while it is the only thing the driver can see.
        val code = state.pinCode
        val codeBlock = view.findViewById<View>(R.id.pairing_code_block)
        if (codeBlock != null) {
            if (code.isNullOrBlank()) {
                codeBlock.visibility = View.GONE
            } else {
                // 6 位按 3+3 分组：行车中扫一眼比连续 6 位好读（见布局里的注释）。
                view.findViewById<TextView>(R.id.pairing_code)?.text = groupPin(code)
                codeBlock.visibility = View.VISIBLE
            }
        }

        val connected = state.phase == LinkPhase.Connected
        view.findViewById<View>(R.id.wifi_button)?.visibility =
            if (connected) View.VISIBLE else View.GONE

        // Refresh is pointless once a session is up, so it is the mirror image of "Open projection".
        view.findViewById<View>(R.id.refresh_pin_button)?.visibility =
            if (connected) View.GONE else View.VISIBLE
    }

    /**
     * 降级态渲染：协议实现没起来（典型：缺 icar-sdk.dex）时，首页只说一句状态，
     * 不铺说明段落 —— 首页的字数按 ICCOA 车机端的口径收紧。
     *
     * 刻意不走 [render]：没有真实阶段可画，环停在 0%，配对码块与刷新按钮都收起
     * （刷新对一个不存在的链路做不了任何事，留着只会骗人）。
     */
    private fun renderDegraded() {
        val view = view ?: return
        view.findViewById<TextView>(R.id.connection_status)?.text =
            getString(R.string.da_unavailable)
        view.findViewById<View>(R.id.pairing_code_block)?.visibility = View.GONE
        view.findViewById<View>(R.id.refresh_pin_button)?.visibility = View.GONE
        view.findViewById<View>(R.id.wifi_button)?.visibility = View.GONE
        view.findViewById<TextView>(R.id.device_name)?.visibility = View.GONE
        view.findViewById<RingProgressView>(R.id.ring_progress)?.setProgress(0f, animate = false)
    }

    private fun openProjection() {
        // Mark as opened so MainActivity's auto-open observer doesn't immediately re-launch the
        // projection the moment this fragment's host resumes.
        MainActivity.icocaProjectionOpened = true
        // 投影界面由协议自带（ICCOA 是 IcocaProjectionActivity）；换协议时这里不用改。
        PhoneLinkRegistry.provider()?.projectionIntent(requireContext())?.let { startActivity(it) }
    }

    companion object {
        // Retained for existing automation entry points; self projection has no standby control.
        var forceSelfModeLaunch = false
        fun resetAutoStart() { forceSelfModeLaunch = false }

        /**
         * 连接阶段 → 环形进度百分比。
         *
         * ICCOA 只给四个阶段、不给百分比，所以这里按阶段定锚点：等待=1/3，配对=2/3，
         * 已连=满。数字表达的是「走到哪一段」，不是协议承诺的剩余时间 ——
         * 与其显示一个编造的倒计时，不如显示一个诚映射的进度。
         */
        /**
         * 协议没给真实进度时的兜底映射。
         * 关键：等待连接（Advertising）和出配对码（Pairing）都从 **0%** 起步，
         * 不让环在阶段切换时跳跃；配对码出现后由 ICCOA 的 `onConnectingProgress`
         * 真实进度（0→100）接管 —— 手机端输入配对码后进度平滑涨到 100%，
         * 协议报 Connected 即出画面。
         */
        internal fun progressPercentFor(phase: LinkPhase): Float = when (phase) {
            LinkPhase.Idle -> 0f
            LinkPhase.Advertising -> 0f
            LinkPhase.Pairing -> 0f
            LinkPhase.Connected -> 100f
        }

        /** 6 位码按 3+3 分组；位数不是 6 时原样返回，不做「修正」。 */
        fun groupPin(pin: String): String =
            if (pin.length == 6) pin.substring(0, 3) + " " + pin.substring(3) else pin
    }
}
