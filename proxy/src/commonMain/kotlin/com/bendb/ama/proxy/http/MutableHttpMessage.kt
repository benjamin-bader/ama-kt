package com.bendb.ama.proxy.http

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

import io.ktor.http.HeadersBuilder
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.nio.ByteBuffer

abstract class MutableHttpMessage {
    abstract var statusLine: String
    val headers: HeadersBuilder = HeadersBuilder()
    var body: ByteArray? = null

    suspend fun write(channel: ByteWriteChannel) {
        channel.writeAscii(statusLine)
        channel.writeAscii("\r\n")

        for ((name, values) in headers.entries()) {
            if (name.compareTo("Transfer-Encoding", ignoreCase = true) == 0) {
                continue
            }
            channel.writeAscii(name)
            channel.writeAscii(": ")
            channel.writeAscii(values.joinToString("; "))
            channel.writeAscii("\r\n")
        }

        // TODO: We need a better way to handle this (where "this" == manipulate headers as a proxy)
        if (body != null && headers["Content-Length"] == null) {
            channel.writeAscii("Content-Length: ${body!!.size}\r\n")
        }

        channel.writeAscii("\r\n")

        val b = body
        if (b != null) {
            channel.writeFully(b)
        }
    }

    private suspend inline fun ByteWriteChannel.writeAscii(text: String) {
        writeFully(ByteBuffer.wrap(text.toByteArray(Charsets.US_ASCII)))
    }
}
