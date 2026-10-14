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
import com.carpp.sdk.aap.AapService
import com.carpp.sdk.sdk.LinkPhase
import com.carpp.sdk.sdk.LinkState
import com.carpp.sdk.sdk.PhoneLinkRegistry
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
        view.findViewById<View>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
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
                app.phoneLink?.state?.collect { render(it) }
            }
        }

        // Keep the AA service alive so the process (and with it the BLE advertisement) is not
        // reaped while the car sits on the standby screen.
        if (app.settings.autoStartOnBoot || app.settings.autoStartOnScreenOn) {
            ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java))
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

        // Null-tolerant lookups: the layout has an orientation-specific variant, and a missing view
        // must never take the standby screen down while it is the only thing the driver can see.
        val code = state.pinCode
        val codeBlock = view.findViewById<View>(R.id.pairing_code_block)
        if (codeBlock != null) {
            if (code.isNullOrBlank()) {
                codeBlock.visibility = View.GONE
            } else {
                view.findViewById<TextView>(R.id.pairing_code)?.text = code
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
    }
}
