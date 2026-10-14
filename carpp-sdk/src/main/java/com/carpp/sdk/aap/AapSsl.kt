package com.carpp.sdk.aap

import com.carpp.sdk.connection.AccessoryConnection

interface AapSsl {
    fun decrypt(start: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit?
    fun encrypt(offset: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit?
    fun postHandshakeReset()
    fun performHandshake(connection: AccessoryConnection): Boolean
    fun release()
}
