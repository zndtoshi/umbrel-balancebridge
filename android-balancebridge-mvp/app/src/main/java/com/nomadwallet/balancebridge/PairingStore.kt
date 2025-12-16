package com.nomadwallet.balancebridge

object PairingStore {
    private var pairingData: PairingData? = null

    fun hasPairing(): Boolean {
        return pairingData != null
    }

    fun get(): PairingData? {
        return pairingData
    }

    fun set(data: PairingData) {
        pairingData = data
    }

    fun clear() {
        pairingData = null
    }
}

