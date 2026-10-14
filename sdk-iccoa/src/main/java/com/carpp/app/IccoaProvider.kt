package com.carpp.app

import android.content.Context
import com.carpp.sdk.connection.IcocaBridge
import com.carpp.sdk.icoca.IcocaProjectionActivity
import com.carpp.sdk.sdk.PhoneLink
import com.carpp.sdk.sdk.PhoneLinkProvider

/**
 * ICCOA CarLink 协议插件的注册入口。
 *
 * 宿主 App 只要 `PhoneLinkRegistry.install(IccoaProvider())` 一次，SDK 就会用
 * [IcocaBridge] 当手机链路，用 [IcocaProjectionActivity] 当投影界面。
 */
class IccoaProvider : PhoneLinkProvider {

    override val id: String = "iccoa-carlink"

    override fun create(context: Context): PhoneLink = IcocaBridge(context)

    override fun projectionIntent(context: Context) = IcocaProjectionActivity.intent(context)

    override val projectionForeground: Boolean
        get() = IcocaProjectionActivity.isForeground
}
