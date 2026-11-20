# The Linux build environment for StegPony (Android) releases, after
# PassPonyAndroid's docker/release.Dockerfile.
#
# F-Droid builds from source on Linux. The NDK's clang for macOS and its clang
# for Linux are different binaries, and a library cross-compiled on a Mac is
# not guaranteed to come out byte for byte the same as one cross-compiled on
# Linux (PassPony and VaultPony both hit this). A release built here is built
# on the same kind of host F-Droid uses, whichever machine runs Docker.
#
# linux/amd64 is pinned: the NDK ships only an x86_64 clang for Linux, so on an
# Apple Silicon Mac the whole image runs emulated. Slow, and the same bytes.
#
# Keep in step by hand with gradle.properties (ndkVersion), StegPonyCore's
# rust-toolchain.toml (channel), and the fdroiddata recipe (cargo-ndk version).
FROM --platform=linux/amd64 ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive
ENV ANDROID_HOME=/usr/local/lib/android/sdk
ENV ANDROID_SDK_ROOT=/usr/local/lib/android/sdk
ENV NDK_VERSION=27.2.12479018
ENV RUST_VERSION=1.95.0
ENV CARGO_NDK_VERSION=4.1.2
ENV ANDROID_NDK_HOME=${ANDROID_SDK_ROOT}/ndk/${NDK_VERSION}
ENV CARGO_HOME=/root/.cargo
ENV PATH=${CARGO_HOME}/bin:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:${PATH}

RUN apt-get update && apt-get install -y --no-install-recommends \
      curl unzip git python3 ca-certificates openjdk-17-jdk-headless \
      build-essential pkg-config \
    && rm -rf /var/lib/apt/lists/*

# The toolchain StegPonyCore pins, with the components its rust-toolchain.toml
# names, so nothing is downloaded when the core is built.
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs \
      | sh -s -- -y --default-toolchain ${RUST_VERSION} --profile minimal \
           --component rustfmt --component clippy
RUN rustup target add aarch64-linux-android x86_64-linux-android
RUN cargo install cargo-ndk@${CARGO_NDK_VERSION} --locked

# If this download starts failing, https://developer.android.com/studio#command-tools
# has the current commandlinetools-linux file name. Only the number changes.
RUN mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools" \
    && curl -sL -o /tmp/cmdline-tools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip" \
    && unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_SDK_ROOT}/cmdline-tools" \
    && mv "${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools" "${ANDROID_SDK_ROOT}/cmdline-tools/latest" \
    && rm /tmp/cmdline-tools.zip

RUN yes | sdkmanager --licenses > /dev/null \
    && sdkmanager --install "platform-tools" "ndk;${NDK_VERSION}"

COPY release-entrypoint.sh /usr/local/bin/release-entrypoint.sh
RUN chmod +x /usr/local/bin/release-entrypoint.sh

WORKDIR /work
ENTRYPOINT ["/usr/local/bin/release-entrypoint.sh"]
