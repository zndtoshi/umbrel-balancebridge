package com.nomadwallet.balancebridge

import android.content.Context
import android.util.Log
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import rust.nostr.sdk.Client
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Keys
import rust.nostr.sdk.NostrSigner
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.SecretKey

class NostrManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val pairingStore = PairingStore
    
    private val keys: Keys = loadOrCreateKeys()
    private val signer: NostrSigner = NostrSigner.keys(keys)
    private var client: Client? = null

    fun start() {
        if (started.compareAndSet(false, true)) {
            scope.launch {
                runCatching { connectAndStart() }
                    .onFailure { error ->
                        logError("Failed to initialize Nostr", error)
                    }
            }
        }
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
    }

    private suspend fun connectAndStart() {
        val pairing = pairingStore.get()
        if (pairing == null) {
            logError("No pairing found, cannot connect", IllegalStateException("No pairing"))
            return
        }

        val clientInstance = Client(signer)
        this.client = clientInstance

        // Connect to all provided relays
        for (relayUrl in pairing.relays) {
            try {
                val relay = RelayUrl.parse(relayUrl)
                clientInstance.addRelay(relay)
                logInfo("Added relay: $relayUrl")
            } catch (e: Exception) {
                logError("Failed to add relay: $relayUrl", e)
            }
        }

        clientInstance.connect()
        logInfo("Connected to ${pairing.relays.size} relay(s) as ${keys.publicKey().toHex()}")

        // Send hello/paired event to Umbrel node
        sendHelloEvent(pairing.nodePubkey)
        
        // Start listening for responses
        listenForEvents(pairing.nodePubkey)
    }

    private suspend fun sendHelloEvent(nodePubkey: String) {
        try {
            val recipient = PublicKey.parse(nodePubkey)
            val message = "hello / paired"
            
            val encryptedContent = signer.nip44Encrypt(recipient, message)
            
            val event = EventBuilder(Kind(30078u), encryptedContent)
                .tags(listOf(rust.nostr.sdk.Tag.publicKey(recipient)))
                .sign(signer)

            client?.sendEvent(event)
            logInfo("Sent hello/paired event to ${recipient.toHex()}")
        } catch (e: Exception) {
            logError("Failed to send hello event", e)
        }
    }

    private suspend fun listenForEvents(nodePubkey: String) {
        try {
            val filter = Filter()
                .kinds(listOf(Kind(30078u)))
                .pubkey(keys.publicKey())

            val stream = client?.streamEvents(filter, Duration.ofMinutes(5))
            if (stream != null) {
                while (true) {
                    val event = stream.next() ?: break
                    try {
                        val decrypted = signer.nip44Decrypt(event.author(), event.content())
                        logInfo("Received encrypted message from ${event.author().toHex()}: $decrypted")
                    } catch (e: Exception) {
                        logError("Failed to decrypt event from ${event.author().toHex()}", e)
                    }
                }
            }
        } catch (e: Exception) {
            logError("Failed to listen for events", e)
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
        Log.i(TAG, message)
    }

    private fun logError(message: String, throwable: Throwable) {
        Log.e(TAG, message, throwable)
    }

    companion object {
        private const val TAG = "NostrManager"
        private const val PREF_NAME = "nostr_prefs"
        private const val PREF_SECRET_KEY = "nostr_secret_hex"
    }
}

