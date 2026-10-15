package com.carpp.sdk.main

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.carpp.sdk.App
import com.carpp.sdk.R
import com.carpp.sdk.app.CarppService
import com.carpp.sdk.main.settings.SettingItem
import com.carpp.sdk.main.settings.SettingsAdapter
import com.carpp.sdk.utils.AppLog
import com.carpp.sdk.utils.AppPermissions
import com.carpp.sdk.utils.Settings
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AutoStartFragment : Fragment() {
    private lateinit var settings: Settings
    private lateinit var recyclerView: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar
    private var saveButton: MaterialButton? = null

    private var pendingAutoStartOnBoot: Boolean? = null
    private var pendingAutoStartOnScreenOn: Boolean? = null
    private var pendingAutoStartOnWifi: Boolean? = null
    private var pendingAutoStartWifiSsid: String? = null

    private var hasChanges = false
    private val SAVE_ITEM_ID = 1001

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_auto_start, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = App.provide(requireContext()).settings

        pendingAutoStartOnBoot = settings.autoStartOnBoot
        pendingAutoStartOnScreenOn = settings.autoStartOnScreenOn
        pendingAutoStartOnWifi = settings.autoStartOnWifi
        pendingAutoStartWifiSsid = settings.autoStartWifiSsid

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })

        toolbar = view.findViewById(R.id.toolbar)
        settingsAdapter = SettingsAdapter()
        recyclerView = view.findViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = settingsAdapter

        updateSettingsList()
        setupToolbar()
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            handleBackPress()
        }

        val saveItem = toolbar.menu.add(0, SAVE_ITEM_ID, 0, getString(R.string.save))
        saveItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        saveItem.setActionView(R.layout.layout_save_button)

        saveButton = saveItem.actionView?.findViewById(R.id.save_button_widget)
        saveButton?.setOnClickListener {
            saveSettings()
        }

        updateSaveButtonState()
    }

    private fun handleBackPress() {
        if (hasChanges) {
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.unsaved_changes)
                .setMessage(R.string.unsaved_changes_message)
                .setPositiveButton(R.string.discard) { _, _ ->
                    navigateBack()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            navigateBack()
        }
    }

    private fun navigateBack() {
        try {
            val navController = findNavController()
            if (!navController.navigateUp()) {
                requireActivity().finish()
            }
        } catch (e: Exception) {
            requireActivity().finish()
        }
    }

    private fun updateSaveButtonState() {
        saveButton?.isEnabled = hasChanges
        saveButton?.text = getString(R.string.save)
    }

    private fun saveSettings() {
        pendingAutoStartOnBoot?.let {
            settings.autoStartOnBoot = it
            Settings.syncAutoStartOnBootToDeviceStorage(requireContext(), it)
        }
        pendingAutoStartOnScreenOn?.let {
            settings.autoStartOnScreenOn = it
            Settings.syncAutoStartOnScreenOnToDeviceStorage(requireContext(), it)
        }
        pendingAutoStartOnWifi?.let {
            settings.autoStartOnWifi = it
            Settings.syncAutoStartOnWifiToDeviceStorage(requireContext(), it)
        }
        pendingAutoStartWifiSsid?.let {
            settings.autoStartWifiSsid = it
            Settings.syncAutoStartWifiSsidToDeviceStorage(requireContext(), it)
        }

        // 配了任一自启项就需要悬浮窗权限：没有它，后台拉起的界面会被系统挡掉。
        if (pendingAutoStartOnBoot == true || pendingAutoStartOnScreenOn == true ||
            pendingAutoStartOnWifi == true) {
            if (!AppPermissions.isOverlayGranted(requireContext())) {
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.overlay_permission_title)
                    .setMessage(R.string.overlay_permission_description)
                    .setPositiveButton(R.string.open_settings) { _, _ ->
                        try {
                            val intent = Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:${requireContext().packageName}")
                            )
                            startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                            } catch (e2: Exception) {
                                try {
                                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = android.net.Uri.parse("package:${requireContext().packageName}")
                                    })
                                } catch (_: Exception) {}
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }

        // 开机自启与亮屏自启都靠前台保活服务里的动态 SCREEN_ON 接收器兜着，
        // 所以设置一保存就把服务拉起来，别等下次开机。
        if (settings.autoStartOnScreenOn || settings.autoStartOnBoot) {
            CarppService.start(requireContext())
        }

        hasChanges = false
        updateSaveButtonState()

        Toast.makeText(context, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
    }

    private fun checkChanges() {
        hasChanges = pendingAutoStartOnBoot != settings.autoStartOnBoot ||
                pendingAutoStartOnScreenOn != settings.autoStartOnScreenOn ||
                pendingAutoStartOnWifi != settings.autoStartOnWifi ||
                pendingAutoStartWifiSsid != settings.autoStartWifiSsid

        updateSaveButtonState()
    }

    private fun updateSettingsList() {
        val scrollState = recyclerView.layoutManager?.onSaveInstanceState()
        val items = mutableListOf<SettingItem>()

        items.add(SettingItem.CategoryHeader("autoStart", R.string.auto_start_settings))

        items.add(SettingItem.InfoBanner(
            stableId = "autoStartOemWarning",
            textResId = R.string.auto_start_oem_warning
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "autoStartOnBoot",
            nameResId = R.string.auto_start_on_boot_label,
            descriptionResId = R.string.auto_start_on_boot_description,
            isChecked = pendingAutoStartOnBoot!!,
            onCheckedChanged = { isChecked ->
                pendingAutoStartOnBoot = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "autoStartOnScreenOn",
            nameResId = R.string.auto_start_screen_on_label,
            descriptionResId = R.string.auto_start_screen_on_description,
            isChecked = pendingAutoStartOnScreenOn!!,
            onCheckedChanged = { isChecked ->
                pendingAutoStartOnScreenOn = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        if (Build.VERSION.SDK_INT <= 32) {
            items.add(SettingItem.InfoBanner(
                stableId = "autoStartWifiWarning",
                textResId = R.string.auto_start_wifi_warning
            ))

            items.add(SettingItem.ToggleSettingEntry(
                stableId = "autoStartWifi",
                nameResId = R.string.auto_start_wifi_label,
                descriptionResId = R.string.auto_start_wifi_description,
                isChecked = pendingAutoStartOnWifi!!,
                onCheckedChanged = { isChecked ->
                    pendingAutoStartOnWifi = isChecked
                    checkChanges()
                    updateSettingsList()
                }
            ))

            if (pendingAutoStartOnWifi == true) {
                items.add(SettingItem.SettingEntry(
                    stableId = "autoStartWifiSsid",
                    nameResId = R.string.auto_start_wifi_ssid_label,
                    value = if (pendingAutoStartWifiSsid.isNullOrEmpty()) getString(R.string.wifi_ssid_not_set) else pendingAutoStartWifiSsid!!,
                    onClick = {
                        showSsidInputDialog()
                    }
                ))
            }
        }

        // 只保留当前连接方式下有效的选项：WiFi 自启在纯 USB 模式的车机上没有意义。
        val wifiIds = setOf("autoStartWifiWarning", "autoStartWifi", "autoStartWifiSsid")
        val filtered = items.filterNot { item ->
            item.stableId in wifiIds && !settings.showsWifi()
        }

        settingsAdapter.submitList(filtered) {
            scrollState?.let { recyclerView.layoutManager?.onRestoreInstanceState(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        // 回到界面时重新检查悬浮窗权限：用户从系统设置里回来却没给权限的话，
        // 就把依赖它的自启项关掉，免得留个开了也不生效的开关。
        if (!AppPermissions.isOverlayGranted(requireContext())) {
            var disabled = false
            if (settings.autoStartOnBoot) {
                settings.autoStartOnBoot = false
                Settings.syncAutoStartOnBootToDeviceStorage(requireContext(), false)
                pendingAutoStartOnBoot = false
                disabled = true
            }
            if (settings.autoStartOnScreenOn) {
                settings.autoStartOnScreenOn = false
                Settings.syncAutoStartOnScreenOnToDeviceStorage(requireContext(), false)
                pendingAutoStartOnScreenOn = false
                disabled = true
            }
            if (settings.autoStartOnWifi) {
                settings.autoStartOnWifi = false
                settings.autoStartWifiSsid = ""
                Settings.syncAutoStartOnWifiToDeviceStorage(requireContext(), false)
                Settings.syncAutoStartWifiSsidToDeviceStorage(requireContext(), "")
                pendingAutoStartOnWifi = false
                pendingAutoStartWifiSsid = ""
                disabled = true
            }
            if (disabled) {
                AppLog.w("Overlay permission not granted, disabling auto-start settings")
                Toast.makeText(requireContext(), getString(R.string.overlay_permission_denied_auto_start_disabled), Toast.LENGTH_LONG).show()
                checkChanges()
                updateSettingsList()
            }
        }
    }

    private fun showSsidInputDialog() {
        val input = EditText(requireContext())
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setText(pendingAutoStartWifiSsid)
        input.setSelection(input.text.length)

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.enter_wifi_ssid)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                pendingAutoStartWifiSsid = input.text.toString()
                checkChanges()
                updateSettingsList()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.window?.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )
        dialog.show()
        input.requestFocus()
    }
}
