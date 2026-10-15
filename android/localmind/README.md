# LocalMind Android

LocalMind is being rebuilt from a clean app layer inside this llama.cpp repository. The existing llama.cpp sources are left in place.

## Target

- Samsung Galaxy A50 (SM-A505F), Android 11, ARM64, 4 GB RAM
- Import GGUF models from device storage, then support downloads from Hugging Face
- Run inference locally through llama.cpp
- Provide an OpenAI-compatible LAN API with API-key authentication
- OLED black theme and a modern Material 3-based interface
- Build APKs with GitHub Actions rather than Android Studio on Windows 7

## Rebuild stages

1. Foundation: compile and install a minimal Android app; validate the APK artifact.
2. Model management: import/select/delete GGUF files using Android's Storage Access Framework.
3. Native inference: integrate llama.cpp for ARM64 and run a small local GGUF model.
4. LAN API: implement GET /v1/models, POST /v1/chat/completions including SSE streaming, /health, and API-key authentication.
5. Reliability and polish: foreground service, server controls, settings, metrics, and UI refinement.

No PIN or biometric lock is planned. Each stage should be built and checked before moving on.
