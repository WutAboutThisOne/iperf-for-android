#!/usr/bin/env bash
# Cross-compiles esnet/iperf3 (BSD-3-Clause) for Android with the NDK and drops the
# executables into app/src/main/jniLibs/<abi>/libiperf3.so.
#
# Why "lib*.so"? Android 10+ only lets an app exec() files from its nativeLibraryDir, and the
# installer only extracts files named lib*.so there. The file is a normal PIE executable.
#
# iperf3 is statically linked against OpenSSL (Apache-2.0, libcrypto only) so that iperf3's
# optional RSA server login (--username / --rsa-public-key-path) works.
#
# Reproducibility / privacy: sources are pinned (iperf3 by tag *and* commit, OpenSSL by
# SHA-256), OpenSSL is configured with neutral install paths and staged via DESTDIR, and
# -ffile-prefix-map strips the local checkout path from __FILE__ strings, so no build-machine
# paths end up inside the binaries.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
IPERF_SRC="${IPERF_SRC:-$ROOT/native/iperf}"
IPERF_TAG="${IPERF_TAG:-3.22}"
IPERF_COMMIT="${IPERF_COMMIT:-926bd905501b792bd082d2200d1d1a84aa543e3a}"
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/26.3.11579264}"
API=29
OPENSSL_VER="${OPENSSL_VER:-3.5.9}"
OPENSSL_SHA256="603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a"
# Neutral paths baked into libcrypto (unused at runtime: no config files, engines or modules).
OPENSSL_PREFIX=/opt/openssl
OPENSSL_DIR=/etc/ssl
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
MAKE_BIN="${MAKE_BIN:-$(command -v make || echo "$NDK/prebuilt/linux-x86_64/bin/make")}"
export MAKE="$MAKE_BIN"

if [ ! -d "$IPERF_SRC" ]; then
  git clone --depth 1 --branch "$IPERF_TAG" https://github.com/esnet/iperf.git "$IPERF_SRC"
fi
actual="$(git -C "$IPERF_SRC" rev-parse HEAD)"
if [ "$actual" != "$IPERF_COMMIT" ]; then
  echo "iperf3 source is at $actual, expected $IPERF_COMMIT (tag $IPERF_TAG)" >&2; exit 1
fi
# Optional local fixes (idempotent), applied if present. None are used for the shipped build.
for p in "$HERE"/patches/*.patch; do
  [ -e "$p" ] || continue
  git -C "$IPERF_SRC" apply --check "$p" 2>/dev/null && git -C "$IPERF_SRC" apply "$p" || true
done

OPENSSL_TGZ="$ROOT/native/openssl-$OPENSSL_VER.tar.gz"
if [ ! -f "$OPENSSL_TGZ" ]; then
  curl -sSL -o "$OPENSSL_TGZ" "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VER/openssl-$OPENSSL_VER.tar.gz"
fi
echo "$OPENSSL_SHA256  $OPENSSL_TGZ" | sha256sum -c - >/dev/null

# Builds a static libcrypto for one ABI and stages it under native/build/openssl-<abi>.
build_openssl() {
  local abi="$1" target="$2"
  local src="$ROOT/native/build/openssl-$abi-src" stage="$ROOT/native/build/openssl-$abi"
  [ -f "$stage$OPENSSL_PREFIX/lib/libcrypto.a" ] && return 0
  rm -rf "$src" "$stage" && mkdir -p "$src"
  tar -xzf "$OPENSSL_TGZ" -C "$src" --strip-components=1
  ( cd "$src"
    export ANDROID_NDK_ROOT="$NDK" PATH="$TC/bin:$PATH"
    ./Configure "$target" -D__ANDROID_API__=$API no-shared no-tests no-docs no-apps no-module no-engine \
      no-dso no-ui-console no-filenames --prefix="$OPENSSL_PREFIX" --openssldir="$OPENSSL_DIR" --libdir=lib \
      -fPIE "-ffile-prefix-map=$src=openssl" >/dev/null
    "$MAKE_BIN" -j"$(nproc)" build_libs >/dev/null
    "$MAKE_BIN" DESTDIR="$stage" install_dev >/dev/null
  )
}

# Configures and builds the iperf3 executable for one ABI, then strips it into jniLibs.
build() {
  local abi="$1" triple="$2" clang="$3" ossl="$4"
  local out="$ROOT/native/build/$abi"
  build_openssl "$abi" "$ossl"
  rm -rf "$out" && mkdir -p "$out"
  ( cd "$out"
    CC="$TC/bin/$clang" AR="$TC/bin/llvm-ar" RANLIB="$TC/bin/llvm-ranlib" STRIP="$TC/bin/llvm-strip" \
    CFLAGS="-O2 -fPIE -fstack-protector-strong -D_FORTIFY_SOURCE=2 -ffile-prefix-map=$IPERF_SRC=iperf -ffile-prefix-map=$out=build" \
    LDFLAGS="-pie -Wl,-z,max-page-size=16384" \
      "$IPERF_SRC/configure" --host="$triple" --disable-shared --enable-static \
        --with-openssl="$ROOT/native/build/openssl-$abi$OPENSSL_PREFIX" \
        --without-sctp --disable-profiling --disable-dependency-tracking >/dev/null
    "$MAKE_BIN" -j"$(nproc)" -C src iperf3 >/dev/null
  )
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  "$TC/bin/llvm-strip" -o "$ROOT/app/src/main/jniLibs/$abi/libiperf3.so" "$out/src/iperf3"
  echo "$abi: $(stat -c %s "$ROOT/app/src/main/jniLibs/$abi/libiperf3.so") bytes"
}

build arm64-v8a  aarch64-linux-android    aarch64-linux-android${API}-clang    android-arm64
build armeabi-v7a armv7a-linux-androideabi armv7a-linux-androideabi${API}-clang android-arm
build x86_64     x86_64-linux-android     x86_64-linux-android${API}-clang     android-x86_64
