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

package com.bendb.ama.http

import com.bendb.ama.proxy.http.MutableHttpRequest
import io.ktor.http.URLProtocol
import io.ktor.http.fullPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class MutableHttpRequestTest {
    private val request = MutableHttpRequest()

    @Test
    fun absoluteRequestUrlWithGetAndAbsolutePath() {
        request.statusLine = "GET /foo/bar/baz.html HTTP/1.1"
        request.headers["Host"] = "example.com"

        val url = request.absoluteRequestUrl
        assertEquals("example.com", url.host)
        assertEquals("/foo/bar/baz.html", url.fullPath)
        assertEquals(URLProtocol.HTTP, url.protocol)
    }

    @Test
    fun absoluteRequestUrlWithGetAndHostWithPort() {
        request.statusLine = "GET /foo/bar/baz.html HTTP/1.1"
        request.headers["Host"] = "example.com:443"

        assertEquals("https://example.com/foo/bar/baz.html", request.absoluteRequestUrl.toString())
    }

    @Test
    fun absoluteRequestUrlWithGetAndFullUrl() {
        request.statusLine = "GET https://www.example.com/foo/bar/baz.html HTTP/1.1"
        request.headers["Host"] = "www.wrong-host.com"

        val url = request.absoluteRequestUrl
        assertEquals("https://www.example.com/foo/bar/baz.html", url.toString())
    }

    @Test
    fun absoluteRequestUrlWithGetAndAuthority() {
        request.statusLine = "GET www.example.com HTTP/1.1"

        assertEquals("http://www.example.com/", request.absoluteRequestUrl.toString())
    }

    @Test
    fun absoluteRequestUrlWithStarAndGet() {
        request.statusLine = "GET * HTTP/1.1"

        assertEquals(request.requestPath, "*")
        try {
            request.absoluteRequestUrl
            fail("should have thrown an exception")
        } catch (e: Exception) {
            assertEquals("'*' is valid only with OPTIONS requests", e.message ?: "")
        }
    }

    @Test
    fun absoluteRequestUrlWithStarAndOptions() {
        request.statusLine = "OPTIONS * HTTP/1.1"
        request.headers["Host"] = "foo.bar.com:8080"

        assertEquals("http://foo.bar.com:8080", request.absoluteRequestUrl.toString())
    }
}