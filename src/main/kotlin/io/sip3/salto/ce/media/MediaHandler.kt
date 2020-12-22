/*
 * Copyright 2018-2020 SIP3.IO, Inc.
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

package io.sip3.salto.ce.media

import io.sip3.commons.domain.SdpSession
import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.commons.micrometer.Metrics
import io.sip3.commons.util.format
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.util.localRequest
import io.sip3.salto.ce.Attributes
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.rtpr.RtprSession
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import mu.KotlinLogging
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Handles media sessions
 */
@Instance
open class MediaHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        const val PREFIX = "media"
        
        const val REPORTS = PREFIX + "_reports"
        const val BAD_REPORTS = PREFIX + "_bad-reports"
        const val DURATION = PREFIX + "_duration"

        const val CREATED_DIFF = PREFIX + "_created-diff"
        const val TERMINATED_DIFF = PREFIX + "_terminated-diff"
        const val DURATION_DIFF = PREFIX + "_duration-diff"
    }

    private var timeSuffix: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    private var expirationDelay: Long = 4000L
    private var aggregationTimeout: Long = 30000L

    private var media = mutableMapOf<String, MutableMap<String, MediaSession>>()

    override fun start() {
        config().getString("time-suffix")?.let {
            timeSuffix = DateTimeFormatter.ofPattern(it)
        }

        config().getJsonObject("media")?.let { config ->
            config.getLong("expiration-delay")?.let {
                expirationDelay = it
            }
            config.getLong("aggregation-timeout")?.let {
                aggregationTimeout = it
            }
        }

        vertx.setPeriodic(expirationDelay) {
            terminateExpiredMediaSessions()
        }

        vertx.eventBus().localConsumer<List<SdpSession>>(RoutesCE.sdp + "_info") { event ->
            try {
                val sessions = event.body()
                handleSdp(sessions)
            } catch (e: Exception) {
                logger.error(e) { "MediaHandler 'handleSdp()' failed." }
            }
        }

        vertx.eventBus().localConsumer<RtprSession>(RoutesCE.media) { event ->
            try {
                val session = event.body()
                handle(session)
            } catch (e: Exception) {
                logger.error(e) { "MediaHandler 'handle()' failed." }
            }
        }

        vertx.eventBus().localConsumer<Pair<String, String>>(RoutesCE.media + "_keep-alive") { event ->
            try {
                val (callId, compositeKey) = event.body()
                handleKeepAlive(callId, compositeKey)
            } catch (e: Exception) {
                logger.error(e) { "MediaHandler 'handleKeepAlive()' failed." }
            }
        }
    }

    open fun terminateExpiredMediaSessions() {
        val now = System.currentTimeMillis()

        media.filterValues { sessions ->
            sessions.filterValues { session ->
                session.aliveAt + aggregationTimeout < now
            }.forEach { (sid, session) ->
                sessions.remove(sid)
                terminateMediaSession(session)
            }
            return@filterValues sessions.isEmpty()
        }.forEach { (callId, _) ->
            media.remove(callId)
        }
    }

    open fun terminateMediaSession(session: MediaSession) {
        if (session.hasMedia()) {
            writeToDatabase("media_call_index", session)
            calculateMetrics(session)
        }
    }

    open fun calculateMetrics(session: MediaSession) {
        session.apply {
            val attributes = mutableMapOf<String, Any>().apply {
                srcAddr.host?.let { put("src_host", it) }
                dstAddr.host?.let { put("dst_host", it) }
                codecName?.let { put("codec_name", it) }
            }

            Metrics.summary(REPORTS, attributes).record(reportCount.toDouble())
            Metrics.summary(BAD_REPORTS, attributes).record(badReportCount.toDouble())
            Metrics.timer(DURATION, attributes).record(duration, TimeUnit.MILLISECONDS)

            createdAtDiff?.let { Metrics.summary(CREATED_DIFF, attributes).record(it.toDouble()) }
            terminatedAtDiff?.let { Metrics.summary(TERMINATED_DIFF, attributes).record(it.toDouble()) }
            durationDiff?.let { Metrics.summary(DURATION_DIFF, attributes).record(it.toDouble()) }
        }
    }

    open fun handleSdp(sessions: List<SdpSession>) {
        if (sessions.size != 2) {
            throw IllegalStateException("Invalid SdpSession count: ${sessions.size}}")
        }

        val request = sessions[0]
        val srcAddr = Address().apply {
            addr = request.address
            port = request.rtpPort
        }

        val response = sessions[1]
        val dstAddr = Address().apply {
            addr = response.address
            port = response.rtpPort
        }

        media.getOrPut(request.callId) { mutableMapOf() }.putIfAbsent(dstAddr.compositeKey(srcAddr), MediaSession(srcAddr, dstAddr, request.callId))
    }

    // TODO: Update after full migration to MediaSession
    open fun handle(session: RtprSession) {
        val report = session.report
        writeAttributes(report)

        val prefix = when (report.source) {
            RtpReportPayload.SOURCE_RTP -> "rtpr_rtp_index"
            RtpReportPayload.SOURCE_RTCP -> "rtpr_rtcp_index"
            else -> throw IllegalArgumentException("Unsupported RTP Report source: '${report.source}'")
        }
        writeToDatabase(prefix, session)

        if (report.source == RtpReportPayload.SOURCE_RTP && report.callId != null) {
            val mediaSession = media.getOrPut(report.callId!!) { mutableMapOf() }
                .getOrPut(session.dstAddr.compositeKey(session.srcAddr)) { MediaSession(session.srcAddr, session.dstAddr, report.callId!!) }
            mediaSession.add(session)
        }
    }

    private fun handleKeepAlive(callId: String, compositeKey: String) {
        media.get(callId)?.get(compositeKey)?.apply {
            aliveAt = System.currentTimeMillis()
        }
    }

    open fun writeAttributes(report: RtpReportPayload) {
        val attributes = mutableMapOf<String, Any>().apply {
            put(Attributes.mos, report.mos)
            put(Attributes.r_factor, report.rFactor)
        }

        val prefix = when (report.source) {
            RtpReportPayload.SOURCE_RTP -> "rtp"
            RtpReportPayload.SOURCE_RTCP -> "rtcp"
            else -> throw IllegalArgumentException("Unsupported RTP Report source: '${report.source}'")
        }
        vertx.eventBus().localRequest<Any>(RoutesCE.attributes, Pair(prefix, attributes))
    }

    @Deprecated("Remove it after successful media session tests")
    open fun writeToDatabase(prefix: String, session: RtprSession) {
        val collection = prefix + "_" + timeSuffix.format(session.createdAt)

        val operation = JsonObject().apply {
            put("document", toJsonObject(session).apply {
                put("started_at", session.report.startedAt)
                put("call_id", session.report.callId)
            })
        }

        vertx.eventBus().localRequest<Any>(RoutesCE.mongo_bulk_writer, Pair(collection, operation))
    }

    open fun writeToDatabase(prefix: String, session: MediaSession) {
        val collection = prefix + "_" + timeSuffix.format(session.createdAt)

        val operation = JsonObject().apply {
            put("document", JsonObject().apply {
                put("created_at", session.createdAt)
                put("terminated_at", session.terminatedAt)

                val src = session.srcAddr
                put("src_addr", src.addr)
                put("src_port", src.port)
                src.host?.let { put("src_host", it) }

                val dst = session.dstAddr
                put("dst_addr", dst.addr)
                put("dst_port", dst.port)
                dst.host?.let { put("dst_host", it) }

                put("call_id", session.callId)
                session.codecName?.let { put("codec_name", it) }
                put("duration", session.duration)

                put("report_count", session.reportCount)
                put("bad_report_count", session.badReportCount)
                put("one_way", session.isOneWay)

                session.forward.rtp?.let { put("forward_rtp", toJsonObject(it))}
                session.forward.rtcp?.let { put("forward_rtcp", toJsonObject(it))}
                session.reverse.rtp?.let { put("reverse_rtp", toJsonObject(it))}
                session.reverse.rtcp?.let { put("reverse_rtcp", toJsonObject(it))}
            })
        }

        vertx.eventBus().localRequest<Any>(RoutesCE.mongo_bulk_writer, Pair(collection, operation))
    }

    // TODO: Exclude useless props
    private fun toJsonObject(session: RtprSession): JsonObject {
        val report = session.report
        return JsonObject().apply {
            put("created_at", session.createdAt)
            put("terminated_at", session.terminatedAt)

            val src = session.srcAddr
            put("src_addr", src.addr)
            put("src_port", src.port)
            src.host?.let { put("src_host", it) }

            val dst = session.dstAddr
            put("dst_addr", dst.addr)
            put("dst_port", dst.port)
            dst.host?.let { put("dst_host", it) }

            put("payload_type", report.payloadType.toInt())
            put("ssrc", report.ssrc)
            put("duration", report.duration)

            put("packets", JsonObject().apply {
                put("expected", report.expectedPacketCount)
                put("received", report.receivedPacketCount)
                put("lost", report.lostPacketCount)
                put("rejected", report.rejectedPacketCount)
            })

            put("jitter", JsonObject().apply {
                put("last", report.lastJitter.toDouble())
                put("avg", report.avgJitter.toDouble())
                put("min", report.minJitter.toDouble())
                put("max", report.maxJitter.toDouble())
            })

            put("r_factor", report.rFactor.toDouble())
            put("mos", report.mos.toDouble())
            put("fraction_lost", report.fractionLost.toDouble())
        }
    }
}