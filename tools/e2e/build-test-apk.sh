#!/usr/bin/env bash
# build-test-apk.sh - build a Grocery Sync APK for emulator checks against the mock web app.
#
# Usage: build-test-apk.sh [qa|debug|release] [--src DIR] [--work DIR] [--clean] [-- <gradle args>]
#   qa (default)       ./gradlew assembleQa in the project itself: the release build (same R8 shrinking and
#                      rules) with its own app id com.grocerysync.app.qa, the debug key and cleartext HTTP to
#                      the mock (src/qa). It installs next to the real app and can never replace it: the
#                      test build of the release code to use.
#   debug              ./gradlew assembleDebug in the project itself.
#   release            only when the real app id or the release signing itself is what you check:
#                      assembleRelease in a scratch copy of android/ that also gets the debug network security
#                      config (src/release/AndroidManifest.xml + res/xml/network_security_config.xml, copied
#                      from src/debug), so the R8-minified release build can talk to the mock over plain HTTP
#                      (localhost, 127.0.0.1 and 10.0.2.2 only). Signed like a real release when the signing
#                      file exists (see README, "Option C"); without it the APK is unsigned and cannot be
#                      installed. TEST BUILD ONLY: never give it to a phone.
#   --src DIR          the android/ folder to build (default: android/ of the checkout, see lib.sh GS_REPO)
#   --work DIR         release only: the scratch copy (default $GS_E2E_HOME/build/release); kept between runs
#                      so Gradle builds incrementally. Its sources are replaced from --src on every run.
#   --clean            release only: delete the scratch copy first (full rebuild)
# Prints the APK path (copied to $GS_E2E_HOME/out/apk/app-<variant>-e2e.apk) on stdout, and its aapt2
# badging (package, versionCode/Name, SDK levels, debuggable), whether cleartext to the mock is allowed and
# the signing certificate's SHA-256 on stderr.
# Needs JAVA_HOME (JDK 17+; Android Studio's jbr is used when JAVA_HOME is not set). Never changes a source
# file in --src (qa and debug only add the usual build outputs under app/build). It only builds: installing
# is app.sh's job, on one emulator.
set -uo pipefail
. "$(dirname "$0")/lib.sh"

usage() { sed -n '2,/^set -uo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit "${1:-0}"; }

variant=qa
src=""
work=""
clean=""
gradle_extra=()
while [ $# -gt 0 ]; do
  case $1 in
    release | qa | debug) variant=$1; shift;;
    --src) src=${2:?}; shift 2;;
    --work) work=${2:?}; shift 2;;
    --clean) clean=1; shift;;
    --) shift; gradle_extra=("$@"); break;;
    -h | --help | help) usage 0;;
    *) gs_log "unknown option: $1"; usage 2;;
  esac
done
if [ "$variant" != release ] && { [ -n "$work" ] || [ -n "$clean" ]; }; then
  gs_die "--work and --clean are for release builds only ($variant builds in the project itself)"
fi

src=$(gs_path "${src:-$(gs_repo)/android}")
[ -f "$src/gradlew" ] && [ -d "$src/app/src/main" ] || gs_die "$src is not the android/ folder of Grocery Sync"
if [ "$variant" = qa ] && ! grep -q 'create("qa")' "$src/app/build.gradle.kts" 2>/dev/null; then
  gs_die "$src/app/build.gradle.kts has no qa build type: build release or debug instead"
fi
gs_init_sdk
java_home=$(gs_java_home) || exit 1
java="$java_home/bin/java"

# ---- the tree to build
if [ "$variant" != release ]; then
  dir=$src
  gs_log "building $variant in the project: $dir"
else
  dir=$(gs_path "${work:-$GS_E2E_HOME/build/$variant}")
  case "$dir/" in "$src/"*) gs_die "--work must not be inside --src";; esac
  [ -n "$clean" ] && rm -rf "$dir"
  mkdir -p "$dir/app"
  # Drop the old sources but keep Gradle's outputs and caches (incremental builds).
  find "$dir" -mindepth 1 -maxdepth 1 ! -name .gradle ! -name .kotlin ! -name build ! -name app -exec rm -rf {} +
  find "$dir/app" -mindepth 1 -maxdepth 1 ! -name build -exec rm -rf {} +
  tar -C "$src" --exclude=./.gradle --exclude=./.kotlin --exclude=./build --exclude=./app/build --exclude=./.idea \
    --exclude='*.iml' --exclude=./captures --exclude=./.cxx -cf - . | tar -C "$dir" -xf - || gs_die "copying $src failed"
  echo "Scratch copy made by build-test-apk.sh from $src; it is replaced on every run." >"$dir/E2E-SCRATCH-COPY.txt"
  gs_log "building $variant in a scratch copy: $dir"
  rel="$dir/app/src/release"
  if [ -f "$rel/AndroidManifest.xml" ] && ! grep -q networkSecurityConfig "$rel/AndroidManifest.xml"; then
    gs_die "$src/app/src/release/AndroidManifest.xml exists: merge the network config into it by hand"
  fi
  mkdir -p "$rel/res/xml"
  if [ -f "$src/app/src/debug/AndroidManifest.xml" ] && [ -f "$src/app/src/debug/res/xml/network_security_config.xml" ]; then
    cp "$src/app/src/debug/AndroidManifest.xml" "$rel/AndroidManifest.xml"
    cp "$src/app/src/debug/res/xml/network_security_config.xml" "$rel/res/xml/network_security_config.xml"
  else
    cat >"$rel/AndroidManifest.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<!-- Added by build-test-apk.sh (test builds only): cleartext HTTP to the local mock web app. -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:networkSecurityConfig="@xml/network_security_config" />
</manifest>
EOF
    cat >"$rel/res/xml/network_security_config.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">localhost</domain>
        <domain includeSubdomains="false">127.0.0.1</domain>
        <domain includeSubdomains="false">10.0.2.2</domain>
    </domain-config>
</network-security-config>
EOF
  fi
fi

# ---- build
case $variant in release) task=assembleRelease;; qa) task=assembleQa;; debug) task=assembleDebug;; esac
mkdir -p "$GS_OUT/logs" "$GS_OUT/apk"
log="$GS_OUT/logs/build-$variant.log"
apk_dir="$dir/app/build/outputs/apk/$variant"
rm -f "$apk_dir"/*.apk 2>/dev/null
gs_log "./gradlew $task (log: $log)"
start=$SECONDS
gs_gradle "$dir" "$log" "$task" "${gradle_extra[@]}"
rc=$?
if [ $rc -ne 0 ]; then
  grep -E '^(e:|w:|FAILURE|\* What went wrong|  >)|error:' "$log" | head -n 30 >&2
  tail -n 15 "$log" >&2
  gs_die "build failed (exit $rc); full log: $log"
fi
grep -E '^BUILD SUCCESSFUL' "$log" >&2
grep -i 'release APK will be unsigned' "$log" >&2

apk=$(ls -t "$apk_dir"/*.apk 2>/dev/null | head -n 1)
[ -n "$apk" ] || gs_die "no APK in $apk_dir"
out="$GS_OUT/apk/app-$variant-e2e.apk"
cp "$apk" "$out" || gs_die "cannot copy $apk"
case $apk in *-unsigned.apk) gs_log "WARNING: the APK is unsigned (no signing file): adb install will refuse it";; esac

# ---- what was built
bt=$(ls -d "$GS_SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -n 1)
bt=${bt%/}
aapt2="$bt/aapt2"
[ -f "$aapt2.exe" ] && aapt2="$aapt2.exe"
{
  echo "---- $(basename "$apk") -> $(gs_path "$out")  ($(( $(wc -c <"$out") / 1024 )) KiB, built in $((SECONDS - start)) s)"
  if [ -f "$aapt2" ]; then
    "$aapt2" dump badging "$out" 2>/dev/null |
      grep -E "^(package:|minSdkVersion:|sdkVersion:|targetSdkVersion:|application-label:|application-debuggable|launchable-activity:)" |
      sed -E "s/ (platformBuildVersion|compileSdkVersion)[A-Za-z]*='[^']*'//g"
    if "$aapt2" dump xmltree --file AndroidManifest.xml "$out" 2>/dev/null | grep networkSecurityConfig >/dev/null; then
      echo "network security config: present (cleartext to localhost / 127.0.0.1 / 10.0.2.2)"
    else
      echo "network security config: none (HTTPS only: cannot reach the mock over http)"
    fi
  else
    echo "(no aapt2 in $bt: badging skipped)"
  fi
  if [ -f "$bt/lib/apksigner.jar" ]; then
    certs=$("$java" -jar "$bt/lib/apksigner.jar" verify --print-certs "$out" 2>&1 | tr -d '\r')
    if grep -q 'certificate SHA-256' <<<"$certs"; then
      printf '%s\n' "$certs" | grep -E 'certificate (DN|SHA-256 digest)' | sed 's/^Signer #1 //'
    else
      echo "signature: $(printf '%s\n' "$certs" | head -n 1)"
    fi
  fi
} >&2
gs_path "$out"
