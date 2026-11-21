#!/usr/bin/env bash
# Compiles the pure parts of the app (Core, Cookies, Http, Twitter, PixivScanner, Previews, Session, ArtistUrls, Similarity) with the Kotlin compiler that ships inside Gradle
# and runs the checks. The okhttp/org.json stand-ins in core-test/stubs are for this test only, not the app.
# Usage: core-test/run.sh [path-to-gradle-lib-dir]   (default: /opt/gradle/lib)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../app/src/main/java/com/dlh/helper"
LIB="${1:-/opt/gradle/lib}"
OUT="$(mktemp -d)"
STD="$LIB/kotlin-stdlib-2.0.21.jar"
COR="$LIB/kotlinx-coroutines-core-jvm-1.6.4.jar"
CP="$LIB/kotlin-compiler-embeddable-2.0.21.jar:$STD:$LIB/kotlin-reflect-2.0.21.jar:$LIB/kotlin-script-runtime-2.0.21.jar:$LIB/kotlin-daemon-embeddable-2.0.21.jar:$LIB/trove4j-1.0.20200330.jar:$LIB/annotations-24.0.1.jar:$COR"
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -cp "$STD:$COR" -d "$OUT" \
  "$HERE/stubs/Json.kt" "$HERE/stubs/OkHttpStubs.kt" \
  "$SRC/Core.kt" "$SRC/Cookies.kt" "$SRC/Http.kt" "$SRC/Twitter.kt" "$SRC/PixivScanner.kt" "$SRC/Previews.kt" "$SRC/Session.kt" "$SRC/ArtistUrls.kt" "$SRC/Similarity.kt" \
  "$HERE/CoreTest.kt" "$HERE/XTest.kt" "$HERE/PixivTest.kt" "$HERE/SimTest.kt"
java -cp "$OUT:$STD:$COR" com.dlh.helper.CoreTestKt
java -cp "$OUT:$STD:$COR" com.dlh.helper.XTestKt
java -cp "$OUT:$STD:$COR" com.dlh.helper.PixivTestKt
java -cp "$OUT:$STD:$COR" com.dlh.helper.SimTestKt
