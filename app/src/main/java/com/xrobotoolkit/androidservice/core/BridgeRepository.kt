package com.xrobotoolkit.androidservice.core

import com.xrobotoolkit.androidservice.model.BridgeUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

object BridgeRepository {
    private val _state = MutableStateFlow(BridgeUiState())
    val state: StateFlow<BridgeUiState> = _state

    fun update(transform: (BridgeUiState) -> BridgeUiState) {
        _state.update(transform)
    }
}
