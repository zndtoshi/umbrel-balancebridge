//! Nostr event handler for Bitcoin lookup requests
//!
//! Listens for encrypted events from the paired Android app,
//! processes Bitcoin lookup requests, and sends responses.

use anyhow::{Context, Result};
use nostr_sdk::prelude::*;
use std::time::{SystemTime, UNIX_EPOCH};
use tracing::{error, info, warn};

use crate::electrs::ElectrsClient;
use crate::pairing::PairingManager;
use crate::protocol::{BitcoinLookupRequest, BitcoinLookupResponse};
use crate::xpub::{derive_addresses, is_bitcoin_address, is_xpub};

/// Handles Nostr communication with Android app
pub struct NostrHandler {
    client: Client,
    keys: Keys,
    pairing_manager: PairingManager,
    electrs_client: ElectrsClient,
}

impl NostrHandler {
    /// Create a new Nostr handler
    pub async fn new(
        keys: Keys,
        pairing_manager: PairingManager,
        relay_urls: Vec<String>,
    ) -> Result<Self> {
        let client = Client::new(keys.clone());

        // Add all relays (continue on failure for redundancy)
        let mut added_count = 0;
        for relay_url in &relay_urls {
            match Url::parse(relay_url) {
                Ok(url) => {
                    match client.add_relay(url).await {
                        Ok(_) => {
                            info!("Relay connected: {}", relay_url);
                            added_count += 1;
                        }
                        Err(e) => {
                            warn!("Relay failed: {} - {}", relay_url, e);
                        }
                    }
                }
                Err(e) => {
                    warn!("Invalid relay URL {}: {}", relay_url, e);
                }
            }
        }

        if added_count == 0 {
            return Err(anyhow::anyhow!("Failed to add any relays"));
        }

        // Connect to all relays and wait for completion
        client.connect().await;
        info!("Connected to {} relay(s)", added_count);

        let electrs_client = ElectrsClient::new().await?;

        let handler = Self {
            client,
            keys,
            pairing_manager,
            electrs_client,
        };

        // Subscribe immediately on startup (before pairing completes)
        handler.subscribe_immediately().await?;

        Ok(handler)
    }

    /// Subscribe immediately on startup (accepts events from past 60 seconds)
    async fn subscribe_immediately(&self) -> Result<()> {
        // Calculate timestamp for 60 seconds ago
        let now = Timestamp::now();
        let now_secs = now.as_secs();
        let since_secs = now_secs.saturating_sub(60);
        let since = Timestamp::from(since_secs);

        // Create filter: kind 30078, accept events from past 60 seconds
        // Don't filter by authors yet - accept all temporarily
        let filter = Filter::new()
            .kinds(vec![Kind::Custom(30078)])
            .since(since);

        self.client.subscribe(filter, None).await?;
        info!("Subscribed to kind 30078 events (since {} seconds ago)", 60);
        Ok(())
    }

    /// Start listening for events from the paired Android app
    pub async fn start_listening(&self) -> Result<()> {
        // Get notification stream (subscription already active from startup)
        let mut notifications = self.client.notifications();

        // Get paired Android pubkey (if any)
        let android_pubkey = self.pairing_manager.get_android_pubkey()?;

        if let Some(ref pk) = android_pubkey {
            info!("Listening for events from Android pubkey: {}", pk.to_hex());
        } else {
            warn!("No Android app paired yet, accepting all kind 30078 events for pairing...");
        }

        // Process incoming events with reconnection handling
        loop {
            match notifications.recv().await {
                Ok(notification) => {
                    match notification {
                        RelayPoolNotification::Event { event, .. } => {
                            // Log event receipt immediately (before any validation)
                            info!("Received Nostr event: id={}, pubkey={}", 
                                event.id.to_hex(), event.pubkey.to_hex());

                            // If paired, validate pubkey matches
                            if let Some(ref expected_pubkey) = android_pubkey {
                                if event.pubkey != *expected_pubkey {
                                    warn!(
                                        "Event from non-paired pubkey: {} (expected: {})",
                                        event.pubkey.to_hex(),
                                        expected_pubkey.to_hex()
                                    );
                                    continue;
                                }
                            }

                            // Handle event (decryption happens inside)
                            if let Err(e) = self.handle_event(*event).await {
                                error!("Error handling event: {}", e);
                            }
                        }
                        RelayPoolNotification::Message { .. } => {
                            // Ignore other message types
                        }
                        RelayPoolNotification::Shutdown => {
                            warn!("Relay pool shutdown");
                            // Continue listening - relay pool will handle reconnection
                        }
                        _ => {}
                    }
                }
                Err(e) => {
                    warn!("Error receiving notification: {}", e);
                    // Continue listening - do not exit loop
                }
            }
        }
    }

    /// Check if event is within expiration tolerance (60 seconds)
    /// Returns Ok(()) if valid, logs warning if expired but doesn't reject
    fn check_event_expiration(&self, event: &Event) -> Result<()> {
        let event_time = event.created_at.as_secs();
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .context("System time error")?
            .as_secs();
        
        let delta = if now > event_time {
            now - event_time
        } else {
            0
        };
        
        if delta > 60 {
            warn!(
                "Event is {} seconds old (max 60s), but processing anyway",
                delta
            );
        } else {
            info!(
                "Event timestamp check: created_at={}, now={}, delta={}s",
                event_time, now, delta
            );
        }

        Ok(())
    }


    /// Handle a pairing event ("hello / paired")
    async fn handle_pairing_event(&self, event: Event) -> Result<()> {
        // Try to decrypt the event
        let decrypted = match self.keys.nip44_decrypt(&event.pubkey, &event.content).await {
            Ok(msg) => msg,
            Err(e) => {
                warn!("Failed to decrypt pairing event: {}", e);
                return Ok(());
            }
        };

        if decrypted == "hello / paired" {
            info!("Received pairing from: {}", event.pubkey.to_hex());

            // Get relays from the event tags (if available)
            // For now, use default relays
            let relays = self.pairing_manager.get_relays()
                .unwrap_or_else(|_| vec!["wss://relay.damus.io".to_string()]);

            // Store the pairing
            self.pairing_manager.store_pairing(event.pubkey, relays)?;

            info!("Android app paired successfully");
        }

        Ok(())
    }

    /// Handle an incoming event from the paired Android app
    async fn handle_event(&self, event: Event) -> Result<()> {
        let pubkey_hex = event.pubkey.to_hex();
        let sender_pubkey_short = format!("{}...{}", 
            &pubkey_hex[0..8], 
            &pubkey_hex[pubkey_hex.len()-8..]);
        
        // Check expiration (log only, don't reject)
        self.check_event_expiration(&event)?;

        // Decrypt the event content (don't reject before decryption)
        let decrypted = match self
            .keys
            .nip44_decrypt(&event.pubkey, &event.content)
            .await
        {
            Ok(msg) => msg,
            Err(e) => {
                warn!("Failed to decrypt event from {}: {}", sender_pubkey_short, e);
                // If not paired, this might be a pairing event
                if !self.pairing_manager.has_pairing() {
                    if let Err(e) = self.handle_pairing_event(event).await {
                        error!("Error handling pairing event: {}", e);
                    }
                }
                return Ok(());
            }
        };

        info!("Event decrypted successfully from {}", sender_pubkey_short);

        // Try to parse as Bitcoin lookup request
        let request: BitcoinLookupRequest = match serde_json::from_str(&decrypted) {
            Ok(req) => req,
            Err(e) => {
                warn!("Failed to parse request JSON from {}: {}", sender_pubkey_short, e);
                return Ok(());
            }
        };

        // Validate request
        if !request.is_valid() {
            warn!("Invalid Bitcoin lookup request from {}: {:?}", sender_pubkey_short, request);
            return Ok(());
        }

        // Determine query type
        let query_type = if crate::xpub::is_xpub(&request.query) {
            "xpub"
        } else if crate::xpub::is_bitcoin_address(&request.query) {
            "address"
        } else {
            "unknown"
        };

        info!("Bitcoin lookup started from {}: type={}, query={}", 
            sender_pubkey_short, query_type, request.query);

        // Process the request
        let response = self.process_bitcoin_lookup(&request.query).await?;

        // Send response back
        self.send_response(event.pubkey, &response).await?;

        Ok(())
    }

    /// Process a Bitcoin lookup request
    async fn process_bitcoin_lookup(&self, query: &str) -> Result<BitcoinLookupResponse> {
        let mut response = BitcoinLookupResponse::new(query.to_string());

        if is_xpub(query) {
            // Handle xpub/ypub/zpub/tpub
            info!("Processing xpub query: {}", query);
            let addresses = derive_addresses(query, 20)?;
            info!("Derived {} addresses from xpub (gap_limit=20)", addresses.len());

            let mut total_confirmed = 0u64;
            let mut total_unconfirmed = 0u64;
            let mut all_txids = Vec::new();

            for address in addresses {
                match self.electrs_client.get_address_balance(&address).await {
                    Ok((confirmed, unconfirmed)) => {
                        total_confirmed += confirmed;
                        total_unconfirmed += unconfirmed;
                    }
                    Err(e) => {
                        warn!("Failed to get balance for derived address {}: {}", address, e);
                    }
                }

                match self.electrs_client.get_address_txs(&address).await {
                    Ok(txids) => {
                        all_txids.extend(txids);
                    }
                    Err(e) => {
                        warn!("Failed to get transactions for derived address {}: {}", address, e);
                    }
                }
            }

            // Deduplicate txids
            all_txids.sort();
            all_txids.dedup();

            info!("Electrs query completed for xpub: confirmed={} sats, unconfirmed={} sats, tx_count={}",
                total_confirmed, total_unconfirmed, all_txids.len());

            response.confirmed_balance = total_confirmed as i64;
            response.unconfirmed_balance = total_unconfirmed as i64;
            response.transactions = all_txids
                .into_iter()
                .map(|txid| crate::protocol::TransactionInfo {
                    txid,
                    timestamp: 0, // Electrum doesn't provide timestamps
                    amount: 0,    // Electrum doesn't provide amounts in history
                    confirmations: 1, // Assume confirmed if in history
                })
                .collect();
        } else if is_bitcoin_address(query) {
            // Handle single Bitcoin address
            info!("Processing single address query: {}", query);
            let (confirmed, unconfirmed) = self
                .electrs_client
                .get_address_balance(query)
                .await?;

            info!("Electrs balance query completed: confirmed={} sats, unconfirmed={} sats", 
                confirmed, unconfirmed);

            let txids: Vec<String> = self
                .electrs_client
                .get_address_txs(query)
                .await?;

            info!("Electrs transaction query completed: tx_count={}", txids.len());

            response.confirmed_balance = confirmed as i64;
            response.unconfirmed_balance = unconfirmed as i64;
            response.transactions = txids
                .into_iter()
                .map(|txid| crate::protocol::TransactionInfo {
                    txid,
                    timestamp: 0, // Electrum doesn't provide timestamps
                    amount: 0,    // Electrum doesn't provide amounts in history
                    confirmations: 1, // Assume confirmed if in history
                })
                .collect();
        } else {
            return Err(anyhow::anyhow!("Invalid query: not an address or xpub"));
        }

        info!(
            "Bitcoin lookup result: confirmed={}, unconfirmed={}, tx_count={}",
            response.confirmed_balance,
            response.unconfirmed_balance,
            response.transactions.len()
        );

        Ok(response)
    }

    /// Send a response back to the Android app
    async fn send_response(&self, recipient: PublicKey, response: &BitcoinLookupResponse) -> Result<()> {
        let recipient_short = format!("{}...{}", 
            &recipient.to_hex()[0..8], 
            &recipient.to_hex()[recipient.to_hex().len()-8..]);

        let response_json = serde_json::to_string(response)
            .context("Failed to serialize response")?;

        info!("Sending response to Android app ({}): confirmed={} sats, unconfirmed={} sats, tx_count={}", 
            recipient_short, response.confirmed_balance, response.unconfirmed_balance, response.transactions.len());

        // Encrypt the response
        let encrypted = match self.keys.nip44_encrypt(&recipient, &response_json).await {
            Ok(enc) => enc,
            Err(e) => {
                error!("Failed to encrypt response to {}: {}", recipient_short, e);
                return Err(anyhow::anyhow!("Encryption failed: {}", e));
            }
        };

        // Create unsigned event
        let unsigned = EventBuilder::new(Kind::Custom(30078), encrypted)
            .build(self.keys.public_key());

        // Sign the event
        let event = self.keys.sign_event(unsigned).await
            .context("Failed to sign event")?;

        match self.client.send_event(&event).await {
            Ok(_) => {
                info!("Response sent successfully to {}", recipient_short);
                Ok(())
            }
            Err(e) => {
                error!("Failed to send response to {}: {}", recipient_short, e);
                Err(anyhow::anyhow!("Failed to send event: {}", e))
            }
        }
    }
}

