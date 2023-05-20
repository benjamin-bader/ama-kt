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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class ProxyServer(
    private val dispatcher: CoroutineDispatcher,
    val port: Int,
) : Closeable {
    private var scopeRef = atomic<CoroutineScope?>(null)
    private val nextId = atomic(ULong.MIN_VALUE.toLong())

    private val mutableSessionEventFlow = MutableSharedFlow<SessionEvent>()
    private val mutableProxyStateFlow = MutableStateFlow(ProxyState.STOPPED)

    val sessionEvents: SharedFlow<SessionEvent> = mutableSessionEventFlow.asSharedFlow()
    val proxyStateEvents: StateFlow<ProxyState> = mutableProxyStateFlow.asStateFlow()

    suspend fun listen() {
        val scope = CoroutineScope(CoroutineName("ProxyServer") + SupervisorJob() + dispatcher)
        val existingScope = scopeRef.value
        if (existingScope != null || !scopeRef.compareAndSet(null, scope)) {
            throw IllegalStateException("Already listening")
        }

        val job = scope.launch {
            try {
                serve(scope)
            } catch (e: CancellationException) {
                // We've been closed - no big deal.
            } catch (e: Exception) {
                println("error occurred in proxy server: $e")
                throw e
            }
        }

        job.invokeOnCompletion { close() }
    }

    private suspend fun serve(scope: CoroutineScope) {
        KtorListener(port).use { listener ->
            mutableProxyStateFlow.value = ProxyState.LISTENING

            val pool = DefaultConnectionPool(listener.selectorManager)
            while (true) {
                val socket = listener.accept()
                val conn = pool.registerLocalSocket(socket)
                val id = getNextId()
                val session = HttpOneSession(id, pool, conn)

                scope.launch {
                    session.use { session ->
                        session.run()
                            .onEach { mutableSessionEventFlow.emit(it) }
                            .collect()
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
        scopeRef.getAndSet(null)?.cancel()
        mutableProxyStateFlow.value = ProxyState.STOPPED
    }

    enum class ProxyState {
        STOPPED,
        LISTENING,
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
