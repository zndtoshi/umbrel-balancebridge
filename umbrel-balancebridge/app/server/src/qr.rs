//! QR code generation for pairing
//! 
//! Generates QR payload and QR code images for Android app pairing.

use anyhow::{Context, Result};
use qrcode::QrCode;
use image::Luma;
use serde::{Deserialize, Serialize};

const APP_IDENTIFIER: &str = "umbrel-balancebridge";
const VERSION: u32 = 1;

/// Pairing payload structure matching Android app expectations
#[derive(Debug, Serialize, Deserialize)]
pub struct PairingPayload {
    pub version: u32,
    pub app: String,
    #[serde(rename = "nodePubkey")]
    pub node_pubkey: String,
    pub relays: Vec<String>,
}

impl PairingPayload {
    /// Create a new pairing payload
    pub fn new(node_pubkey: String, relays: Vec<String>) -> Self {
        Self {
            version: VERSION,
            app: APP_IDENTIFIER.to_string(),
            node_pubkey,
            relays,
        }
    }
    
    /// Serialize to JSON string
    pub fn to_json(&self) -> Result<String> {
        serde_json::to_string(self)
            .context("Failed to serialize pairing payload")
    }
    
    /// Generate QR code image
    pub fn generate_qr_image(&self, size: u32) -> Result<Vec<u8>> {
        let json = self.to_json()?;
        let qr_code = QrCode::new(json.as_bytes())
            .context("Failed to generate QR code")?;
        
        let image = qr_code.render::<Luma<u8>>()
            .max_dimensions(size, size)
            .build();
        
        // Convert to image::DynamicImage and encode as PNG
        let img_buffer = image::ImageBuffer::from_raw(
            size,
            size,
            image.into_raw()
        ).context("Failed to create image buffer")?;
        
        let dynamic_image = image::DynamicImage::ImageLuma8(img_buffer);
        let mut buffer = Vec::new();
        dynamic_image.write_to(
            &mut std::io::Cursor::new(&mut buffer),
            image::ImageOutputFormat::Png
        )?;
        
        Ok(buffer)
    }
}

