package com.nomadwallet.balancebridge.mvp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nomadwallet.balancebridge.BitcoinLookupResult

@Composable
fun BitcoinResultsScreen(
    modifier: Modifier = Modifier,
    result: BitcoinLookupResult,
    rawJson: String,
    onBack: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        Text(
            text = "Confirmed Balance",
            style = MaterialTheme.typography.titleMedium
        )
        Text("${result.confirmedBalance} sats")

        Text(
            text = "Unconfirmed Balance",
            style = MaterialTheme.typography.titleMedium
        )
        Text("${result.unconfirmedBalance} sats")

        Text(
            text = "Transaction List (${result.transactions.size} total)",
            style = MaterialTheme.typography.titleMedium
        )

        if (result.transactions.isEmpty()) {
            Text(
                text = "No transactions found for this address",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            result.transactions.forEach { tx ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                ) {
                    Text(
                        text = "TxID: ${tx.txid.take(12)}…${tx.txid.takeLast(6)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Confirmations: ${tx.confirmations}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Amount: ${tx.amount} sats",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Raw Response",
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = rawJson,
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onBack) {
            Text("Back")
        }
    }
}

