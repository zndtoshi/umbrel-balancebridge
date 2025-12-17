package com.nomadwallet.balancebridge

object NostrRelayConfig {
    // Public Nostr relays for BalanceBridge (same as server)
    val PUBLIC_RELAYS = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.primal.net"
    )

    // Event kinds for BalanceBridge (matches server)
    const val BALANCEBRIDGE_KIND: Int = 30078
}

