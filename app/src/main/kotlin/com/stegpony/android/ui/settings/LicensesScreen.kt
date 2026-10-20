package com.stegpony.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.stegpony.android.R

/** The open-source parts StegPony ships, with their licenses. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    SubPage(stringResource(R.string.licenses_title), onBack) {
        LICENSES.forEach { (name, license) -> HelpTopic(name, license) }
    }
}

// Names and license identifiers are not translated.
private val LICENSES = listOf(
    "StegPony and StegPonyCore" to "Apache-2.0. Source: github.com/norsehorse-dev",
    "OpenPGP card and QR code from PGPony Android and PassPony Android" to "Apache-2.0, same author",
    "RustCrypto crates (ChaCha20-Poly1305, Argon2, HKDF, SHA-2 and others)" to "MIT or Apache-2.0",
    "curve25519-dalek, x25519-dalek, ed25519-dalek, subtle, curve25519-elligator2" to "BSD-3-Clause",
    "rPGP (pgp)" to "MIT or Apache-2.0",
    "zune-jpeg" to "MIT, Apache-2.0 or Zlib",
    "png, miniz_oxide, rayon, thiserror, zeroize and other Rust crates" to "MIT or Apache-2.0 (a few MIT, Zlib or Unlicense)",
    "bech32" to "MIT",
    "UniFFI" to "MPL-2.0",
    "AndroidX, Jetpack Compose, CameraX, Biometric" to "Apache-2.0",
    "Kotlin and kotlinx.coroutines" to "Apache-2.0",
    "JNA" to "Apache-2.0 or LGPL-2.1",
    "ZXing core" to "Apache-2.0",
)
