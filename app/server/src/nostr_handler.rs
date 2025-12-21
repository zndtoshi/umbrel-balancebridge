use anyhow::{anyhow, Result};
use nostr_sdk::prelude::*;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tokio::time::{timeout, Duration};
use tracing::{error, info, warn};

use crate::electrs::ElectrsClient;
use crate::nostr::NostrState;
use crate::pairing::PairingManager;

pub const BALANCEBRIDGE_REQUEST_KIND: u16 = 30078;
pub const BALANCEBRIDGE_RESPONSE_KIND: u16 = 30079;

/* -------------------- Request / Response -------------------- */

#[derive(Debug, Serialize, Deserialize)]
struct BitcoinLookupRequest {
    #[serde(rename = "type")]
    req_type: String,
    query: String,
}

/*
 Android MVP compatibility:
 - req inside JSON
 - legacy field names
*/
#[derive(Debug, Serialize)]
struct BitcoinLookupResponse {
    // Android MVP fields
    req: String,
    confirmedBalance: u64,
    unconfirmedBalance: u64,
    confirmations: u64,
    amount: u64,

    // Modern fields
    confirmed_balance: u64,
    unconfirmed_balance: u64,
    transactions: Vec<TransactionInfo>,
}

#[derive(Debug, Serialize)]
struct TransactionInfo {
    txid: String,
}

/* -------------------- Handler -------------------- */

pub struct NostrHandler {
    client: Arc<Client>,
    keys: Keys,
    electrs_client: Arc<ElectrsClient>,
}

impl NostrHandler {
    pub async fn new(
        nostr_state: NostrState,
        keys: Keys,
        _pairing_manager: PairingManager,
        electrs_client: Arc<ElectrsClient>,
    ) -> Result<Self> {
        Ok(Self {
            client: nostr_state.client.clone(),
            keys,
            electrs_client,
        })
    }

    pub async fn start_listening(&self) -> Result<()> {
        let filter = Filter::new()
            .kinds(vec![Kind::Custom(BALANCEBRIDGE_REQUEST_KIND)]);

        self.client.subscribe(filter, None).await?;

        info!(
            "Subscribed to BalanceBridge request kind={}",
            BALANCEBRIDGE_REQUEST_KIND
        );

        let mut notifications = self.client.notifications();

        // IMPORTANT: never exit this loop on bad events
        while let Ok(notification) = notifications.recv().await {
            if let RelayPoolNotification::Event { event, .. } = notification {
                if event.kind.as_u16() != BALANCEBRIDGE_REQUEST_KIND {
                    continue;
                }

                let from_pk = event.pubkey;

                // 🔑 FIX: ignore events without req tag instead of crashing
                let req_id = match extract_req_id(&event) {
                    Some(v) => v,
                    None => {
                        warn!(
                            "Ignoring BalanceBridge request without req tag (from={})",
                            from_pk.to_hex()
                        );
                        continue;
                    }
                };

                let parsed: BitcoinLookupRequest =
                    match serde_json::from_str(&event.content) {
                        Ok(v) => v,
                        Err(e) => {
                            warn!(
                                "Invalid request JSON (from={} req={}): {}",
                                from_pk.to_hex(),
                                req_id,
                                e
                            );
                            continue;
                        }
                    };

                if parsed.req_type != "bitcoin_lookup" {
                    continue;
                }

                let address = parsed.query.clone();

                info!(
                    "Nostr lookup request: from={} req={} query={}",
                    from_pk.to_hex(),
                    req_id,
                    address
                );

                if let Err(e) = self
                    .lookup_and_publish(from_pk, &req_id, address)
                    .await
                {
                    error!(
                        "Lookup failed: from={} req={} err={}",
                        from_pk.to_hex(),
                        req_id,
                        e
                    );
                }
            }
        }

        Ok(())
    }

    async fn lookup_and_publish(
        &self,
        to_pubkey: PublicKey,
        req_id: &str,
        address: String,
    ) -> Result<()> {
        let (confirmed, unconfirmed) = timeout(
            Duration::from_secs(30),
            self.electrs_client.get_address_balance(&address),
        )
        .await
        .map_err(|_| anyhow!("Electrs balance timeout"))??;

        let txids = match timeout(
            Duration::from_secs(20),
            self.electrs_client.get_address_txs(&address),
        )
        .await
        {
            Ok(Ok(v)) => v,
            _ => vec![],
        };

        info!(
            "Lookup OK: req={} confirmed={} unconfirmed={} txs={}",
            req_id,
            confirmed,
            unconfirmed,
            txids.len()
        );

        let response = BitcoinLookupResponse {
            req: req_id.to_string(),
            confirmedBalance: confirmed,
            unconfirmedBalance: unconfirmed,
            confirmations: txids.len() as u64,
            amount: confirmed + unconfirmed,

            confirmed_balance: confirmed,
            unconfirmed_balance: unconfirmed,
            transactions: txids
                .into_iter()
                .map(|txid| TransactionInfo { txid })
                .collect(),
        };

        let json = serde_json::to_string(&response)?;

        let tags = vec![
            Tag::parse(["p", to_pubkey.to_hex().as_str()])?,
            Tag::parse(["req", req_id])?,
        ];

        let event = EventBuilder::new(
            Kind::Custom(BALANCEBRIDGE_RESPONSE_KIND),
            json,
        )
        .tags(tags)
        .sign_with_keys(&self.keys)?;

        info!(
            "Publishing response: kind={} to={} req={}",
            BALANCEBRIDGE_RESPONSE_KIND,
            to_pubkey.to_hex(),
            req_id
        );

        self.client.send_event(&event).await?;

        Ok(())
    }
}

/* -------------------- Helpers -------------------- */

fn extract_req_id(event: &Event) -> Option<String> {
    for t in event.tags.iter() {
        let v = t.clone().to_vec();
        if v.len() >= 2 && v[0] == "req" {
            return Some(v[1].to_string());
        }
    }
    None
}
