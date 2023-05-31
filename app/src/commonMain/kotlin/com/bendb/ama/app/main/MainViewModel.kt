package com.bendb.ama.app.main

import com.bendb.ama.app.ViewModel
import com.bendb.ama.proxy.ProxyServer
import com.bendb.ama.proxy.SessionEvent
import com.bendb.ama.proxy.Transaction
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

class MainViewModel(
    private val proxy: ProxyServer
) : ViewModel<MainViewState, MainViewInput, MainViewResult, Nothing>(MainViewState.Stopped) {

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

    override suspend fun inputToResult(input: MainViewInput): MainViewResult {
        return when (input) {
            is MainViewInput.StartProxy -> {
                if (state.value is MainViewState.Stopped) {
                    proxy.listen()
                }
                MainViewResult.ProxyStarting
            }

            is MainViewInput.ProxyStarted -> {
                MainViewResult.ProxyStarted
            }

            is MainViewInput.StopProxy -> {
                if (state.value !is MainViewState.Stopped) {
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
        }
    }

    override suspend fun resultToState(
        currentState: MainViewState,
        result: MainViewResult
    ): MainViewState {
        return when (result) {
            is MainViewResult.NoChange -> currentState
            is MainViewResult.ProxyStopped -> MainViewState.Stopped
            is MainViewResult.ProxyStarting -> MainViewState.Starting
            is MainViewResult.ProxyStarted -> MainViewState.Ready(proxy.port, emptyList())
            is MainViewResult.NewTransaction -> {
                require (currentState is MainViewState.Ready) {
                    "Cannot add a new transaction to a non-ready state (current state: $currentState)"
                }

                currentState.copy(
                    transactions = currentState.transactions + newTxViewModel(result.tx),
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

sealed interface MainViewState {
    object Stopped : MainViewState
    object Starting : MainViewState
    data class Ready(
        val port: Int,
        val transactions: List<TransactionViewModel>
    ) : MainViewState
}

sealed interface MainViewInput {
    object StartProxy : MainViewInput
    object ProxyStarted : MainViewInput
    object StopProxy : MainViewInput
    object ProxyStopped : MainViewInput
    class TransactionStarted(val tx: Transaction) : MainViewInput
}

sealed interface MainViewResult {
    object NoChange : MainViewResult
    object ProxyStopped : MainViewResult
    object ProxyStarting : MainViewResult
    object ProxyStarted : MainViewResult
    class NewTransaction(val tx: Transaction) : MainViewResult
}
