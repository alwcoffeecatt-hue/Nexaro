# Nexora for Android

Nexora is an Android WebView-based browser with a local DNS-over-HTTPS proxy.

## GitHub + Termux workflow

Clone the repository in Termux, make changes, then push to GitHub:

```bash
git clone https://github.com/YOUR_USERNAME/nexora.git
cd nexora
# edit files here

git add .
git commit -m "Update Nexora"
git push
```

Every push to `main` runs `.github/workflows/android.yml` and produces a debug APK artifact.

## Download the APK

On GitHub: **Actions → Build Nexora APK → latest successful run → Artifacts → Nexora-debug-apk**.

## Local build (optional)

If a local Android SDK is configured:

```bash
./gradlew assembleDebug
```

The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Important

Do not commit signing keys, passwords, API keys, or other secrets. The included workflow creates a debug APK. A production release should use a private signing key stored as a GitHub Actions secret.
