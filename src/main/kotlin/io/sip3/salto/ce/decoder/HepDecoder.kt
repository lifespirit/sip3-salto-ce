/*
 * Copyright 2018-2019 SIP3.IO, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.sip3.salto.ce.decoder

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Metrics
import io.sip3.commons.util.IpUtil
import io.sip3.salto.ce.Routes
import io.sip3.salto.ce.USE_LOCAL_CODEC
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.vertx.core.AbstractVerticle
import io.vertx.core.buffer.Buffer
import mu.KotlinLogging
import java.sql.Timestamp

/**
 * Decodes packets in HEP3 protocol
 */
class HepDecoder : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        const val HEADER_LENGTH = 6
        const val TYPE_SIP: Byte = 1
    }

    private val packetsDecoded = Counter.builder("packets_decoded")
            .tag("proto", "hep3")
            .register(Metrics.globalRegistry)

    override fun start() {
        vertx.eventBus().localConsumer<Buffer>(Routes.hep3) { event ->
            try {
                val buffer = event.body()
                decode(buffer)
            } catch (e: Exception) {
                logger.error("HepDecoder 'decode()' failed.", e)
            }
        }
    }

    fun decode(buffer: Buffer) {
        var seconds: Long? = null
        var uSeconds: Long? = null
        var srcAddr: ByteArray? = null
        var dstAddr: ByteArray? = null
        var srcPort: Int? = null
        var dstPort: Int? = null
        var protocolType: Byte? = null
        var payload: ByteArray? = null

        var offset = HEADER_LENGTH
        while (offset < buffer.length()) {
            // Type
            offset += 2
            val type = buffer.getShort(offset)
            // Length
            offset += 2
            val length = buffer.getShort(offset) - 6
            // Value
            offset += 2
            when (type.toInt()) {
                3 -> srcAddr = buffer.getBytes(offset + length - 4, offset + length)
                4 -> dstAddr = buffer.getBytes(offset + length - 4, offset + length)
                7 -> srcPort = buffer.getUnsignedShort(offset)
                8 -> dstPort = buffer.getUnsignedShort(offset)
                9 -> seconds = buffer.getUnsignedInt(offset)
                10 -> uSeconds = buffer.getUnsignedInt(offset)
                11 -> protocolType = buffer.getByte(offset)
                15 -> payload = buffer.getBytes(offset, offset + length)
            }
            offset += length
        }

        val packet = Packet().apply {
            this.timestamp = Timestamp(seconds!! * 1000 + uSeconds!! / 1000).apply { nanos += (uSeconds % 1000).toInt() }
            this.srcAddr = Address().apply {
                addr = IpUtil.convertToString(srcAddr!!)
                port = srcPort!!
            }
            this.dstAddr = Address().apply {
                addr = IpUtil.convertToString(dstAddr!!)
                port = dstPort!!
            }
            when (protocolType) {
                TYPE_SIP -> this.protocolCode = Packet.TYPE_SIP
                else -> throw NotImplementedError("Unknown HEPv3 protocol type: $protocolType")
            }
            this.payload = payload!!
        }

        packetsDecoded.increment()
        vertx.eventBus().send(Routes.router, packet, USE_LOCAL_CODEC)
    }
}