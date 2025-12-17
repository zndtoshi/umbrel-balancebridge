package com.nomadwallet.balancebridge.mvp.ui

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nomadwallet.balancebridge.BitcoinLookupResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Extension function for UiState status messages
fun UiState.getStatusMessage(): String {
    return when (this) {
        is UiState.Idle -> "Ready"
        is UiState.Loading -> "Requesting data from node…"
        is UiState.Success -> "Balance loaded"
        is UiState.Error -> "Error: ${this.message}"
    }
}

// Simplified UI state for ViewModel
sealed class UiState {
    object Idle : UiState()
    object Loading : UiState()
    data class Success(val result: BitcoinLookupResult) : UiState()
    data class Error(val message: String) : UiState()
}

// ViewModel for managing UI state
class BitcoinLookupViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun startLookup() {
        _uiState.value = UiState.Loading
        Log.d("BalanceBridge-Android", "DEBUG: UiState.Loading emitted")
    }

    fun onSuccess(result: BitcoinLookupResult) {
        _uiState.value = UiState.Success(result)
        Log.d("BalanceBridge-Android", "DEBUG: UiState.Success emitted with result: confirmed=${result.confirmedBalance}, transactions=${result.transactions.size}")
    }

    fun onError(message: String) {
        _uiState.value = UiState.Error(message)
        Log.d("BalanceBridge-Android", "DEBUG: UiState.Error emitted: $message")
    }

    fun resetToIdle() {
        _uiState.value = UiState.Idle
    }
}

@Composable
fun BitcoinLookupScreen(
    modifier: Modifier = Modifier,
    viewModel: BitcoinLookupViewModel,
    onRequestClick: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Bitcoin Lookup",
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        // Status TextView
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    text = "Status:",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = uiState.getStatusMessage(),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        
        OutlinedTextField(
            value = inputText,
            onValueChange = {
                inputText = it
                errorMessage = null // Clear local error when user types
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            label = { Text("Bitcoin Address or Extended Public Key") },
            placeholder = { Text("Enter bc1/1/3 address or xpub/ypub/zpub/tpub") },
            enabled = uiState !is UiState.Loading,
            minLines = 5,
            maxLines = 10,
            isError = errorMessage != null || uiState is UiState.Error,
            supportingText = {
                when (val state = uiState) {
                    is UiState.Error -> Text(state.message)
                    else -> {
                        if (errorMessage != null) Text(errorMessage!!)
                    }
                }
            }
        )
        
        if (uiState is UiState.Loading) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        }

        // Display balance data when successfully loaded
        when (val state = uiState) {
            is UiState.Success -> {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "Balance Results:",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        Text("Confirmed: ${state.result.confirmedBalance} sats")
                        Text("Unconfirmed: ${state.result.unconfirmedBalance} sats")
                        Text("Transactions: ${state.result.transactions.size}")

                        if (state.result.transactions.isNotEmpty()) {
                            Text(
                                text = "Recent TXIDs:",
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                            state.result.transactions.forEach { tx ->
                                Text(
                                    text = tx.txid,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
            else -> {}
        }

        Button(
            onClick = {
                val trimmed = inputText.text.trim()
                Log.d("BalanceBridge-Android", "Request Data from Node button pressed")
                Log.d("BalanceBridge-Android", "Input text: $trimmed")
                if (trimmed.isEmpty()) {
                    Log.d("BalanceBridge-Android", "Input is empty, showing error")
                    errorMessage = "Input cannot be empty"
                } else {
                    Log.d("BalanceBridge-Android", "Input validated, calling onRequestClick")
                    onRequestClick(trimmed)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState !is UiState.Loading
        ) {
            Text("Request Data from Node")
        }
    }
}

