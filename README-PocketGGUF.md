# PocketGGUF

PocketGGUF is an Android app project intended to run user-selected GGUF language models locally with llama.cpp and expose an OpenAI-compatible API on the local network.

## Project status

This repository currently contains the initial Android Compose UI and a GitHub Actions workflow that builds an installable debug-signed APK. The model runtime, GGUF import/download manager, authenticated API server, and background service are not implemented yet.

## Build APK

1. Open the repository's **Actions** tab.
2. Select **PocketGGUF Android APK**.
3. Run the workflow manually with **Run workflow**, or push changes to a `localmind/**` branch.
4. Download the artifact named **PocketGGUF-Android**.
5. The APK inside is named `PocketGGUF-Android.apk`.

The APK is debug-signed for testing and direct installation. It is not a Play Store release build.

## Development plan

1. Confirm the initial APK builds and installs.
2. Integrate the llama.cpp Android native runtime.
3. Implement GGUF import, model storage, and Hugging Face downloads.
4. Implement OpenAI-compatible LAN endpoints and API-key authentication.
5. Add reliable background server operation and test on physical devices.

The app's display name and launcher icon are PocketGGUF. The UI uses a true-black OLED-style background.
