# Release build container

Builds the release APK on Linux, the kind of host F-Droid builds on, so the
Rust core in it matches what F-Droid's servers produce. A Mac build of the same
commit is not guaranteed to. `tools/verify_repro.sh` is the quick check on one
machine; this is the build that gets published.

Needs Docker Desktop on the machine that holds the release keystore. Under
emulation on Apple Silicon, give Docker 12 GB of memory: with less, the kernel
kills Gradle part way through ("daemon disappeared unexpectedly").

Build the image once, and again whenever `ndkVersion` in `gradle.properties`,
the Rust version StegPonyCore pins, or the cargo-ndk version changes:

```
cd ~/Apps/StegPonyAndroid
docker build -t stegpony-release -f docker/release.Dockerfile docker
```

A check build, with no keystore. `<ref>` is a tag, a branch or a commit that is
already pushed, since the container clones from GitHub:

```
docker run --rm -v ~/Apps/StegPonyAndroid/drafts/rc:/out stegpony-release <version> <ref>
```

The signed release, with the keystore and its properties mounted read-only:

```
docker run --rm \
  -v ~/Keystores/stegpony-release.jks:/keystore/release.keystore:ro \
  -v ~/Apps/StegPonyAndroid/keystore.properties:/keystore-props/keystore.properties:ro \
  -v ~/Apps/StegPonyAndroid/drafts/rc:/out \
  stegpony-release <version> <ref>
```

The APK lands in `drafts/rc/<version>/`: `StegPony-<version>.apk` when signed,
`StegPony-<version>-check.apk` for a check build. Compare two builds with
`tools/verify_repro.sh compare <a.apk> <b.apk>`.
