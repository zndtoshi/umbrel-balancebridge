package com.nomadwallet.balancebridge

data class PairingData(
    val version: Int = 1,
    val app: String = "umbrel-balancebridge",
    val nodePubkey: String,
    val relays: List<String>
)

