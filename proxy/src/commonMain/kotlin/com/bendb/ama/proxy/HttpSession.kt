// Amanuensis - a local web proxy for debugging
// Copyright (C) 2023 Benjamin Bader
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.

package com.bendb.ama.proxy

import com.bendb.ama.proxy.http.Connection
import com.bendb.ama.proxy.http.ConnectionPool
import com.bendb.ama.proxy.http.MutableHttpMessage
import com.bendb.ama.proxy.http.MutableHttpRequest
import com.bendb.ama.proxy.http.MutableHttpResponse
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.pool.ByteBufferPool
import io.ktor.utils.io.pool.useInstance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.supervisorScope
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.StringBuilder
import java.nio.ByteBuffer
import java.util.zip.DeflaterInputStream
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

class HttpOneSession(
    override val id: SessionId,
    private val connectionPool: ConnectionPool,
    private val localConn: Connection,
) : Session {

    /*
    I'm not thrilled with this design, where the run() method can only be run once
    *as long as someone subscribes to its result*, because it makes the proxy a passive
    thing that can't do anything until someone else asks it to. I'd rather have it be
    an active entity that also lets other entities listen in on what it's doing.

    I've settled on this design _for now_ because, by virtue of the Flow API and collectAsState(),
    it makes writing a Compose UI relatively simple.  To do better, I suspect I'll need to
    learn about Compose and MVI patterns in quite a bit more depth.
     */

    override suspend fun run(): Flow<SessionEvent> = flow {
        val session = this@HttpOneSession

        emit(SessionEvent.Started(session))

        val tx = HttpTransaction(session, connectionPool, localConn)
        try {
            emit(SessionEvent.TransactionStarted(session, tx))
            tx.run()

            emit(SessionEvent.Stopped(session))
        } catch (t: Throwable) {
            emit(SessionEvent.Error(session, t))
        } finally {
            tx.close()
        }
    }

    override suspend fun close() {
        connectionPool.returnConnection(localConn)
    }
}

class HttpTransaction(
    private val session: HttpOneSession,
    private val pool: ConnectionPool,
    private val localConn: Connection,
) : Transaction {
    private var remoteConn: Connection? = null
    private var mutableTxState: TransactionState = TransactionState.STARTED

    override val request: MutableHttpRequest = MutableHttpRequest()
    override val response: MutableHttpResponse = MutableHttpResponse()

    private val mutableTxEventsFlow: MutableStateFlow<TransactionEvent> =
        MutableStateFlow(
            TransactionEvent.Started(
                TransactionData(
                    TransactionState.STARTED,
                    request.toImmutable(),
                    response.toImmutable()
                )
            )
        )

    override val events: StateFlow<TransactionEvent> = mutableTxEventsFlow.asStateFlow()
    override val state: TransactionState
        get() = mutableTxState

    suspend fun run() {
        try {
            actuallyRun()
        } catch (e: Exception) {
            println("${session.id}: BOOM: $e")
            advanceState(TransactionState.ERROR, e)

            throw e // bubble this up to the session so that it too can fail?
        }
    }

    private suspend fun actuallyRun() {
        val statusLine = localConn.readChannel.readCrLf()
        request.statusLine = statusLine
        advanceState(TransactionState.REQUEST_LINE)

        println("${session.id}: Transaction started")

        readLocalRequest(localConn.readChannel)
        println("${session.id}: Request read")

        if (request.method.value == "CONNECT") {
            println("${session.id}: CONNECT request")
            runTlsSession(request)
            return
        }

        val requestUrl = request.absoluteRequestUrl
        val host = requestUrl.host
        val port = requestUrl.port

        println("${session.id}: Non-CONNECT request, opening remote connection to $host:$port")
        remoteConn = pool.getOrCreateConnection(host, port)
        val remoteConn = remoteConn!!
        println("${session.id}: Remote connection opened, forwarding request")

        request.write(remoteConn.writeChannel)
        remoteConn.writeChannel.flush()

        println("${session.id}: Request forwarded, reading response")
        readRemoteResponse(remoteConn.readChannel, response)

        println("${session.id}: Response read, forwarding response")

        response.write(localConn.writeChannel)
        localConn.writeChannel.flush()

        advanceState(TransactionState.RESPONSE_COMPLETE)
        advanceState(TransactionState.COMPLETED)
        println("${session.id}: Response forwarded, transaction complete")
    }

    private suspend fun readLocalRequest(readChannel: ByteReadChannel) {
        readHeaders(readChannel, request)
        advanceState(TransactionState.REQUEST_HEADERS)

        readBody(readChannel, request)
        advanceState(TransactionState.REQUEST_BODY)
    }

    private suspend fun readRemoteResponse(readChannel: ByteReadChannel, response: MutableHttpResponse) {
        response.statusLine = readChannel.readCrLf()
        advanceState(TransactionState.RESPONSE_LINE)

        readHeaders(readChannel, response)
        advanceState(TransactionState.RESPONSE_HEADERS)

        readBody(readChannel, response)
        advanceState(TransactionState.RESPONSE_BODY)
    }

    private suspend fun readHeaders(readChannel: ByteReadChannel, request: MutableHttpMessage) {
        while (true) {
            val headerLine = readChannel.readCrLf()
            if (headerLine.isBlank()) {
                break
            }

            val (name, value) = headerLine.split(":", limit = 2)
            request.headers.append(name, value.trim())
        }
    }

    private suspend fun readBody(readChannel: ByteReadChannel, message: MutableHttpMessage) {
        if (message.headers.contains("Content-Length")) {
            val contentLength = message.headers["Content-Length"]?.toInt()
                ?: throw IllegalStateException("Content-Length header present, but no value")
            val body = ByteArray(contentLength)
            readChannel.readFully(ByteBuffer.wrap(body))
            message.body = body
        } else if (message.headers.contains("Transfer-Encoding", "chunked")) {
            val compressionMethod = message.headers.getAll("Transfer-Encoding")!!
                .flatMap { it.split(",") }
                .map(String::trim)
                .firstOrNull { it != "chunked" } // This is BAD AND WRONG but why would any sane agent send multiple compression methods?
            message.body = readChunkedBody(readChannel, compressionMethod)
        }
    }

    private suspend fun readChunkedBody(readChannel: ByteReadChannel, compressionMethod: String?): ByteArray {
        val baos = ByteArrayOutputStream()
        while (true) {
            val chunkSize = readChannel.readCrLf().toInt(radix = 16)
            if (chunkSize == 0) {
                break
            }

            val chunk = ByteArray(chunkSize)
            readChannel.readFully(ByteBuffer.wrap(chunk))
            baos.write(chunk)
            readChannel.readCrLf()
        }

        return when (compressionMethod) {
            null -> baos.toByteArray()

            "gzip", "x-gzip" ->
                GZIPInputStream(ByteArrayInputStream(baos.toByteArray())).use { gzip ->
                    gzip.readAllBytes()
                }

            "deflate", "x-deflate" ->
                DeflaterInputStream(ByteArrayInputStream(baos.toByteArray())).use { deflate ->
                    deflate.readAllBytes()
                }

            else -> error("Unsupported compression method: ${compressionMethod}}")
        }
    }

    private suspend fun runTlsSession(request: MutableHttpRequest) {
        val remoteHost: String
        val port: Int
        val ix = request.requestPath.indexOf(":")
        if (ix != -1) {
            remoteHost = request.requestPath.substring(0, ix)
            port = request.requestPath.substring(ix + 1).toInt()
        } else {
            remoteHost = request.requestPath
            port = 443
        }

        val remoteConn = pool.getOrCreateConnection(remoteHost, port)

        advanceState(TransactionState.TLS_SESSION)

        val connectResponse = MutableHttpResponse().apply {
            statusLine = "HTTP/1.1 200 Connection established"
        }
        connectResponse.write(localConn.writeChannel)
        localConn.writeChannel.flush()

        var failure: Exception? = null
        supervisorScope {
            val clientToServer = async { shovelBytes(localConn.readChannel, remoteConn.writeChannel) }
            val serverToClient = async { shovelBytes(remoteConn.readChannel, localConn.writeChannel) }

            try {
                awaitAll(clientToServer, serverToClient)
            } catch (e: CancellationException) {
                // This is fine, it just means the other side closed the connection
            } catch (e: Exception) {
                failure = e
            } finally {
                pool.returnConnection(remoteConn)
            }
        }

        if (failure != null) {
            advanceState(TransactionState.ERROR, failure)
        } else {
            advanceState(TransactionState.COMPLETED)
        }
    }

    private suspend fun shovelBytes(from: ByteReadChannel, to: ByteWriteChannel) {
        BUFFER_POOL.useInstance { buffer ->
            while (true) {
                buffer.clear()
                val bytesRead = from.readAvailable(buffer)
                if (bytesRead == -1) {
                    break
                }

                buffer.flip()
                to.writeFully(buffer)
                to.flush()
            }
        }
        coroutineContext.cancel()
    }

    private fun advanceState(nextState: TransactionState, err: Throwable? = null) {
        require(nextState != TransactionState.ERROR || err == null)
        check(nextState > state) { "Attempted to transition from $state to $nextState" }

        val data = TransactionData(nextState, request.toImmutable(), response.toImmutable())
        val event = when (nextState) {
            TransactionState.STARTED -> TransactionEvent.Started(data)
            TransactionState.REQUEST_LINE -> TransactionEvent.RequestLine(data)
            TransactionState.REQUEST_HEADERS -> TransactionEvent.RequestHeaders(data)
            TransactionState.REQUEST_BODY -> TransactionEvent.RequestBody(data)
            TransactionState.REQUEST_COMPLETE -> TransactionEvent.RequestComplete(data)
            TransactionState.RESPONSE_LINE -> TransactionEvent.ResponseLine(data)
            TransactionState.RESPONSE_HEADERS -> TransactionEvent.ResponseHeaders(data)
            TransactionState.RESPONSE_BODY -> TransactionEvent.ResponseBody(data)
            TransactionState.RESPONSE_COMPLETE -> TransactionEvent.ResponseComplete(data)
            TransactionState.TLS_SESSION -> TransactionEvent.TlsSession(data)
            TransactionState.COMPLETED -> TransactionEvent.Completed(data)
            TransactionState.ERROR -> TransactionEvent.Error(data, err!!)
        }

        mutableTxState = nextState
        mutableTxEventsFlow.value = event
    }

    suspend fun close() {
        remoteConn?.let { pool.returnConnection(it) }
        remoteConn = null
    }

    override fun equals(other: Any?): Boolean {
        if (other !is HttpTransaction) {
            return false
        }

        if (localConn != other.localConn) {
            return false
        }

        if (state != other.state) {
            return false
        }

        if (remoteConn != other.remoteConn) {
            return false
        }

        println("I HAVE COMPARED MYSELF AND FOUND AN EQUAL")

        return true
    }

    override fun hashCode(): Int {
        var result = localConn.hashCode()
        result = 31 * result + state.hashCode()
        result = 31 * result + (remoteConn?.hashCode() ?: 0)
        return result
    }

    companion object {
        // IIS limits all headers to 16kb, the largest buffer of all the major
        // web servers.  If a client sends more than 16kb of headers, then it's
        // as likely as not that nothing will willingly accept it - we may as
        // well limit our resource consumption.
        private const val DEFAULT_LINE_BUFFER_CAPACITY = 1024 * 16

        private val BUFFER_POOL = ByteBufferPool(bufferSize = DEFAULT_LINE_BUFFER_CAPACITY)
    }
}

/**
 * Reads a full CR-LF-terminated line from the channel, excluding the trailing CR-LF.
 *
 * Note that *no character decoding is performed* - the input is assumed to be ASCII,
 * per the HTTP specification.  Also note that the trailing CR-LF are consumed, even
 * though they are not included in the result.
 */
suspend fun ByteReadChannel.readCrLf(): String {
    val sb = StringBuilder()

    var cr = false
    while (true) {
        val c = (readByte().toInt() and 0xFF).toChar()
        sb.append(c)

        when (c) {
            '\r' -> cr = true
            '\n' -> {
                if (cr) {
                    sb.setLength(sb.length - 2)
                    return sb.toString()
                }
            }
            else -> cr = false
        }
    }
}
