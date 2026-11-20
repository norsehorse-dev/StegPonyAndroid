#!/usr/bin/env bash
# Runs inside the release.Dockerfile image; docker/README.md has the docker run
# line. Clones a pushed ref fresh from GitHub (never a working tree), builds the
# release APK, and copies it to /out/<version>/.
#
# With the release keystore mounted, the APK is signed for release by the same
# Gradle build that makes it (never re-signed afterwards, which would change
# its bytes). Without it, the build is a check build signed with a throwaway
# debug key: enough to compare contents, not something to publish.
set -euo pipefail

VERSION="${1:?usage: release-entrypoint.sh <version> <ref>, e.g. 1.0.0 v1.0.0}"
REF="${2:?usage: release-entrypoint.sh <version> <ref>, e.g. 1.0.0 v1.0.0}"

echo "--- Cloning StegPonyAndroid at $REF ---"
git clone https://github.com/norsehorse-dev/StegPonyAndroid.git /work/repo
cd /work/repo
git checkout "$REF"
git submodule update --init --recursive
echo "StegPonyAndroid $(git rev-parse HEAD)"
echo "StegPonyCore    $(git -C third_party/stegponycore rev-parse HEAD)"

if [[ -f /keystore/release.keystore && -f /keystore-props/keystore.properties ]]; then
  # keystore.properties on the host names the keystore by a host path. Point
  # that one line at the mount; the passwords and alias pass through as they are.
  sed 's#^storeFile=.*#storeFile=/keystore/release.keystore#' \
    /keystore-props/keystore.properties > keystore.properties
  NAME="StegPony-$VERSION.apk"
  echo "--- Release keystore mounted: building the signed release ---"
else
  NAME="StegPony-$VERSION-check.apk"
  echo "--- No keystore mounted: check build, signed with a throwaway debug key ---"
fi

# Under amd64 emulation on Apple Silicon an unbounded Rust compile can deadlock
# and R8 can run out of memory (both seen on PassPony). These change how many
# jobs run at once and the JVM heap, never the output bytes. A -e on docker run
# overrides them.
export CARGO_BUILD_JOBS="${CARGO_BUILD_JOBS:-2}"
export GRADLE_OPTS="${GRADLE_OPTS:--Dorg.gradle.jvmargs=-Xmx6g -Dorg.gradle.workers.max=2 -Dorg.gradle.parallel=false}"

echo "--- Building (CARGO_BUILD_JOBS=$CARGO_BUILD_JOBS) ---"
./gradlew --no-daemon :app:assembleRelease

APK=app/build/outputs/apk/release/app-release.apk
[[ -f "$APK" ]] || { echo "Expected APK not found at $APK" >&2; exit 1; }

mkdir -p "/out/$VERSION"
cp "$APK" "/out/$VERSION/$NAME"
echo "--- Done ---"
( cd "/out/$VERSION" && sha256sum "$NAME" )
