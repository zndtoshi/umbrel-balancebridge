package com.nomadwallet.balancebridge

object BitcoinInputValidator {
    // Bitcoin address patterns
    private val LEGACY_ADDRESS_PATTERN = Regex("^[13][a-km-zA-HJ-NP-Z1-9]{25,34}$")
    private val SEGWIT_ADDRESS_PATTERN = Regex("^bc1[a-z0-9]{39,59}$")
    
    // Extended public key patterns
    private val XPUB_PATTERN = Regex("^(xpub|ypub|zpub|tpub)[a-zA-Z0-9]{107,108}$", RegexOption.IGNORE_CASE)
    
    fun validate(input: String): ValidationResult {
        val trimmed = input.trim()
        
        if (trimmed.isEmpty()) {
            return ValidationResult(false, "Input cannot be empty")
        }
        
        // Check for extended public key
        if (XPUB_PATTERN.matches(trimmed)) {
            return ValidationResult(true, null)
        }
        
        // Check for legacy address (starts with 1 or 3)
        if (LEGACY_ADDRESS_PATTERN.matches(trimmed)) {
            return ValidationResult(true, null)
        }
        
        // Check for SegWit address (starts with bc1)
        if (SEGWIT_ADDRESS_PATTERN.matches(trimmed)) {
            return ValidationResult(true, null)
        }
        
        return ValidationResult(
            false,
            "Invalid input. Please enter a valid Bitcoin address (bc1/1/3) or extended public key (xpub/ypub/zpub/tpub)"
        )
    }
    
    data class ValidationResult(
        val isValid: Boolean,
        val errorMessage: String?
    )
}

