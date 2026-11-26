#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_VERSION="8.11.1"
CMDLINE_TOOLS_REV="11076708"
TOOLS_DIR="$HOME/tools"
ANDROID_SDK_DIR="$HOME/android-sdk"

mkdir -p "$TOOLS_DIR" "$ANDROID_SDK_DIR" "$HOME/.certs"

# Strip any non-interactive early-return guard from ~/.bashrc so non-interactive shells inherit PATH & JAVA_TOOL_OPTIONS
if [ -f "$HOME/.bashrc" ]; then
  sed -i '/case \$- in/,/esac/d' "$HOME/.bashrc" 2>/dev/null || true
else
  touch "$HOME/.bashrc"
fi

cat > "$HOME/.android-env.sh" <<'EOF'
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$HOME/android-sdk"
export PATH="$HOME/.cargo/bin:$HOME/tools/gradle/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# Rebuild Java PKCS12 truststore automatically if missing or if system CA bundle changed
if [ -f /etc/ssl/certs/ca-certificates.crt ] && command -v keytool >/dev/null 2>&1; then
  if [ ! -f "$HOME/.certs/cacerts.p12" ] || [ /etc/ssl/certs/ca-certificates.crt -nt "$HOME/.certs/cacerts.p12" ]; then
    mkdir -p "$HOME/.certs"
    _cert_tmp_dir="$(mktemp -d)"
    awk -v d="$_cert_tmp_dir" '/-----BEGIN CERTIFICATE-----/{i++} i>0{print > (d "/cert-" i ".pem")}' /etc/ssl/certs/ca-certificates.crt
    rm -f "$HOME/.certs/cacerts.p12.tmp"
    for _pem in "$_cert_tmp_dir"/cert-*.pem; do
      [ -f "$_pem" ] || continue
      keytool -importcert -noprompt -trustcacerts \
        -alias "local-ca-$(basename "$_pem" .pem)" \
        -file "$_pem" \
        -keystore "$HOME/.certs/cacerts.p12.tmp" \
        -storetype PKCS12 \
        -storepass changeit >/dev/null 2>&1 || true
    done
    if [ -f "$HOME/.certs/cacerts.p12.tmp" ]; then
      mv "$HOME/.certs/cacerts.p12.tmp" "$HOME/.certs/cacerts.p12"
    fi
    rm -rf "$_cert_tmp_dir"
    unset _cert_tmp_dir _pem
  fi
fi

_focal_proxy="${HTTPS_PROXY:-${HTTP_PROXY:-}}"
if [ -n "$_focal_proxy" ] && [ -f "$HOME/.certs/cacerts.p12" ]; then
  _hp="${_focal_proxy#*://}"
  _hp="${_hp%%/*}"
  _phost="${_hp%%:*}"
  _pport="${_hp##*:}"
  export JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=${_phost} -Dhttp.proxyPort=${_pport} -Dhttps.proxyHost=${_phost} -Dhttps.proxyPort=${_pport} -Djavax.net.ssl.trustStore=$HOME/.certs/cacerts.p12 -Djavax.net.ssl.trustStorePassword=changeit"
fi
unset _focal_proxy _hp _phost _pport
EOF

grep -qxF '[ -f "$HOME/.android-env.sh" ] && . "$HOME/.android-env.sh"' "$HOME/.bashrc" \
  || echo '[ -f "$HOME/.android-env.sh" ] && . "$HOME/.android-env.sh"' >> "$HOME/.bashrc"

# shellcheck disable=SC1091
. "$HOME/.android-env.sh"

# 1. Install Gradle 8.11.1 idempotently
if [ ! -x "$TOOLS_DIR/gradle-${GRADLE_VERSION}/bin/gradle" ]; then
  _gradle_zip="$(mktemp --suffix=.zip)"
  curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o "$_gradle_zip"
  unzip -q -o "$_gradle_zip" -d "$TOOLS_DIR"
  rm -f "$_gradle_zip"
fi
ln -sfn "$TOOLS_DIR/gradle-${GRADLE_VERSION}" "$TOOLS_DIR/gradle"

# 2. Install Android SDK Command-Line Tools + Platform 35 + Build-Tools 35.0.0 idempotently
if [ ! -x "$ANDROID_SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then
  _cmdline_zip="$(mktemp --suffix=.zip)"
  _cmdline_tmp="$(mktemp -d)"
  curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_REV}_latest.zip" -o "$_cmdline_zip"
  unzip -q -o "$_cmdline_zip" -d "$_cmdline_tmp"
  mkdir -p "$ANDROID_SDK_DIR/cmdline-tools"
  rm -rf "$ANDROID_SDK_DIR/cmdline-tools/latest"
  mv "$_cmdline_tmp/cmdline-tools" "$ANDROID_SDK_DIR/cmdline-tools/latest"
  rm -rf "$_cmdline_zip" "$_cmdline_tmp"
fi

if [ ! -d "$ANDROID_SDK_DIR/platforms/android-35" ] || [ ! -d "$ANDROID_SDK_DIR/build-tools/35.0.0" ]; then
  _sdk_args=("--sdk_root=$ANDROID_SDK_DIR")
  _proxy_raw="${HTTPS_PROXY:-${HTTP_PROXY:-}}"
  if [ -n "$_proxy_raw" ]; then
    _hp="${_proxy_raw#*://}"
    _hp="${_hp%%/*}"
    _sdk_args+=("--proxy=http" "--proxy_host=${_hp%%:*}" "--proxy_port=${_hp##*:}")
  fi
  yes | "$ANDROID_SDK_DIR/cmdline-tools/latest/bin/sdkmanager" "${_sdk_args[@]}" --licenses >/dev/null || true
  "$ANDROID_SDK_DIR/cmdline-tools/latest/bin/sdkmanager" "${_sdk_args[@]}" \
    "platform-tools" \
    "platforms;android-35" \
    "build-tools;35.0.0"
fi

# 3. Install Node dependencies for desktop-extension and sync-relay idempotently
if [ -f "$REPO_ROOT/desktop-extension/package-lock.json" ] && [ ! -d "$REPO_ROOT/desktop-extension/node_modules" ]; then
  (cd "$REPO_ROOT/desktop-extension" && npm ci --no-audit --no-fund)
fi

if [ -f "$REPO_ROOT/sync-relay/package-lock.json" ] && [ ! -d "$REPO_ROOT/sync-relay/node_modules" ]; then
  (cd "$REPO_ROOT/sync-relay" && npm ci --no-audit --no-fund)
fi

gradle --version >/dev/null
