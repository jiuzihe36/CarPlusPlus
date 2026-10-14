package com.carpp.sdk.aap

import android.view.KeyEvent

object ProjectionKeyPolicy {

    fun shouldRouteBackKeyToProjection(actionToPhysicalKeyCode: Map<Int, Int>, keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_BACK &&
                actionToPhysicalKeyCode.containsValue(KeyEvent.KEYCODE_BACK)
    }
}