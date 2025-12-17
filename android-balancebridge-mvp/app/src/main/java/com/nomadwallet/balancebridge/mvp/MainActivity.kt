package com.nomadwallet.balancebridge.mvp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nomadwallet.balancebridge.BitcoinInputValidator
import com.nomadwallet.balancebridge.NostrManager
import com.nomadwallet.balancebridge.NostrResponseCallback
import com.nomadwallet.balancebridge.PairingData
import com.nomadwallet.balancebridge.BitcoinLookupResult
import com.nomadwallet.balancebridge.PairingStore
import com.nomadwallet.balancebridge.QrScanActivity
import com.nomadwallet.balancebridge.mvp.ui.BitcoinLookupScreen
import com.nomadwallet.balancebridge.mvp.ui.BitcoinLookupViewModel
import com.nomadwallet.balancebridge.mvp.ui.BitcoinResultsScreen
import com.nomadwallet.balancebridge.mvp.ui.UiState
import com.nomadwallet.balancebridge.mvp.ui.theme.BalanceBridgeMVPTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val pairingStore = PairingStore
    private val nostrManager: NostrManager by lazy { NostrManager(applicationContext) }
    
    private val qrScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val qrContent = result.data?.getStringExtra(QrScanActivity.EXTRA_QR_CONTENT)
            if (qrContent != null) {
                handleQrScanResult(qrContent)
            } else {
                showError("No QR content received")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize PairingStore
        pairingStore.initialize(applicationContext)
        
        enableEdgeToEdge()
        
        setContent {
            BalanceBridgeMVPTheme {
                AppContent(
                    pairingStore = pairingStore,
                    nostrManager = nostrManager,
                    lifecycleScope = lifecycleScope,
                    onScanQrClick = { launchQrScan() },
                    showError = { message -> showError(message) }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        nostrManager.stop()
    }

    private fun launchQrScan() {
        val intent = Intent(this, QrScanActivity::class.java)
        qrScanLauncher.launch(intent)
    }

    private fun handleQrScanResult(qrContent: String) {
        val pairing = PairingData.fromJson(qrContent)
        if (pairing != null) {
            pairingStore.set(pairing)
            Toast.makeText(this, "Pairing successful!", Toast.LENGTH_SHORT).show()
            // Start Nostr connection with server pubkey
            nostrManager.start(pairing.nodePubkey)
            // Restart activity to show main screen
            recreate()
        } else {
            showError("Invalid QR code format. Please scan a valid pairing QR code.")
        }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}

sealed class AppScreen {
    object Main : AppScreen()
    object BitcoinLookup : AppScreen()
    data class BitcoinResults(
        val result: BitcoinLookupResult,
        val rawJson: String
    ) : AppScreen()
}


@Composable
fun AppContent(
    pairingStore: PairingStore,
    nostrManager: NostrManager,
    lifecycleScope: kotlinx.coroutines.CoroutineScope,
    onScanQrClick: () -> Unit,
    showError: (String) -> Unit
) {
    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Main) }
    val viewModel = remember { BitcoinLookupViewModel() }
    val coroutineScope = rememberCoroutineScope()
    
    // Initialize Nostr connection if pairing exists
    LaunchedEffect(Unit) {
        val pairing = pairingStore.get()
        if (pairing != null) {
            nostrManager.start(pairing.nodePubkey)
        }
    }
    
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        when (val screen = currentScreen) {
            is AppScreen.Main -> {
                if (pairingStore.hasPairing()) {
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        onScanQrClick = onScanQrClick,
                        onBitcoinLookupClick = {
                            Log.d("BalanceBridge-Android", "Navigating to Bitcoin Lookup screen")
                            currentScreen = AppScreen.BitcoinLookup
                            viewModel.resetToIdle()
                        }
                    )
                } else {
                    PairingPromptScreen(
                        modifier = Modifier.padding(innerPadding),
                        onScanQrClick = onScanQrClick
                    )
                }
            }
            
            is AppScreen.BitcoinLookup -> {
                // Note: Nostr requests timeout after 30 seconds
                
                BitcoinLookupScreen(
                    modifier = Modifier.padding(innerPadding),
                    viewModel = viewModel,
                    onRequestClick = { query ->
                        Log.d("BalanceBridge-Android", "onRequestClick called with query: $query")
                        
                        // Validate input
                        val validation = BitcoinInputValidator.validate(query)
                        if (!validation.isValid) {
                            Log.d("BalanceBridge-Android", "Input validation failed: ${validation.errorMessage}")
                            viewModel.onError(validation.errorMessage ?: "Invalid input")
                            return@BitcoinLookupScreen
                        }
                        
                        Log.d("BalanceBridge-Android", "Input validation passed")
                        
                        // Build request JSON for logging
                        val requestJson = JSONObject().apply {
                            put("type", "bitcoin_lookup")
                            put("query", query)
                        }
                        val requestJsonString = requestJson.toString()
                        Log.d("BalanceBridge-Android", "Request JSON: $requestJsonString")
                        
                        // Set loading state
                        viewModel.startLookup()
                        
                        // Get server pubkey from pairing
                        val pairing = pairingStore.get()
                        if (pairing == null) {
                            Log.e("BalanceBridge", "No pairing found, cannot send request")
                            viewModel.onError("No pairing found. Please pair with server first.")
                            return@BitcoinLookupScreen
                        }
                        
                        // Use coroutine scope to launch Nostr request
                        coroutineScope.launch {
                            Log.d("BalanceBridge", "Sending Nostr request")
                            Log.d("BalanceBridge", "Request body: $requestJsonString")
                            Log.d("BalanceBridge", "Server pubkey: ${pairing.nodePubkey}")
                            
                            // Ensure Nostr is started
                            if (!nostrManager.isStarted()) {
                                nostrManager.start(pairing.nodePubkey)
                                delay(1000) // Give time for connection
                            }
                            
                            // Publish BalanceBridge request via Nostr
                            nostrManager.publishBalanceBridgeRequest(
                                query = query,
                                serverPubkeyHex = pairing.nodePubkey
                            ) { result ->
                                // Handle the Result<BitcoinLookupResult> from NostrManager
                                result.fold(
                                    onSuccess = { lookupResult ->
                                        viewModel.onSuccess(lookupResult)

                                        // Create raw JSON for display (this would normally come from the server response)
                                        val rawJson = JSONObject().apply {
                                            put("type", "bitcoin_lookup_response")
                                            put("status", "ok")
                                            put("result", JSONObject().apply {
                                                put("confirmed_balance", lookupResult.confirmedBalance)
                                                put("unconfirmed_balance", lookupResult.unconfirmedBalance)
                                                put("transactions", JSONArray(lookupResult.transactions.map { it.txid }))
                                            })
                                        }.toString()

                                        currentScreen = AppScreen.BitcoinResults(
                                            result = lookupResult,
                                            rawJson = rawJson
                                        )
                                    },
                                    onFailure = { exception ->
                                        val errorMsg = exception.message ?: "Unknown error"
                                        viewModel.onError(errorMsg)
                                    }
                                )
                            }
                        }
                    }
                )
            }

            is AppScreen.BitcoinResults -> {
                BitcoinResultsScreen(
                    modifier = Modifier.padding(innerPadding),
                    result = screen.result,
                    rawJson = screen.rawJson,
                    onBack = {
                        Log.d("BalanceBridge-Android", "Navigating back to Main screen")
                        currentScreen = AppScreen.Main
                        viewModel.resetToIdle()
                    }
                )
            }
        }
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onScanQrClick: () -> Unit,
    onBitcoinLookupClick: () -> Unit
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Greeting(name = "BalanceBridge")
        Text(
            text = "Paired and connected",
            modifier = Modifier.padding(top = 8.dp)
        )
        Button(
            onClick = onBitcoinLookupClick,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text("Bitcoin Lookup")
        }
        Button(
            onClick = onScanQrClick,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("Re-pair")
        }
    }
}

@Composable
fun PairingPromptScreen(
    modifier: Modifier = Modifier,
    onScanQrClick: () -> Unit
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Please scan QR code to pair with Umbrel node")
        Button(
            onClick = onScanQrClick,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text("Scan QR")
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    BalanceBridgeMVPTheme {
        Greeting("Android")
    }
}