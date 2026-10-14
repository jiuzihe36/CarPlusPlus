package com.carpp.sdk.utils

import android.content.IntentFilter
import com.carpp.sdk.contract.KeyIntent

object IntentFilters {
    val keyEvent = IntentFilter(KeyIntent.action)
}