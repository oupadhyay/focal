# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability in Focal (Android app, Rust JNI adblock engine, MV3 Desktop Extension, or E2EE Sync Relay), please report it responsibly via **[GitHub Security Advisories](https://github.com/oupadhyay/focal/security/advisories/new)** on `oupadhyay/focal` rather than opening a public issue.

Please include:
- A clear description of the issue and affected component/version.
- Steps to reproduce or a minimal proof of concept.
- Potential impact (e.g., Private Aperture data leak, E2EE bypass, intent hijacking, native memory safety).

---

## Security Architecture Highlights

### 1. In-App Self-Update Verification
When Focal updates itself in-app from GitHub Releases (`FocalReleaseUpdater`):
- **HTTPS-only transport**: Every API call, asset download, and redirect hop strictly enforces `https://`.
- **SHA-256 verification**: Downloads are hashed on-the-fly and verified against the release's `SHA256SUMS.txt` manifest before staging.
- **On-device package & signature check**: Before committing to Android's `PackageInstaller.Session`, Focal inspects the downloaded APK via `PackageManager.getPackageArchiveInfo` to confirm `packageName == "app.focal"`, monotonic `longVersionCode > installedVersionCode`, and a matching signing certificate digest.

### 2. Zero-Account E2EE Continuity (`Focal Bridge`)
- Pairing secrets are 256-bit CSPRNG values (`focal-pair://v1?secret=<base64url>`) verified out-of-band via an explicit 4-part Short Authentication String (SAS) confirmation modal before joining any relay room.
- Session keys and opaque room IDs are derived via `HKDF-SHA256`.
- Every message is encrypted with `AES-256-GCM` bound to `AAD = roomId` with per-sender monotonic anti-replay counters.
- Cleartext `ws://` / `http://` relays are rejected; Android stores the pairing secret sealed under `AndroidKeyStore`.

### 3. Rust JNI FFI Safety
- Every JNI entrypoint in [`core/adblock-rust/rust/src/jni_bridge.rs`](core/adblock-rust/rust/src/jni_bridge.rs) wraps Rust execution in `std::panic::catch_unwind` with `panic = "unwind"` so native panics never abort the Android process across the C-ABI boundary.

### 4. WebView Hardening
- `allowFileAccess = false`, `allowContentAccess = false`, `allowFileAccessFromFileURLs = false`, and `allowUniversalAccessFromFileURLs = false` are enforced on every WebView instance.
- Private Aperture (`◎`) uses an isolated ephemeral WebView profile (`focal_ephemeral`) with zero Room/disk persistence.

---

## Important Note on the Repository Signing Keystore (`app/focal-dev.keystore`)

To enable reproducible CI builds and seamless in-place upgrades across community/dev releases, this repository intentionally includes a **public deterministic development signing keystore** at `app/focal-dev.keystore`:

- **Certificate DN**: `CN=Focal Dev Debug, O=Focal Browser, C=US`
- **SHA-256 Fingerprint**: `1376db20cd4ca10c52d0a50dd6e078fc1b8c30f6145f82ed3ef40c3e03203b1f`

Because this dev keystore is public in the repository:
- **Official builds** from `oupadhyay/focal` GitHub Releases should only be installed directly from [`github.com/oupadhyay/focal/releases`](https://github.com/oupadhyay/focal/releases) (or via Focal's verified in-app updater).
- **Production forks / Play Store / F-Droid maintainers** should generate and inject their own private release signing keystore (never committed to git — `*.jks` and `*.keystore` are ignored by `.gitignore` except `app/focal-dev.keystore`).
