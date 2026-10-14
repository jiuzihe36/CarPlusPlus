package com.carpp.sdk.aap.protocol.messages

import com.carpp.sdk.aap.AapMessage
import com.carpp.sdk.aap.protocol.Channel
import com.carpp.sdk.aap.protocol.proto.Input
import com.google.protobuf.Message



class ScrollWheelEvent(timeStamp: Long, delta: Int)
    : AapMessage(Channel.ID_INP, Input.MsgType.EVENT_VALUE, makeProto(timeStamp, delta)) {
    companion object {
        const val KEYCODE_SCROLL_WHEEL = 65536

        private fun makeProto(timeStamp: Long, delta: Int): Message {

            return Input.InputReport.newBuilder().also {
                it.timestamp = timeStamp * 1000000L
                it.keyEvent = Input.KeyEvent.newBuilder().build() // TODO: check if requred
                it.relativeEvent = Input.RelativeEvent.newBuilder().also { event ->
                    event.addData(Input.RelativeEvent_Rel.newBuilder().apply {
                        setDelta(delta)
                        keycode = KEYCODE_SCROLL_WHEEL
                    })
                }.build()
            }.build()

        }
    }

}
