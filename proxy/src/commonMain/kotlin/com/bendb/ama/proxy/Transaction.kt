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

import com.bendb.ama.proxy.http.HttpRequest
import com.bendb.ama.proxy.http.HttpResponse
import com.bendb.ama.proxy.http.MutableHttpRequest
import com.bendb.ama.proxy.http.MutableHttpResponse
import kotlinx.coroutines.flow.StateFlow

interface Transaction : SuspendCloseable {
    /**
     * A *hot* flow of transaction events.
     */
    val events: StateFlow<TransactionEvent>
    val state: TransactionState

    val request: MutableHttpRequest
    val response: MutableHttpResponse
}

class TransactionData(
    val state: TransactionState,
    val request: HttpRequest,
    val response: HttpResponse,
)

sealed class TransactionEvent(
    val tx: TransactionData
) {
    class Started(tx: TransactionData) : TransactionEvent(tx)
    class RequestLine(tx: TransactionData) : TransactionEvent(tx)
    class RequestHeaders(tx: TransactionData) : TransactionEvent(tx)
    class RequestBody(tx: TransactionData) : TransactionEvent(tx)
    class RequestComplete(tx: TransactionData) : TransactionEvent(tx)
    class ResponseLine(tx: TransactionData) : TransactionEvent(tx)
    class ResponseHeaders(tx: TransactionData) : TransactionEvent(tx)
    class ResponseBody(tx: TransactionData) : TransactionEvent(tx)
    class ResponseComplete(tx: TransactionData) : TransactionEvent(tx)
    class TlsSession(tx: TransactionData) : TransactionEvent(tx)
    class Error(tx: TransactionData, val e: Throwable) : TransactionEvent(tx)
    class Completed(tx: TransactionData): TransactionEvent(tx)
}

enum class TransactionState {
    STARTED,
    REQUEST_LINE,
    REQUEST_HEADERS,
    REQUEST_BODY,
    REQUEST_COMPLETE,
    RESPONSE_LINE,
    RESPONSE_HEADERS,
    RESPONSE_BODY,
    RESPONSE_COMPLETE,
    TLS_SESSION,
    COMPLETED,
    ERROR,
}
