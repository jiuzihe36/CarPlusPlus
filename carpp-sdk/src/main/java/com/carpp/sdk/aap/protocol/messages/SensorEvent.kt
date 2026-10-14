package com.carpp.sdk.aap.protocol.messages

import com.carpp.sdk.aap.AapMessage
import com.carpp.sdk.aap.protocol.Channel
import com.carpp.sdk.aap.protocol.proto.Sensors
import com.google.protobuf.Message

open class SensorEvent(val sensorType: Int, proto: Message)
    : AapMessage(Channel.ID_SEN, Sensors.SensorsMsgType.SENSOR_EVENT_VALUE, proto)
