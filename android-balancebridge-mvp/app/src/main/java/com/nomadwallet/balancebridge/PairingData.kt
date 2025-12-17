package com.nomadwallet.balancebridge

import org.json.JSONArray
import org.json.JSONObject

data class PairingData(
    val version: Int = 1,
    val app: String = "umbrel-balancebridge",
    val nodePubkey: String,
    val relays: List<String>,
    val nodeUrl: String? = null
) {
    companion object {
        fun fromJson(jsonString: String): PairingData? {
            return try {
                val json = JSONObject(jsonString)
                
                // Validate required fields
                if (!json.has("nodePubkey") || !json.has("relays")) {
                    return null
                }
                
                val version = if (json.has("version")) json.getInt("version") else 1
                val app = if (json.has("app")) json.getString("app") else "umbrel-balancebridge"
                val nodePubkey = json.getString("nodePubkey")
                
                // Validate nodePubkey is not empty
                if (nodePubkey.isBlank()) {
                    return null
                }
                
                val relaysArray = json.getJSONArray("relays")
                
                // Validate relays array is not empty
                if (relaysArray.length() == 0) {
                    return null
                }
                
                val relays = mutableListOf<String>()
                for (i in 0 until relaysArray.length()) {
                    val relay = relaysArray.getString(i)
                    // Validate relay URL is not empty
                    if (relay.isNotBlank()) {
                        relays.add(relay)
                    }
                }
                
                // Ensure we have at least one valid relay
                if (relays.isEmpty()) {
                    return null
                }
                
                // Extract nodeUrl from JSON if present, otherwise use first relay as fallback
                val nodeUrl = if (json.has("nodeUrl")) {
                    json.getString("nodeUrl")
                } else {
                    // Try to extract HTTP URL from first relay if it's an HTTP endpoint
                    relays.firstOrNull()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                }
                
                PairingData(version, app, nodePubkey, relays, nodeUrl)
            } catch (e: Exception) {
                null
            }
        }
    }
}

