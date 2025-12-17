package com.nomadwallet.balancebridge

import android.content.Context
import android.util.Log
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rust.nostr.sdk.Client
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Keys
import rust.nostr.sdk.NostrSigner
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.SecretKey
import rust.nostr.sdk.Timestamp

// Data class for Bitcoin transaction
data class BitcoinTransaction(
    val txid: String,
    val confirmations: Int = 0,
    val timestamp: Long = 0,
    val amount: Long = 0
)

// Data class for balance lookup results
data class BitcoinLookupResult(
    val confirmedBalance: Long,
    val unconfirmedBalance: Long,
    val transactions: List<BitcoinTransaction>
)

interface NostrResponseCallback {
    fun onResponse(jsonResponse: String)
    fun onError(error: String)
}

data class PendingRequest(
    val requestId: String,
    val callback: NostrResponseCallback,
    val serverPubkey: PublicKey
)

class NostrManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    
    private val keys: Keys = loadOrCreateKeys()
    private val signer: NostrSigner = NostrSigner.keys(keys)
    private var client: Client? = null
    private val pendingRequests = mutableMapOf<String, PendingRequest>()
    private var serverPubkey: PublicKey? = null

    fun start(serverPubkeyHex: String) {
        scope.launch {
            runCatching {
                val serverPub = PublicKey.parse(serverPubkeyHex)
                this@NostrManager.serverPubkey = serverPub
                
                // Connect if not already connected
                if (!started.get() || client == null) {
                    started.set(true)
                    connectToPublicRelays()
                } else {
                    logInfo("Nostr client already connected")
                }
            }.onFailure { error ->
                logError("Failed to initialize Nostr", error)
                started.set(false)
            }
        }
    }

    fun isStarted(): Boolean {
        return started.get() && client != null
    }
    
    fun stop() {
        scope.launch {
            runCatching {
                client?.shutdown()
            }.onFailure { error ->
                logError("Error while shutting down Nostr client", error)
            }
        }
        scope.cancel()
        started.set(false)
    }

    private suspend fun connectToPublicRelays() {
        val clientInstance = Client(signer)
        this.client = clientInstance

        // Connect to public relays
        var connectedCount = 0
        for (relayUrl in NostrRelayConfig.PUBLIC_RELAYS) {
            try {
                val relay = RelayUrl.parse(relayUrl)
                clientInstance.addRelay(relay)
                connectedCount++
                logInfo("Added public relay: $relayUrl")
            } catch (e: Exception) {
                logError("Failed to add relay: $relayUrl", e)
            }
        }

        if (connectedCount == 0) {
            throw IllegalStateException("No relays connected")
        }

        clientInstance.connect()
        Log.d(TAG, "NOSTR: connected to relays")
        logInfo("Connected to $connectedCount public relay(s) as ${keys.publicKey().toHex()}")

        // Start listening for responses (in background coroutine)
        scope.launch { listenForResponses() }
        logInfo("Listening for BalanceBridge responses")
    }

    private suspend fun listenForResponses() {
        try {
            Log.d(TAG, "Starting to listen for BalanceBridge response events")

            val server = serverPubkey
            if (server == null) {
                Log.e(TAG, "Server pubkey not set yet; cannot listen for responses")
                return
            }

            // Subscribe to BalanceBridge response events
            val clientPubkeyHex = keys.publicKey().toHex()
            val responseFilter = Filter()
                .kinds(listOf(Kind(30079.toUShort())))              // BalanceBridge RESPONSE
                .since(Timestamp.now())                             // fresh events only

            client?.subscribe(responseFilter)

            Log.d(
                "BalanceBridge-Android",
                "=== SUBSCRIBED TO RESPONSES === clientPubkey=$clientPubkeyHex"
            )

            // DEBUG: Add catch-all subscription for ALL kind 30079 events (no tag filtering)
            val debugFilter = Filter()
                .kinds(listOf(Kind(30079.toUShort())))              // BalanceBridge RESPONSE - ALL
                .since(Timestamp.now())                             // fresh events only

            client?.subscribe(debugFilter)
            Log.d("BalanceBridge-Android", "=== DEBUG SUBSCRIPTION ACTIVE === catching ALL kind=30079 events")

            // Create DEBUG stream for ALL kind 30079 events (no filtering)
            scope.launch {
                val debugStream = client?.streamEvents(debugFilter, Duration.ofHours(1))
                if (debugStream != null) {
                    Log.d(TAG, "Debug event stream created for ALL kind=30079 events")
                    while (true) {
                        val event = debugStream.next() ?: break

                        // Log EVERY kind 30079 event received (debug only)
                        Log.d("BalanceBridge-Android",
                            "=== DEBUG 30079 EVENT RECEIVED === id=${event.id().toHex()} pubkey=${event.author().toHex()} tags=${event.tags()}"
                        )
                    }
                }
            }

            // Create stream to handle incoming events (main subscription)
            val stream = client?.streamEvents(responseFilter, Duration.ofHours(1))
            if (stream != null) {
                Log.d(TAG, "Response event stream created, waiting for events...")
                while (true) {
                    val event = stream.next() ?: break

                    // Log ALL received events for debugging
                    Log.d(
                        "BalanceBridge-Android",
                        "=== BALANCEBRIDGE RESPONSE RECEIVED === id=${event.id().toHex()}"
                    )

                    // 🔍 LOCAL VALIDATION: Match response to pending requests
                    if (event.kind().asU16() == 30079.toUShort()) {
                        // Find which pending request this response matches
                        val content = event.content()
                        var matchedReqId: String? = null

                        // Check all pending requests to see if any match this response
                        for ((reqId, _) in pendingRequests) {
                            if (content.contains(reqId) || content.contains("\"req\":\"$reqId\"")) {
                                matchedReqId = reqId
                                break
                            }
                        }

                        if (matchedReqId == null) {
                            Log.d("BalanceBridge-Android", "Ignoring response: unknown req_id")
                            continue
                        }

                        Log.d("BalanceBridge-Android", "=== BALANCEBRIDGE RESPONSE RECEIVED === req_id=$matchedReqId")

                        val pending = pendingRequests.remove(matchedReqId)
                        if (pending != null) {
                            // Parse response content (plain JSON, not encrypted)
                            try {
                                val responseJson = event.content()
                                Log.d(TAG, "Response content: $responseJson")

                                // Validate JSON
                                if (!responseJson.trim().startsWith("{")) {
                                    throw org.json.JSONException("Response content is not JSON: $responseJson")
                                }
                                val jsonObj = JSONObject(responseJson)
                                Log.d(TAG, "Response JSON validated successfully")

                                // Parse response JSON manually for summary
                                val summary = try {
                                    if (!responseJson.trim().startsWith("{")) {
                                        throw org.json.JSONException("Response content is not JSON: $responseJson")
                                    }
                                    val json = JSONObject(responseJson)
                                    val status = json.getString("status")

                                    when (status) {
                                        "ok" -> {
                                            if (json.has("result")) {
                                                val result = json.getJSONObject("result")
                                                val confirmed = result.optLong("confirmed_balance", 0)
                                                val unconfirmed = result.optLong("unconfirmed_balance", 0)

                                                val transactions = result.optJSONArray("transactions")
                                                val txCount = transactions?.length() ?: 0

                                                val recentTxIds = mutableListOf<String>()
                                                if (transactions != null) {
                                                    for (i in 0 until minOf(txCount, 3)) {
                                                        val tx = transactions.getJSONObject(i)
                                                        tx.optString("txid", "").takeIf { it.isNotEmpty() }?.let { recentTxIds.add(it) }
                                                    }
                                                }

                                                val txPreview = if (recentTxIds.isNotEmpty()) {
                                                    " (${recentTxIds.joinToString(", ")}...)"
                                                } else ""

                                                "Balance: ${confirmed} sats confirmed, ${unconfirmed} sats unconfirmed. ${txCount} transactions$txPreview"
                                            } else {
                                                "Response received but no balance data"
                                            }
                                        }
                                        "error" -> {
                                            "Error: ${json.optString("error", "Unknown error")}"
                                        }
                                        else -> {
                                            "Unexpected status: $status"
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to parse response JSON for summary", e)
                                    "Error: Failed to parse response"
                                }

                                Log.d(TAG, "Response summary: $summary")

                                withContext(Dispatchers.Main) {
                                    Log.d(TAG, "Calling callback.onResponse() on main thread")
                                    pending.callback.onResponse(responseJson)  // Pass raw JSON, not summary
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to parse response JSON", e)
                                Log.e(TAG, "JSON parse error: ${e.message}")
                                Log.e(TAG, "JSON parse stack trace: ${e.stackTraceToString()}")
                                withContext(Dispatchers.Main) {
                                    pending.callback.onError("Invalid response format: ${e.message}")
                                }
                            }
                        }
                    }
                }
            } else {
                Log.e(TAG, "Failed to create response event stream")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to listen for responses", e)
            Log.e(TAG, "Listen error: ${e.message}")
            Log.e(TAG, "Listen stack trace: ${e.stackTraceToString()}")
        }
    }

    /**
     * Publishes a real BalanceBridge bitcoin lookup request over Nostr.
     * Waits for and handles the response.
     */
    fun publishBalanceBridgeRequest(
        query: String,
        serverPubkeyHex: String,
        onResult: (Result<BitcoinLookupResult>) -> Unit
    ) {
        // Send a REAL bitcoin_lookup request and wait for response
        sendBitcoinLookupRequest(
            query = query,
            serverPubkeyHex = serverPubkeyHex,
            callback = object : NostrResponseCallback {
                override fun onResponse(jsonResponse: String) {
                    // Parse the JSON response and return typed result
                    try {
                        // Defensive guard: ensure content is JSON
                        if (!jsonResponse.trim().startsWith("{")) {
                            throw org.json.JSONException("Response content is not JSON: $jsonResponse")
                        }

                        val json = JSONObject(jsonResponse)
                        val status = json.getString("status")

                        when (status) {
                            "ok" -> {
                                if (json.has("result")) {
                                    val result = json.getJSONObject("result")
                                    val confirmed = result.optLong("confirmed_balance", 0)
                                    val unconfirmed = result.optLong("unconfirmed_balance", 0)

                                    val transactionsArray = result.optJSONArray("transactions")
                                    val transactions = mutableListOf<BitcoinTransaction>()
                                    if (transactionsArray != null) {
                                        for (i in 0 until transactionsArray.length()) {
                                            val txid = transactionsArray.optString(i, "")
                                            if (txid.isNotEmpty()) {
                                                transactions.add(BitcoinTransaction(txid = txid))
                                            }
                                        }
                                    }

                                    val lookupResult = BitcoinLookupResult(
                                        confirmedBalance = confirmed,
                                        unconfirmedBalance = unconfirmed,
                                        transactions = transactions
                                    )

                                    onResult(Result.success(lookupResult))
                                } else {
                                    onResult(Result.failure(Exception("Response received but no balance data")))
                                }
                            }
                            "error" -> {
                                val errorMsg = json.optString("error", "Server error")
                                onResult(Result.failure(Exception(errorMsg)))
                            }
                            else -> {
                                val errorMsg = "Unexpected response status: $status"
                                onResult(Result.failure(Exception(errorMsg)))
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse response JSON in publishBalanceBridgeRequest", e)
                        onResult(Result.failure(e))
                    }
                }

                override fun onError(error: String) {
                    onResult(Result.failure(Exception(error)))
                }
            },
            timeoutSeconds = 30
        )
    }

    // Legacy function - kept for compatibility but not used by UI
    fun sendBitcoinLookupRequest(
        query: String,
        serverPubkeyHex: String,
        callback: NostrResponseCallback,
        timeoutSeconds: Long = 30
    ) {
        Log.d(TAG, "=== SENDING BALANCEBRIDGE REQUEST ===")
        Log.d(TAG, "Query: $query")
        Log.d(TAG, "Server pubkey: $serverPubkeyHex")

        scope.launch {
            runCatching {
                val serverPub = PublicKey.parse(serverPubkeyHex)
                val requestId = UUID.randomUUID().toString()

                // Build BalanceBridge request JSON
                val requestJson = JSONObject().apply {
                    put("type", "bitcoin_lookup")
                    put("query", query)
                }
                val requestJsonString = requestJson.toString()

                Log.d(TAG, "BalanceBridge request payload: $requestJsonString")
                Log.d(TAG, "Request ID: $requestId")

                // Store pending request
                pendingRequests[requestId] = PendingRequest(requestId, callback, serverPub)

                // Build BalanceBridge Nostr event (kind 30078)
                val eventTags = mutableListOf<rust.nostr.sdk.Tag>()
                // "p" tag targeting the server pubkey
                eventTags.add(rust.nostr.sdk.Tag.publicKey(serverPub))
                // "req" tag with unique request ID
                eventTags.add(rust.nostr.sdk.Tag.parse(listOf("req", requestId)))

                val event = EventBuilder(
                    Kind(30078.toUShort()),
                    requestJsonString
                )
                    .tags(eventTags)
                    .sign(signer)

                Log.d(TAG, "BalanceBridge event built (kind: 30078)")
                Log.d(TAG, "Event tags: p=${serverPub.toHex()}, req=$requestId")
                Log.d(TAG, "Publishing BalanceBridge request event to public relays...")

                // Ensure client is connected
                if (client == null || !started.get()) {
                    if (serverPubkey == null) {
                        serverPubkey = serverPub
                    }
                    started.set(true)
                    connectToPublicRelays()
                    delay(1000) // Give time for connection
                }

                // Send event
                val currentClient = client
                if (currentClient == null) {
                    throw IllegalStateException("Nostr client not connected")
                }

                currentClient.sendEvent(event)
                Log.d("BalanceBridge-Android", "sendEvent() returned OK")
                Log.d(TAG, "✅ BalanceBridge request event published successfully to public relays")
                Log.d(TAG, "Event ID: ${event.id().toHex()}")
                Log.d(TAG, "====================================")

                // Subscribe to BalanceBridge responses for this specific request
                val responseFilter = Filter()
                    .kinds(listOf(Kind(30079.toUShort())))              // BalanceBridge RESPONSE
                    .since(Timestamp.now())                             // fresh events only

                currentClient.subscribe(responseFilter)
                Log.d("BalanceBridge-Android", "Subscribed to BalanceBridge responses")

                // Create response handler for this request
                scope.launch {
                    val responseStream = currentClient.streamEvents(responseFilter, Duration.ofMinutes(5))
                    if (responseStream != null) {
                        Log.d(TAG, "Response stream created for request $requestId")
                        try {
                            while (pendingRequests.containsKey(requestId)) {
                                val responseEvent = responseStream.next() ?: break

                                // Only log events that could potentially match (reduce noise)

                                // Check if this response matches our request
                                if (responseEvent.kind().asU16() == 30079.toUShort()) {
                                    // Validate by content: check if response contains our request ID
                                    val content = responseEvent.content()
                                    if (content.contains("\"req\":\"$requestId\"") || content.contains(requestId)) {
                                        Log.d("BalanceBridge-Android", "✅ Response matched for request $requestId")
                                        Log.d("BalanceBridge-Android", "Response kind: ${responseEvent.kind().asU16()}, req tag validated")

                                        // Parse BalanceBridge response
                                        try {
                                            val responseJson = responseEvent.content()
                                            Log.d(TAG, "Response content: $responseJson")

                                            // Parse response JSON manually for summary
                                            val summary = try {
                                                if (!responseJson.trim().startsWith("{")) {
                                                    throw org.json.JSONException("Response content is not JSON: $responseJson")
                                                }
                                                val json = JSONObject(responseJson)
                                                val status = json.getString("status")

                                                when (status) {
                                                    "ok" -> {
                                                        if (json.has("result")) {
                                                            val result = json.getJSONObject("result")
                                                            val confirmed = result.optLong("confirmed_balance", 0)
                                                            val unconfirmed = result.optLong("unconfirmed_balance", 0)

                                                            val transactions = result.optJSONArray("transactions")
                                                            val txCount = transactions?.length() ?: 0

                                                            val recentTxIds = mutableListOf<String>()
                                                            if (transactions != null) {
                                                                for (i in 0 until min(3, txCount)) {
                                                                    val tx = transactions.getJSONObject(i)
                                                                    tx.optString("txid", "").takeIf { it.isNotEmpty() }?.let { recentTxIds.add(it) }
                                                                }
                                                            }

                                                            val txPreview = if (recentTxIds.isNotEmpty()) {
                                                                " (${recentTxIds.joinToString(", ")}...)"
                                                            } else ""

                                                            "Balance: ${confirmed} sats confirmed, ${unconfirmed} sats unconfirmed. ${txCount} transactions$txPreview"
                                                        } else {
                                                            "Response received but no balance data"
                                                        }
                                                    }
                                                    "error" -> {
                                                        "Error: ${json.optString("error", "Unknown error")}"
                                                    }
                                                    else -> {
                                                        "Unexpected status: $status"
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Failed to parse response JSON for summary", e)
                                                "Error: Failed to parse response"
                                            }

                                            Log.d(TAG, "Response summary: $summary")

                                            Log.d("BalanceBridge-Android", "Response summary: $summary")

                                            withContext(Dispatchers.Main) {
                                                Log.d(TAG, "Calling callback.onResponse() on main thread")
                                                callback.onResponse(responseJson)  // Pass raw JSON, not summary
                                            }

                                            // Remove from pending requests (response delivered)
                                            pendingRequests.remove(requestId)
                                            return@launch

                                        } catch (e: Exception) {
                                            Log.e(TAG, "Failed to parse BalanceBridge response", e)
                                            withContext(Dispatchers.Main) {
                                                callback.onError("Failed to parse response: ${e.message}")
                                            }
                                            pendingRequests.remove(requestId)
                                            return@launch
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in response stream for request $requestId", e)
                        }
                    }
                }

                // Wait for response with timeout (response handled in separate coroutine)
                delay(timeoutSeconds * 1000L)

                // If still pending after timeout, trigger timeout error
                if (pendingRequests.containsKey(requestId)) {
                    pendingRequests.remove(requestId)
                    Log.e(TAG, "❌ BalanceBridge request timeout after $timeoutSeconds seconds")
                    Log.e(TAG, "Request ID: $requestId was not answered")
                    withContext(Dispatchers.Main) {
                        callback.onError("Request timeout: No response received within $timeoutSeconds seconds")
                    }
                }
            }.onFailure { error ->
                Log.e(TAG, "❌ Failed to send BalanceBridge request", error)
                Log.e(TAG, "Send error: ${error.message}")
                Log.e(TAG, "Send stack trace: ${error.stackTraceToString()}")
                withContext(Dispatchers.Main) {
                    callback.onError("Failed to send request: ${error.message}")
                }
            }
        }
    }

    private fun loadOrCreateKeys(): Keys {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val savedSecret = prefs.getString(PREF_SECRET_KEY, null)
        if (savedSecret != null) {
            return Keys(SecretKey.parse(savedSecret))
        }

        val generated = Keys.generate()
        prefs.edit().putString(PREF_SECRET_KEY, generated.secretKey().toHex()).apply()
        logInfo("Generated and stored new Nostr keypair (pubkey=${generated.publicKey().toHex()})")
        return generated
    }

    private fun logInfo(message: String) {
        Log.d(TAG, message)
    }

    private fun logError(message: String, throwable: Throwable) {
        Log.e(TAG, message, throwable)
        Log.e(TAG, "Error details: ${throwable.message}")
        Log.e(TAG, "Stack trace: ${throwable.stackTraceToString()}")
    }

    companion object {
        private const val TAG = "BalanceBridge-Android"
        private const val PREF_NAME = "nostr_prefs"
        private const val PREF_SECRET_KEY = "nostr_secret_hex"
    }
}


