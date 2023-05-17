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

import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodedPath
import io.ktor.http.fullPath
import io.ktor.http.isAbsolutePath
import io.ktor.http.path
import io.ktor.http.set
import java.util.Locale

class MutableHttpRequest : MutableHttpMessage() {
    var method: HttpMethod = HttpMethod("")
    var requestPath: String = ""
    var httpVersion: String = ""

    /**
     * Returns an absolute [Url] identifying the target of this request,
     * based on the request line and the Host header.
     *
     * Attempts to follow https://www.rfc-editor.org/rfc/rfc9112#section-3.3,
     * loosely.
     */
    val absoluteRequestUrl: Url
        get() {
            if (requestPath == "*") {
                // This is allowed by HTTP, but not for GET methods.
                check(method == HttpMethod.Options) { "'*' is valid only with OPTIONS requests" }

                // We are using this method primarily in service of establishing
                // a remote connection, so we'll fall back to using the Host header.
            } else if (requestPath.startsWith("http")) {
                // We've got a scheme, so let's assume that this is an absolute URL.
                // TODO: Validate that this doesn't result in e.g. localhost or whatever.
                return Url(requestPath)
            } else if ("/" !in requestPath) {
                // Assuming this is an authority
                return URLBuilder().apply {
                    host = requestPath
                    path("/")
                }.build()
            }

            val hostAndPort = headers.getAll("Host")?.firstOrNull() ?: error("No Host header")
            val host: String
            val port: Int
            if (":" in hostAndPort) {
                val parts = hostAndPort.split(":", limit = 2)
                host = parts[0]
                port = parts[1].toInt()
            } else {
                host = hostAndPort
                port = 80 // TODO: This is a bad assumption

                // To expand on that TODO - if the request is coming in over HTTPS, then
                // we should be using port 443 instead.  We don't currently support proxying
                // HTTPS, but when we do, we'll have to revisit.
            }

            // TODO: As above - bad assumption.  If the original request was via TLS, then
            //       we must accommodate.
            val protocol = if (port == 443) URLProtocol.HTTPS else URLProtocol.HTTP

            return URLBuilder().apply {
                this.protocol = protocol
                this.host = host
                this.port = port

                if (requestPath != "*") {
                    this.encodedPath = requestPath
                }
            }.build()
        }

    override var statusLine: String
        get() = "${method.value} $requestPath $httpVersion"
        set(value) {
            val parts = value.split(" ", limit = 3)
            require(parts.size == 3) { "Invalid status line: $value" }

            val method = HttpMethod.parse(parts[0].uppercase(Locale.ROOT))
            val requestUri = parts[1]
            val httpVersion = parts[2]

            this.method = method
            this.requestPath = requestUri
            this.httpVersion = httpVersion
        }

    fun toImmutable(): HttpRequest {
        return HttpRequest(
            method = method,
            requestPath = requestPath,
            httpVersion = httpVersion,
            headers = headers.build(),
            body = body,
        )
    }
}
