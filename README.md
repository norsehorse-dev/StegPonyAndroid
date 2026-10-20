# StegPony (Android)

Hide an encrypted message or file inside an ordinary photo, and get it back with a passphrase or a key. Without the secret there is nothing to find: no header, no signature, no fixed layout. Kotlin and Jetpack Compose over the shared Rust core, [StegPonyCore](https://github.com/norsehorse-dev/StegPonyCore), which defines the format and does all the image and crypto work.

Work in progress toward 1.0. What the development build does today:

- Hide text or a file in a JPEG (kept as a JPEG, with the camera's own compression) or in a PNG, under a passphrase or someone's `age1…` public key, with an optional decoy under a second passphrase.
- A capacity meter that rates how detectable the result would be (low, medium, high), from a steganalysis baseline on real phone photos.
- Location and other private metadata removed by default.
- Reveal with a passphrase; share an image into StegPony, or open one with it, to land on Reveal.
- No network permission. Screenshots are blocked on Reveal and Keys.

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
