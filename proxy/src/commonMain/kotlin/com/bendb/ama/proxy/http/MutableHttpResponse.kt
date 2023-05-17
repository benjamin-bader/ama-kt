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

class MutableHttpResponse : MutableHttpMessage() {
    var httpVersion: String = "HTTP/1.1" // a sensible default
    var statusCode: Int = 0
    var statusMessage: String = ""

    override var statusLine: String
        get() = "$httpVersion $statusCode $statusMessage"
        set(value) {
            val parts = value.split(" ", limit = 3)
            require(parts.size == 3) { "Invalid status line: $value" }

            httpVersion = parts[0]
            statusCode = parts[1].toInt()
            statusMessage = parts[2]
        }

    fun toImmutable(): HttpResponse {
        return HttpResponse(
            statusCode = statusCode,
            statusMessage = statusMessage,
            headers = headers.build(),
            body = body,
        )
    }
}
