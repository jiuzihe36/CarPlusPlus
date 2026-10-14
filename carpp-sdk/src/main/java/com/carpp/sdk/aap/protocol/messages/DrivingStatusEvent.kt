package com.carpp.sdk.aap.protocol.messages

import com.carpp.sdk.aap.AapMessage
import com.carpp.sdk.aap.protocol.Channel
import com.carpp.sdk.aap.protocol.proto.Sensors
import com.google.protobuf.Message

class DrivingStatusEvent(status: Sensors.SensorBatch.DrivingStatusData.Status)
    : AapMessage(Channel.ID_SEN, Sensors.SensorsMsgType.SENSOR_EVENT_VALUE, makeProto(status)) {

    companion object {
        private fun makeProto(status: Sensors.SensorBatch.DrivingStatusData.Status): Message {
            return Sensors.SensorBatch.newBuilder()
                    .addDrivingStatus(Sensors.SensorBatch.DrivingStatusData.newBuilder()
                            .setStatus(status.number))
                    .build()
        }
    }
}
