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

package com.bendb.ama.app.main

import com.bendb.ama.app.ViewModel
import com.bendb.ama.proxy.ProxyServer
import com.bendb.ama.proxy.SessionEvent
import com.bendb.ama.proxy.Transaction
import com.bendb.ama.proxy.isTerminal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch

class MainViewModel(
    private val proxy: ProxyServer,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel<MainViewState, MainViewInput, MainViewResult, Nothing>(MainViewState(port = proxy.port), dispatcher) {

    init {
        viewModelScope.launch {
            proxy.proxyStateFlow.collect {
                when (it) {
                    ProxyServer.ProxyState.STOPPED -> dispatch(MainViewInput.ProxyStopped)
                    ProxyServer.ProxyState.LISTENING -> dispatch(MainViewInput.ProxyStarted)
                }
            }
        }

        viewModelScope.launch {
            proxy.sessionEvents
                .filterIsInstance<SessionEvent.TransactionStarted>()
                .collect {
                    dispatch(MainViewInput.TransactionStarted(it.transaction))
                }
        }
    }

    // PUBLIC API

    fun startProxy() {
        accept(MainViewInput.StartProxy)
    }

    fun stopProxy() {
        accept(MainViewInput.StopProxy)
    }

    fun clearTransactions() {
        accept(MainViewInput.ClearTransactions)
    }

    fun selectTransaction(index: Int) {
        accept(MainViewInput.SelectTransaction(index))
    }

    fun deselectTransaction() {
        accept(MainViewInput.SelectTransaction(null))
    }

    // INTERNALS

    override suspend fun inputToResult(input: MainViewInput): MainViewResult {
        return when (input) {
            is MainViewInput.StartProxy -> {
                if (!state.value.listening) {
                    proxy.listen()
                }
                MainViewResult.ProxyStarting
            }

            is MainViewInput.ProxyStarted -> {
                MainViewResult.ProxyStarted
            }

            is MainViewInput.StopProxy -> {
                if (state.value.listening) {
                    proxy.close()
                }
                MainViewResult.ProxyStopped
            }

            is MainViewInput.ProxyStopped -> {
                MainViewResult.ProxyStopped
            }

            is MainViewInput.TransactionStarted -> {
                MainViewResult.NewTransaction(input.tx)
            }

            is MainViewInput.TransactionEnded -> {
                MainViewResult.TransactionEnded(input.tx)
            }

            is MainViewInput.SelectTransaction -> {
                if (state.value.listening) {
                    MainViewResult.TransactionSelected(input.index)
                } else {
                    MainViewResult.NoChange
                }
            }

            is MainViewInput.ClearTransactions -> {
                MainViewResult.ClearTransactions
            }
        }
    }

    override suspend fun resultToState(
        currentState: MainViewState,
        result: MainViewResult
    ): MainViewState {
        return when (result) {
            is MainViewResult.NoChange -> currentState
            is MainViewResult.ProxyStopped -> currentState.copy(listening = false)
            is MainViewResult.ProxyStarting -> currentState
            is MainViewResult.ProxyStarted -> currentState.copy(listening = true)
            is MainViewResult.NewTransaction -> {
                require(currentState.listening) {
                    "Cannot add a new transaction to a non-ready state (current state: $currentState)"
                }

                val tx = result.tx
                val vm = newTxViewModel(tx)

                // We _do_ want to know when the transaction ends, so let's
                // set that up here.
                viewModelScope.launch {
                    tx.events
                        .filter { it.tx.state.isTerminal }
                        .take(1)
                        .collect {
                            // We've got a terminal event
                            dispatch(MainViewInput.TransactionEnded(tx))
                        }
                }

                currentState.copy(
                    transactions = currentState.transactions + vm,
                )
            }

            is MainViewResult.TransactionEnded -> {
                currentState.copy(
                    hasOneCompletedTx = true,
                )
            }

            is MainViewResult.TransactionSelected -> {
                require(currentState.listening) {
                    "Cannot select a transaction in a non-ready state (current state: $currentState)"
                }

                currentState.copy(selectedIndex = result.index)
            }

            is MainViewResult.ClearTransactions -> {
                currentState.copy(
                    transactions = emptyList(),
                    selectedIndex = null,
                    hasOneCompletedTx = false,
                )
            }
        }
    }

    private fun newTxViewModel(tx: Transaction): TransactionViewModel {
        val vm = TransactionViewModel(tx)
        val job = viewModelScope.coroutineContext[Job.Key]
        job?.invokeOnCompletion { vm.close() }

        return vm
    }
}

data class MainViewState(
    val listening: Boolean = false,
    val port: Int? = null,
    val transactions: List<TransactionViewModel> = emptyList(),
    val selectedIndex: Int? = null,
    val hasOneCompletedTx: Boolean = false,
)

sealed interface MainViewInput {
    data object StartProxy : MainViewInput
    data object ProxyStarted : MainViewInput
    data object StopProxy : MainViewInput
    data object ProxyStopped : MainViewInput
    class TransactionStarted(val tx: Transaction) : MainViewInput
    class TransactionEnded(val tx: Transaction) : MainViewInput
    class SelectTransaction(val index: Int?) : MainViewInput
    data object ClearTransactions : MainViewInput
}

sealed interface MainViewResult {
    data object NoChange : MainViewResult
    data object ProxyStopped : MainViewResult
    data object ProxyStarting : MainViewResult
    data object ProxyStarted : MainViewResult
    class NewTransaction(val tx: Transaction) : MainViewResult
    class TransactionEnded(val tx: Transaction) : MainViewResult
    class TransactionSelected(val index: Int?) : MainViewResult
    data object ClearTransactions : MainViewResult
}
