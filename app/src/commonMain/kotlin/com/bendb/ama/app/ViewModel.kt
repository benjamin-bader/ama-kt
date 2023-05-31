package com.bendb.ama.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okio.Closeable

/**
 * A [ViewModel] is a state machine that takes in events and produces a stream of states.
 * (Copilot wrote that)
 *
 * This is out spin on a ViewModel using a unidirectional state flow.  It produces UI
 * states of type [S] in response to events of type [I].  It can also produce side effects
 * of type [E] in response to events.
 */
abstract class ViewModel<S, I, R, E>(
    initialState: () -> S
) : Closeable {
    protected val viewModelScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val mutableState = MutableStateFlow(initialState())
    private val mutableInputs = MutableSharedFlow<I>()
    private val mutableEffects = MutableSharedFlow<E>()

    val state: StateFlow<S> = mutableState.asStateFlow()
    val effects: SharedFlow<E> = mutableEffects.asSharedFlow()

    init {
        viewModelScope.launch {
            mutableInputs.collect { input ->
                val result = inputToResult(input)
                val newState = resultToState(mutableState.value, result)
                mutableState.emit(newState)

                val effect = resultToEffect(result)
                if (effect != null) {
                    mutableEffects.emit(effect)
                }
            }
        }
    }

    constructor(initialState: S) : this({ initialState })

    open fun accept(input: I) {
        viewModelScope.launch {
            dispatch(input)
        }
    }

    suspend fun dispatch(input: I) {
        mutableInputs.emit(input)
    }

    protected abstract suspend fun inputToResult(input: I): R

    protected abstract suspend fun resultToState(currentState: S, result: R): S

    protected open fun resultToEffect(result: R): E? = null

    override fun close() {
        viewModelScope.cancel(cause = null)
    }
}

typealias ViewModelNoEffect<S, I, R> = ViewModel<S, I, R, Nothing>
