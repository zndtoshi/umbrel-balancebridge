package com.nomadwallet.balancebridge

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

object PairingStore {
    private var pairingData: PairingData? = null
    private var context: Context? = null
    private const val PREFS_NAME = "pairing_prefs"
    private const val KEY_VERSION = "version"
    private const val KEY_APP = "app"
    private const val KEY_NODE_PUBKEY = "node_pubkey"
    private const val KEY_RELAYS = "relays"

    fun initialize(context: Context) {
        this.context = context.applicationContext
        loadFromPreferences()
    }

    fun hasPairing(): Boolean {
        return pairingData != null
    }

    fun get(): PairingData? {
        return pairingData
    }

    fun set(data: PairingData) {
        pairingData = data
        saveToPreferences(data)
    }

    fun clear() {
        pairingData = null
        clearPreferences()
    }

    private fun loadFromPreferences() {
        val context = this.context ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        val nodePubkey = prefs.getString(KEY_NODE_PUBKEY, null)
        if (nodePubkey == null) {
            return
        }
        
        val version = prefs.getInt(KEY_VERSION, 1)
        val app = prefs.getString(KEY_APP, "umbrel-balancebridge") ?: "umbrel-balancebridge"
        val relaysJson = prefs.getString(KEY_RELAYS, "[]") ?: "[]"
        
        try {
            val relaysArray = JSONArray(relaysJson)
            val relays = mutableListOf<String>()
            for (i in 0 until relaysArray.length()) {
                relays.add(relaysArray.getString(i))
            }
            
            if (relays.isNotEmpty()) {
                pairingData = PairingData(version, app, nodePubkey, relays)
            }
        } catch (e: Exception) {
            // Invalid stored data, clear it
            clearPreferences()
        }
    }

    private fun saveToPreferences(data: PairingData) {
        val context = this.context ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        prefs.edit().apply {
            putInt(KEY_VERSION, data.version)
            putString(KEY_APP, data.app)
            putString(KEY_NODE_PUBKEY, data.nodePubkey)
            val relaysArray = JSONArray(data.relays)
            putString(KEY_RELAYS, relaysArray.toString())
            apply()
        }
    }

    private fun clearPreferences() {
        val context = this.context ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }
}

