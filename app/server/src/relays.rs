//! Relay configuration
//! 
//! Manages the list of public Nostr relays to use.

use std::env;
use tracing::info;

/// Default list of public Nostr relays
fn default_relays() -> Vec<String> {
    vec![
        "wss://relay.damus.io".to_string(),
        "wss://nostr.wine".to_string(),
        "wss://relay.primal.net".to_string(),
        "wss://nos.lol".to_string(),
        "wss://relay.snort.social".to_string(),
    ]
}

/// Get the list of relays to use
/// 
/// Reads from NOSTR_RELAYS environment variable (comma-separated).
/// Falls back to default list if env var is not set.
pub fn get_relays() -> Vec<String> {
    if let Ok(relays_env) = env::var("NOSTR_RELAYS") {
        let relays: Vec<String> = relays_env
            .split(',')
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .collect();
        
        if !relays.is_empty() {
            info!("Using relays from NOSTR_RELAYS: {:?}", relays);
            return relays;
        }
    }
    
    let defaults = default_relays();
    info!("Using default relay list: {:?}", defaults);
    defaults
}

