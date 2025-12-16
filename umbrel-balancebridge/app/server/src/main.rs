use anyhow::{Context, Result};
use tracing::info;

mod config;
mod error;
mod identity;
mod relays;
mod qr;

/// BalanceBridge Umbrel Server
/// 
/// Main entry point for the server application.
/// Handles Nostr communication with Android clients.
/// 
/// Features:
/// - Connects to public Nostr relays
/// - Uses NIP-44 encryption
/// - Generates QR codes for pairing
/// - Will integrate with Fulcrum/Electrs in the future

#[tokio::main]
async fn main() -> Result<()> {
    // Initialize tracing
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::from_default_env())
        .init();

    info!("BalanceBridge Umbrel Server starting...");

    // Get Umbrel app data directory
    // This is where all persistent data is stored (keys, QR codes, etc.)
    let data_dir = config::get_data_dir();
    info!("Using Umbrel data directory: {}", data_dir.display());
    
    if let Some(app_id) = config::get_app_id() {
        info!("Umbrel app ID: {}", app_id);
    }

    // Initialize Nostr identity
    let identity = identity::IdentityManager::new(&data_dir)
        .context("Failed to initialize identity")?;
    
    let public_key_hex = identity.public_key_hex();
    info!("Nostr public key: {}", public_key_hex);

    // Get relay list
    let relays = relays::get_relays();
    info!("Using {} relay(s): {:?}", relays.len(), relays);

    // Generate pairing payload
    let pairing_payload = qr::PairingPayload::new(public_key_hex.clone(), relays.clone());
    let pairing_json = pairing_payload.to_json()?;
    info!("Pairing payload: {}", pairing_json);

    // Generate QR code image
    let qr_image_data = pairing_payload.generate_qr_image(512)?;
    let qr_path = data_dir.join("pairing_qr.png");
    std::fs::write(&qr_path, qr_image_data)
        .context("Failed to write QR code image")?;
    info!("QR code saved to: {}", qr_path.display());

    // TODO: Initialize Nostr relay connections
    // TODO: Set up NIP-44 encryption
    // TODO: Implement event handlers for Android communication
    // TODO: Integrate with Fulcrum/Electrs

    info!("Server initialized and ready for pairing");

    // Keep the server running
    tokio::signal::ctrl_c().await?;
    info!("Shutting down...");

    Ok(())
}

