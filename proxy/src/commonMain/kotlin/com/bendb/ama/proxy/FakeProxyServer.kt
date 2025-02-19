package com.bendb.ama.proxy

import com.bendb.ama.proxy.http.MutableHttpRequest
import com.bendb.ama.proxy.http.MutableHttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn

class FakeProxyServer: ProxyServer {
    override var port: Int = 0
    //override var sessionEvents = MutableSharedFlow<SessionEvent>()
    override var proxyStateFlow = MutableStateFlow(ProxyServer.ProxyState.STOPPED)

    override fun listen() {
        TODO("Not yet implemented")
    }

    override fun close() {
        TODO("Not yet implemented")
    }

    override val sessionEvents: SharedFlow<SessionEvent>
        get() = flow<SessionEvent> {

        }.shareIn(CoroutineScope(Dispatchers.Default), started = SharingStarted.Lazily)
}

class FakeSession(
    override val id: SessionId,
    val sessionEvents: MutableList<SessionEvent> = mutableListOf()
) : Session {
    override fun run(): Flow<SessionEvent> = sessionEvents.asFlow()

    override suspend fun close() {
        TODO("Not yet implemented")
    }
}

class SessionBuilder {
    val sessionEvents = mutableListOf<SessionEvent>()

    fun transaction(block: TransactionBuilder.() -> Unit) {
        val builder = TransactionBuilder()
        builder.block()
        //sessionEvents.add(SessionEvent.TransactionStarted(builder.build()))
    }

    fun fail(throwable: Throwable? = null) {

    }
}

class FakeTransaction : Transaction {

    override val events: StateFlow<TransactionEvent>
        get() = TODO("Not yet implemented")
    override val state: TransactionState
        get() = TODO("Not yet implemented")
    override val request: MutableHttpRequest
        get() = TODO("Not yet implemented")
    override val response: MutableHttpResponse
        get() = TODO("Not yet implemented")

    override suspend fun close() {
        TODO("Not yet implemented")
    }

}

class TransactionBuilder {
    private val state = TransactionState.STARTED
    private val request = MutableHttpRequest()
    private val response = MutableHttpResponse()

    private fun buildData(): TransactionData {
        return TransactionData(
            state = state,
            request = request.toImmutable(),
            response = response.toImmutable())
    }

    var events = mutableListOf<TransactionEvent>(TransactionEvent.Started(buildData()))

    fun tlsSession(host: String) {
        request.statusLine = "CONNECT $host"
        request.headers["Host"] = host
        events.add(TransactionEvent.TlsSession(buildData()))
    }

    fun fail(throwable: Throwable? = null) {
        events.add(TransactionEvent.Error(buildData(), throwable ?: Exception("BOOM")))
    }

    fun build(): Transaction = TODO()
}
