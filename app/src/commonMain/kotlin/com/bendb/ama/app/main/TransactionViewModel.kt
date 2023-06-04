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
import com.bendb.ama.proxy.Transaction
import com.bendb.ama.proxy.TransactionData
import com.bendb.ama.proxy.TransactionEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TransactionViewModel(
    private val transaction: Transaction
) : ViewModel<TxModelState, TxModelInput, TxModelResult, TxModelEffect>(TxModelState.Idle) {

    private var eventsJob: Job? = null

    init {
        eventsJob = viewModelScope.launch {
            transaction.events.collect {
                when (it) {
                    is TransactionEvent.Started -> { /* no-op */ }
                    is TransactionEvent.RequestLine -> dispatch(TxModelInput.Updated(it.tx))
                    is TransactionEvent.RequestHeaders -> dispatch(TxModelInput.Updated(it.tx))
                    is TransactionEvent.RequestBody -> dispatch(TxModelInput.Updated(it.tx))
                    is TransactionEvent.RequestComplete -> dispatch(TxModelInput.Updated(it.tx))
                    is TransactionEvent.ResponseLine -> dispatch(TxModelInput.Updated(it.tx))
                    is TransactionEvent.ResponseHeaders -> dispatch(TxModelInput.Updated(it.tx))
                    is TransactionEvent.ResponseBody -> dispatch(TxModelInput.Updated(it.tx))
                    is TransactionEvent.ResponseComplete -> dispatch(TxModelInput.Updated(it.tx))
                    is TransactionEvent.TlsSession -> dispatch(TxModelInput.Updated(it.tx))

                    is TransactionEvent.Completed -> {
                        dispatch(TxModelInput.Updated(it.tx))

                        // Terminal state.
                        cancel(null)
                    }

                    is TransactionEvent.Error -> {
                        dispatch(TxModelInput.Failed(it.e))

                        // Terminal state.
                        // The coroutine is finishing unexceptionally, even though
                        // the transaction itself failed.  We just observed it.m
                        cancel(null)
                    }
                }
            }
        }
    }

    override suspend fun inputToResult(input: TxModelInput): TxModelResult {
        return when (input) {
            is TxModelInput.Selected ->  {
                // launch an effect
                TxModelResult.NoChange
            }

            is TxModelInput.Updated -> {
                TxModelResult.Loaded(input.data)
            }

            is TxModelInput.Failed -> {
                TxModelResult.Failed(input.error)
            }
        }
    }

    override suspend fun resultToState(
        currentState: TxModelState,
        result: TxModelResult
    ): TxModelState {
        return when (result) {
            is TxModelResult.NoChange -> currentState
            is TxModelResult.Loaded -> TxModelState.Data(result.data)
            is TxModelResult.Failed -> TxModelState.Failed(result.error)
        }
    }
}

sealed interface TxModelState {
    object Idle : TxModelState
    class Data(val tx: TransactionData) : TxModelState
    class Failed(val error: Throwable) : TxModelState
}

sealed interface TxModelInput {
    object Selected : TxModelInput
    class Updated(val data: TransactionData) : TxModelInput
    class Failed(val error: Throwable) : TxModelInput
}

sealed interface TxModelResult {
    object NoChange : TxModelResult
    class Loaded(val data: TransactionData) : TxModelResult
    class Failed(val error: Throwable) : TxModelResult
}

sealed interface TxModelEffect
