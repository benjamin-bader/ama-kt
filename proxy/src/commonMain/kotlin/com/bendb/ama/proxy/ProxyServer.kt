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

import com.bendb.ama.proxy.http.DefaultConnectionPool
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.tcpNoDelay
import io.ktor.utils.io.core.Closeable
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface ProxyServer : Closeable {
    val port: Int
    val sessionEvents: SharedFlow<SessionEvent>
    val proxyStateFlow: StateFlow<ProxyState>
    fun listen()

    enum class ProxyState {
        STOPPED,
        LISTENING,
    }
}

class DefaultProxyServer(
    private val dispatcher: CoroutineDispatcher,
    override val port: Int,
) : ProxyServer {
    private var jobRef = atomic<Job?>(null)
    private val nextId = atomic(ULong.MIN_VALUE.toLong())

    private val mutableSessionEventFlow = MutableSharedFlow<SessionEvent>()
    private val mutableProxyStateFlow = MutableStateFlow(ProxyServer.ProxyState.STOPPED)

    /**
     * A flow of [SessionEvents][SessionEvent] that occur on this proxy server.
     *
     * This is a *hot* flow, meaning that it will emit events even if there are no
     * subscribers.
     */
    override val sessionEvents: SharedFlow<SessionEvent> = mutableSessionEventFlow.asSharedFlow()

    /**
     * A flow of [ProxyServer.ProxyState] changes that occur on this proxy server.
     *
     * Valid states are [ProxyServer.ProxyState.LISTENING] and [ProxyServer.ProxyState.STOPPED].
     */
    override val proxyStateFlow: StateFlow<ProxyServer.ProxyState> = mutableProxyStateFlow.asStateFlow()

    override fun listen() {
        val supervisorJob = SupervisorJob()
        if (!jobRef.compareAndSet(null, supervisorJob)) {
            throw IllegalStateException("Already listening")
        }

        supervisorJob.invokeOnCompletion { jobRef.compareAndSet(supervisorJob, null) }

        val scope = CoroutineScope(CoroutineName("ProxyServer") + supervisorJob + dispatcher)
        scope.launch {
            try {
                serve(this@launch)
            } catch (e: CancellationException) {
                // We've been closed - no big deal.
            } catch (e: Exception) {
                println("error occurred in proxy server: $e")
                throw e
            }
        }
    }

    private suspend fun serve(scope: CoroutineScope) {
        KtorListener(port).use { listener ->
            mutableProxyStateFlow.value = ProxyServer.ProxyState.LISTENING

            val pool = DefaultConnectionPool(listener.selectorManager)
            while (true) {
                val socket = listener.accept()
                val conn = pool.registerLocalSocket(socket)
                val id = getNextId()
                val session = HttpOneSession(id, pool, conn)

                scope.launch {
                    session.use { session ->
                        session.run()
                            .collect { mutableSessionEventFlow.emit(it) }
                    }
                }

                println("accepted one connection")
            }
        }
    }

    private fun getNextId(): SessionId {
        var oldId: Long
        var id: ULong
        do {
            // Can't use built-in incrementAndGet() because it doesn't understand
            // unsigned longs.
            oldId = nextId.value
            id = oldId.toULong() + 1UL
        } while (!nextId.compareAndSet(oldId, id.toLong()))

        return SessionId(id)
    }

    override fun close() {
        jobRef.getAndSet(null)?.cancel()
        mutableProxyStateFlow.value = ProxyServer.ProxyState.STOPPED
    }
}

private interface Listener : Closeable {
    suspend fun accept(): Socket
}

private class KtorListener(port: Int) : Listener {
    val selectorManager = SelectorManager(Dispatchers.IO)
    private val socket = aSocket(selectorManager).tcp().tcpNoDelay().bind("0.0.0.0", port)

   override suspend fun accept(): Socket {
        return socket.accept()
    }

    override fun close() {
        socket.close()
        selectorManager.close()
    }
}
