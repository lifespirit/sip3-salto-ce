/*
 * Copyright 2018-2021 SIP3.IO, Inc.
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

package io.sip3.salto.ce.sip

import gov.nist.javax.sip.message.SIPMessage
import io.sip3.commons.micrometer.Metrics
import io.sip3.commons.util.MutableMapUtil
import io.sip3.commons.util.format
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.util.localRequest
import io.sip3.salto.ce.Attributes
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Packet
import io.sip3.salto.ce.util.cseqMethod
import io.sip3.salto.ce.util.hasSdp
import io.sip3.salto.ce.util.transactionId
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Handles SIP Transactions
 */
@Instance
open class SipTransactionHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        val PREFIX = RoutesCE.sip + "_transaction"

        const val RESPONSE_DELAY = "response-delay"

        // To simplify call aggregation we decided to skip ACK and CANCEL transaction.
        // Moreover, skipped ACK and CANCEL transactions will not affect final result.
        val EXCLUDED_METHODS = listOf("ACK", "CANCEL")
    }

    private var timeSuffix: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private var trimToSizeDelay: Long = 3600000
    private var expirationDelay: Long = 1000
    private var responseTimeout: Long = 3000
    private var aggregationTimeout: Long = 60000
    private var terminationTimeout: Long = 2000
    private var transactionExclusions = emptyList<String>()
    private var saveSipMessagePayload = true

    private var recordCallUsersAttributes = false
    private var instances = 1

    private var transactions = mutableMapOf<String, SipTransaction>()

    override fun start() {
        config().getString("time-suffix")?.let {
            timeSuffix = DateTimeFormatter.ofPattern(it)
        }
        config().getJsonObject("sip")?.getJsonObject("transaction")?.let { config ->
            config.getLong("trim-to-size-delay")?.let {
                trimToSizeDelay = it
            }
            config.getLong("expiration-delay")?.let {
                expirationDelay = it
            }
            config.getLong("response-timeout")?.let {
                responseTimeout = it
            }
            config.getLong("aggregation-timeout")?.let {
                aggregationTimeout = it
            }
            config.getLong("termination-timeout")?.let {
                terminationTimeout = it
            }
            config.getJsonArray("transaction-exclusions")?.let {
                transactionExclusions = it.map(Any::toString)
            }
            config.getBoolean("save-sip-message-payload")?.let {
                saveSipMessagePayload = it
            }
        }
        config().getJsonObject("attributes")?.getBoolean("record-call-users")?.let {
            recordCallUsersAttributes = it
        }
        config().getJsonObject("vertx")?.getInteger("instances")?.let {
            instances = it
        }

        vertx.setPeriodic(trimToSizeDelay) {
            transactions = MutableMapUtil.mutableMapOf(transactions)
        }
        vertx.setPeriodic(expirationDelay) {
            terminateExpiredTransactions()
        }

        GlobalScope.launch(vertx.dispatcher()) {
            val index = vertx.sharedData().getLocalCounter(PREFIX).await()
            vertx.eventBus().localConsumer<Pair<Packet, SIPMessage>>(PREFIX + "_${index.andIncrement.await()}") { event ->
                try {
                    val (packet, message) = event.body()
                    handle(packet, message)
                } catch (e: Exception) {
                    logger.error("SipTransactionHandler 'handle()' failed.", e)
                }
            }
        }
    }

    open fun handle(packet: Packet, message: SIPMessage) {
        if (message.cseqMethod() in EXCLUDED_METHODS) {
            return
        }

        val transaction = transactions.getOrPut(message.transactionId()) { SipTransaction() }
        transaction.addMessage(packet, message, extend = (saveSipMessagePayload && message.cseqMethod() == "INVITE"))

        // Send SDP
        if (transaction.cseqMethod == "INVITE" && transaction.response?.hasSdp() == true) {
            vertx.eventBus().localRequest<Any>(RoutesCE.sdp + "_session", transaction)
        }
    }

    open fun terminateExpiredTransactions() {
        val now = System.currentTimeMillis()

        transactions.filterValues { transaction ->
            // 1. Wait `termination-timeout` if transaction was terminated
            // 2. Wait `response-timeout` if transaction was created but hasn't received any response yet
            // 3. Wait `aggregation-timeout` if transaction was created and has received response with non final status code
            (transaction.terminatedAt?.let { it + terminationTimeout }
                ?: transaction.createdAt + (transaction.establishedAt?.let { aggregationTimeout } ?: responseTimeout)) < now
        }.forEach { (tid, transaction) ->
            transactions.remove(tid)
            routeTransaction(transaction)
        }
    }

    open fun routeTransaction(transaction: SipTransaction) {
        val prefix = when (transaction.cseqMethod) {
            "REGISTER", "NOTIFY", "MESSAGE", "OPTIONS", "SUBSCRIBE" -> RoutesCE.sip + "_${transaction.cseqMethod.toLowerCase()}"
            else -> RoutesCE.sip + "_call"
        }

        when (prefix) {
            RoutesCE.sip + "_call", RoutesCE.sip + "_register" -> {
                val index = transaction.callId.hashCode()
                val route = prefix + "_${abs(index % instances)}"
                vertx.eventBus().localRequest<Any>(route, transaction)
            }
            else -> {
                writeAttributes(transaction)
                writeToDatabase(prefix, transaction)
                calculateTransactionMetrics(prefix, transaction)
            }
        }
    }

    private fun calculateTransactionMetrics(prefix: String, transaction: SipTransaction) {
        transaction.terminatedAt?.let { terminatedAt ->
            val attributes = excludeTransactionAttributes(transaction.attributes)
            Metrics.timer(prefix + "_$RESPONSE_DELAY", attributes).record(terminatedAt - transaction.createdAt, TimeUnit.MILLISECONDS)
        }
    }

    private fun excludeTransactionAttributes(attributes: Map<String, Any>): MutableMap<String, Any> {
        return attributes.toMutableMap().apply {
            remove(Attributes.caller)
            remove(Attributes.callee)
            remove(Attributes.error_code)
            remove(Attributes.error_type)
            remove(Attributes.x_call_id)
            remove(Attributes.retransmits)
            transactionExclusions.forEach { remove(it) }
        }
    }

    open fun writeAttributes(transaction: SipTransaction) {
        val attributes = transaction.attributes
            .toMutableMap()
            .apply {
                remove(Attributes.src_host)
                remove(Attributes.dst_host)

                put(Attributes.method, transaction.cseqMethod)

                put(Attributes.call_id, "")
                remove(Attributes.x_call_id)

                val caller = get(Attributes.caller) ?: transaction.caller
                put(Attributes.caller, if (recordCallUsersAttributes) caller else "")

                val callee = get(Attributes.callee) ?: transaction.callee
                put(Attributes.callee, if (recordCallUsersAttributes) callee else "")
            }

        vertx.eventBus().localRequest<Any>(RoutesCE.attributes, Pair("sip", attributes))

    }

    open fun writeToDatabase(prefix: String, transaction: SipTransaction) {
        val collection = prefix + "_index_" + timeSuffix.format(transaction.createdAt)

        val document = JsonObject().apply {
            put("document", JsonObject().apply {
                put("state", transaction.state)

                put("created_at", transaction.createdAt)
                put("terminated_at", transaction.terminatedAt)

                val src = transaction.srcAddr
                put("src_addr", src.addr)
                put("src_port", src.port)
                src.host?.let { put("src_host", it) }

                val dst = transaction.dstAddr
                put("dst_addr", dst.addr)
                put("dst_port", dst.port)
                dst.host?.let { put("dst_host", it) }

                put("caller", transaction.caller)
                put("callee", transaction.callee)
                put("call_id", transaction.callId)

                transaction.attributes.forEach { (name, value) -> put(name, value) }
            })
        }

        vertx.eventBus().localRequest<Any>(RoutesCE.mongo_bulk_writer, Pair(collection, document))
    }
}