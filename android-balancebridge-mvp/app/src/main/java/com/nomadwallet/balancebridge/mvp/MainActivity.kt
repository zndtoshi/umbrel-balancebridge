package com.nomadwallet.balancebridge.mvp

import android.content.Intent
import android.os.Bundle
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nomadwallet.balancebridge.NostrManager
import com.nomadwallet.balancebridge.PairingData
import com.nomadwallet.balancebridge.PairingStore
import com.nomadwallet.balancebridge.QrScanActivity
import com.nomadwallet.balancebridge.mvp.ui.theme.BalanceBridgeMVPTheme

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
        
        // Start Nostr connection if paired
        if (pairingStore.hasPairing()) {
            nostrManager.start()
        }
        
        enableEdgeToEdge()
        
        setContent {
            BalanceBridgeMVPTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (pairingStore.hasPairing()) {
                        MainScreen(
                            modifier = Modifier.padding(innerPadding),
                            onScanQrClick = {
                                launchQrScan()
                            }
                        )
                    } else {
                        PairingPromptScreen(
                            modifier = Modifier.padding(innerPadding),
                            onScanQrClick = {
                                launchQrScan()
                            }
                        )
                    }
                }
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
            // Start Nostr connection
            nostrManager.start()
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

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onScanQrClick: () -> Unit
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
            onClick = onScanQrClick,
            modifier = Modifier.padding(top = 16.dp)
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