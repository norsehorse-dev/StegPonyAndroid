# StegPony (Android)

Hide an encrypted message or file inside an ordinary photo or in text, and get it back with a passphrase or a key. Without the secret there is nothing to find: no header, no signature, no fixed layout. Kotlin and Jetpack Compose over the shared Rust core, [StegPonyCore](https://github.com/norsehorse-dev/StegPonyCore), which defines the format and does all the image and crypto work.

Work in progress toward 1.0. What the development build does today:

- **Hide** text or a file in a JPEG (kept as a JPEG, with the camera's own compression) or a PNG, under a passphrase, a contact's key or one of your own, with an optional decoy under a second passphrase. Take the cover with the in-app camera, or pick one; StegPony warns before a cover is reused, or when it looks like a download or a messenger copy.
- **Telegram photos from an iPhone (experimental)**: choose Telegram photo from an iPhone on Hide and the core first makes a copy of the cover the way Telegram on an iPhone encodes photos (1280 pixels on the long side, its JPEG tables, very bright and dark patches toned down), then hides in that copy in JPEG mode. Sent from the iPhone's Telegram gallery, a copy made by the app came through with every hidden coefficient intact in a first test. Telegram on Android recompresses with added noise, so sending from Android does not work; neither does attaching the image as a file in Telegram. Not verified yet.
- **Hide in text** when a photo can't be sent: as a run of ordinary words (reads as nonsense, gets past filters and a quick look), or as invisible characters inside a message you write (weak; many apps strip them). Copy, share or save the result; the same keys and decoy apply.
- **Detectability rating**: a capacity meter rates how detectable the result would be (low, medium, high), from a steganalysis baseline on real phone photos.
- **Reveal** with a passphrase, your saved keys, a pasted secret key, or an OpenPGP hardware key (YubiKey, Nitrokey, Token2) over NFC or USB, where the key does the X25519 step and its secret never leaves it. Paste text to look in it. Share an image or text into StegPony, or open an image with it, to land on Reveal.
- **Keys**: create age-compatible keys, import age identities and Curve25519 OpenPGP keys (from PGPony or GnuPG), add contacts by paste, file, QR code or share, and show your public key as a QR code. Keys and contacts are encrypted with a Keystore key and left out of backups.
- **Privacy**: no network permission, location and other metadata removed by default, app lock, screenshots blocked, clipboard cleared after a minute, and an optional launcher disguise.

Images go to the core as their exact file bytes and come back the same way. The app never decodes a cover to a bitmap for the core, and saves and shares the bytes the core wrote.

## Building

Prereqs: JDK 17, the Android SDK with the NDK named in `gradle.properties`, rustup, and cargo-ndk (`cargo install cargo-ndk`).

```
git submodule update --init --recursive
bash scripts/build-core.sh
./gradlew :app:assembleDebug
```

`scripts/build-core.sh` cross-compiles StegPonyCore's `stegpony-ffi` crate for arm64-v8a and x86_64 with the Rust toolchain StegPonyCore pins, and generates the Kotlin bindings into the `:core` module. It uses `STEGPONY_CORE` if set, then a sibling checkout at `../StegPonyCore`, then the submodule. Gradle runs it on its own when its outputs are missing; after changing the core, run it again.

## License

Apache-2.0. See [LICENSE](LICENSE).
