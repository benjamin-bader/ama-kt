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

package com.bendb.ama.proxy.http

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ConnectionPool {
    suspend fun getOrCreateConnection(host: String, port: Int): Connection
    suspend fun returnConnection(connection: Connection)
    suspend fun destroyConnection(connection: Connection)

    // Used to register a socket that was created outside of the pool,
    // like a connection from the local machine to the proxy server.
    suspend fun registerLocalSocket(socket: Socket): Connection
}

class DefaultConnectionPool(
    private val selectorManager: SelectorManager
) : ConnectionPool {
    private val mux = Mutex()
    private val connections = mutableMapOf<String, PooledConnection>()

    override suspend fun getOrCreateConnection(host: String, port: Int): Connection {
        mux.withLock {
            val key = "$host:$port"
            val maybeConnection = connections[key]
            if (maybeConnection != null) {
                if (maybeConnection.isClosed) {
                    connections.remove(key)
                } else {
                    return maybeConnection
                }
            }

            return connections.getOrPut(key) {
                createConnection(host, port)
            }
        }
    }

    private suspend fun createConnection(host: String, port: Int): PooledConnection {
        val s = aSocket(selectorManager).tcp().connect(host, port) { noDelay = true }
        return PooledConnection("$host:$port", s)
    }

    override suspend fun returnConnection(connection: Connection) {
        // TODO: Implement some sort of pooling
        destroyConnection(connection)
    }

    override suspend fun destroyConnection(connection: Connection) {
        require(connection is PooledConnection) { "Connection was not created by this pool" }

        mux.withLock { connections.remove(connection.key) }

        try {
            connection.close()
        } catch (e: Throwable) {
            // Ignore
        }
    }

    override suspend fun registerLocalSocket(socket: Socket): Connection {
        val key = "local:${socket.remoteAddress}"
        val connection = PooledConnection(key, socket)

        mux.withLock {
            val old = connections.put(key, connection)
            if (old != null) {
                error("Socket already registered: ${socket.remoteAddress}")
            }
        }

        return connection
    }

    private class PooledConnection(val key: String, socket: Socket) : Connection(socket)
}