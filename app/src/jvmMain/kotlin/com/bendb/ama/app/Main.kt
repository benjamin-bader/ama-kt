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

@file:OptIn(DelicateCoroutinesApi::class, ExperimentalSplitPaneApi::class)

package com.bendb.ama.app

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.ProvideTextStyle
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.AwtWindow
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bendb.ama.app.db.Db
import com.bendb.ama.app.main.MainViewModel
import com.bendb.ama.app.main.MainViewState
import com.bendb.ama.app.main.TransactionViewModel
import com.bendb.ama.app.main.TxModelState
import com.bendb.ama.proxy.ProxyServer
import com.bendb.ama.proxy.TransactionData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import org.jetbrains.compose.splitpane.ExperimentalSplitPaneApi
import org.jetbrains.compose.splitpane.HorizontalSplitPane
import org.jetbrains.compose.splitpane.rememberSplitPaneState
import java.awt.Cursor
import java.awt.FileDialog
import java.awt.Frame
import javax.swing.JFileChooser

suspend fun main() {
    val configStore = getConfigurationStorage()
    val config = configStore.get() ?: Configuration()
    val server = ProxyServer(Dispatchers.IO, config.http.port)
    val vm = MainViewModel(server)

    application {
        val windowState = rememberWindowState()

        LaunchedEffect(key1 = this) {
            val dbDriver = JdbcSqliteDriver("jdbc:sqlite:")
            CoroutineScope(newSingleThreadContext("database")).launch {
                Db.Schema.migrate(dbDriver, 0, Db.Schema.version)
            }
        }

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = AppInfo.displayName,
            icon = painterResource("icons/logo.svg")
        ) {
            App(vm)
        }
    }
}

private fun Modifier.cursorForHorizontalResize(): Modifier =
    pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))

@OptIn(ExperimentalComposeUiApi::class)
@Composable
@Preview
fun FrameWindowScope.App(vm: MainViewModel) {
    val uiState by vm.state.collectAsState()
    val splitState = rememberSplitPaneState(0.5f)

    val shouldEnableState = uiState.hasOneCompletedTx

    MenuBar {
        Menu("File", mnemonic = 'F') {
            Item("Open", shortcut = KeyShortcut(Key.O, ctrl = true)) {
                // TODO: Open an open-file UI, pick a DB, tell VM to load the transactions therein
            }
            Item("Save", shortcut = KeyShortcut(Key.S, ctrl = true), enabled = shouldEnableState) {
                // TODO: Open a save-file UI and tell VM where to save the session
            }
        }
    }

    MaterialTheme {
        Column(Modifier.padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            TopAppBar {
                ServerStatusLabel(uiState) {
                    when (uiState.listening) {
                        true -> vm.stopProxy()
                        else -> vm.startProxy()
                    }
                }

            }

            HorizontalSplitPane(
                splitPaneState = splitState,
            ) {
                first(500.dp) {
                    TransactionTable(uiState) { tx, index ->
                        if (index != null) {
                            vm.selectTransaction(index)
                        } else {
                            vm.deselectTransaction()
                        }
                    }
                }

                second {
                    val selectedIx = uiState.selectedIndex
                    val tx = if (selectedIx != null) uiState.transactions[selectedIx] else null

                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        if (tx != null) {
                            TransactionDetails(tx)
                        }
                    }
                }

                splitter {
                    visiblePart {
                        Box(Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colors.secondary)
                        )
                    }

                    handle {
                        Box(
                            Modifier
                                .markAsHandle()
                                .cursorForHorizontalResize()
                                .background(SolidColor(MaterialTheme.colors.secondaryVariant), alpha = 0.5f)
                                .width(9.dp)
                                .fillMaxHeight(0.2f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ServerStatusLabel(state: MainViewState, onButtonClick: () -> Unit = {}) {
    val message = if (state.listening) {
        "Listening on port ${state.port}"
    } else {
        "Proxy stopped; press play to start."
    }

    val icon = if (state.listening) {
        "⏸"
    } else {
        "▶"
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onButtonClick) {
            Text(text = icon)
        }
        Text(text = message)
    }
}

@Composable
fun TransactionTable(
    state: MainViewState,
    onSelectionChanged: (TransactionViewModel?, Int?) -> Unit,
) {
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

            itemsIndexed(state.transactions) { ix, tx ->
                val sequenceId = "${ix + 1}"
                val isSelected = state.selectedIndex == ix
                TransactionRow(sequenceId, tx, isSelected) { onSelectionChanged(tx, ix) }
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
    sequenceId: String,
    vm: TransactionViewModel,
    isSelected: Boolean,
    onClick: (TransactionData?) -> Unit = { _ -> },
) {
    val state by vm.state.collectAsState()

    val statusCode: String
    val method: String
    val requestPath: String

    when (val state = state) {
        is TxModelState.Idle -> {
            statusCode = ""
            method = ""
            requestPath = ""
        }

        is TxModelState.Data -> {
            val tx = state.tx
            method = tx.request.method.value
            requestPath = tx.request.requestPath
            statusCode = when {
                tx.response.statusCode != 0 -> "${tx.response.statusCode}"
                tx.request.method.value == "CONNECT" -> "-"
                else -> ""
            }
        }

        is TxModelState.Failed -> {
            statusCode = "ERR"
            method = ""
            requestPath = state.error.message ?: "Proxy got itself in trouble"
        }
    }

    val backgroundColor = when {
        isSelected -> Color.LightGray
        state is TxModelState.Failed -> Color.Red
        else -> Color.Transparent
    }

    Row(Modifier.background(backgroundColor).clickable { onClick((state as? TxModelState.Data)?.tx) }) {
        TableCell(sequenceId, 0.05f)
        TableCell(statusCode, 0.1f)
        TableCell(method, 0.1f)
        TableCell(requestPath, 0.5f)
    }
}

@Composable
fun TransactionDetails(viewModel: TransactionViewModel) {
    val state by viewModel.state.collectAsState()

    when (val state = state) {
        is TxModelState.Idle -> {
            return
        }

        is TxModelState.Data -> {
            val tx = state.tx
            Text(text = "Request")
            Text(text = tx.request.toString())
            Text(text = "Response")
            Text(text = tx.response.toString())
        }

        is TxModelState.Failed -> {
            Text(text = "Error: ${state.error.message}")
        }
    }
}

@Composable
fun OpenFileDialog(
    prompt: String = "Choose a file",
    parent: Frame? = null,
    onCloseRequest: (result: String?) -> Unit
) = AwtWindow(
    create = {
             object : FileDialog(parent, prompt, LOAD) {
                 override fun setVisible(value: Boolean) {
                     super.setVisible(value)
                     if (value) {
                         onCloseRequest(file)
                     }
                 }
             }
    },
    dispose = FileDialog::dispose
)
