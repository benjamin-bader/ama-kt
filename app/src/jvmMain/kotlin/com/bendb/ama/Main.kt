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

@file:OptIn(DelicateCoroutinesApi::class)

package com.bendb.ama

import androidx.compose.material.MaterialTheme
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.ProvideTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bendb.ama.db.Db
import com.bendb.ama.proxy.ProxyServer
import com.bendb.ama.proxy.SessionEvent
import com.bendb.ama.proxy.Transaction
import com.bendb.ama.proxy.TransactionData
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import org.koin.core.context.startKoin

private val koin = startKoin {
    // lol will we actually need this?  guessing "no" at this point.
}.koin

private val server = ProxyServer(Dispatchers.IO,9977)

suspend fun main() = application {
    val windowState = rememberWindowState()

    LaunchedEffect(key1 = this) {
        val dbDriver = JdbcSqliteDriver("jdbc:sqlite:")
        CoroutineScope(newSingleThreadContext("database")).launch {
            Db.Schema.migrate(dbDriver, 0, Db.Schema.version)
        }

        launch(Dispatchers.IO) {
            server.listen()
        }
    }

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "Ama",
    ) {
        App()
    }
}

@Composable
@Preview
fun App() {
    val sessions by server.sessionEvents
        .filterIsInstance<SessionEvent.TransactionStarted>()
        .scan(listOf<Transaction>()) { txs, event -> txs + event.transaction }
        .collectAsState(listOf())

    val serverStatus by server.proxyStateEvents.collectAsState()

    MaterialTheme {
        Column(Modifier.padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (serverStatus) {
                ProxyServer.ProxyState.STOPPED -> {
                    Text(text = "Starting up...")
                }
                ProxyServer.ProxyState.LISTENING -> {
                    Text(text = "Listening on port ${server.port}")
                }
            }

            TransactionTable(sessions)
        }
    }
}

sealed interface TxState {
    object Loading : TxState
    data class Ready(val tx: TransactionData) : TxState
}

@Composable
fun TransactionTable(transactions: List<Transaction>) {
    val sequenceWeight = 0.05f
    val statusWeight = 0.1f
    val methodWeight = 0.1f
    val urlWeight = 0.5f

    ProvideTextStyle(MaterialTheme.typography.body2) {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp), userScrollEnabled = true) {
            item {
                Row(Modifier.background(color = Color.Gray)) {
                    TableCell("#", sequenceWeight)
                    TableCell("Status", statusWeight)
                    TableCell("Method", methodWeight)
                    TableCell("Path", urlWeight)
                }
            }

            itemsIndexed(transactions) { ix, tx ->
                val sequenceId = "${ix + 1}"
                TransactionRow(sequenceId, tx) { txData ->
                    println("Clicked on $txData")}
            }
        }
    }
}

@Composable
fun RowScope.TableCell(text: String, weight: Float) {
    Column(Modifier.weight(weight, true).border(1.dp, Color.Black)) {
        SelectionContainer {
            Text(text, Modifier.padding(8.dp))
        }
    }
}

@Composable
fun TransactionRow(
    sequenceId: String, initialTx: Transaction,
    onClick: (TransactionData?) -> Unit = { _ -> },
) {
    val txLoadingState by initialTx.events
        .map { TxState.Ready(it.tx) }
        .collectAsState(TxState.Loading)

    var statusCode = ""
    var method = ""
    var requestPath = ""
    (txLoadingState as? TxState.Ready)?.let { readyState ->
        val tx = readyState.tx
        if (tx.response.statusCode != 0) {
            statusCode = tx.response.statusCode.toString()
        } else if (tx.request.method.value == "CONNECT") {
            statusCode = "-"
        } else {
            statusCode = ""
        }

        method = tx.request.method.value
        requestPath = tx.request.requestPath
    }

    Row(Modifier.clickable { onClick((txLoadingState as? TxState.Ready)?.tx) }) {
        TableCell(sequenceId, 0.05f)
        TableCell(statusCode, 0.1f)
        TableCell(method, 0.1f)
        TableCell(requestPath, 0.5f)
    }
}
