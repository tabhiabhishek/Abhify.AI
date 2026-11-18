# Abhify.AI: configuration and secrets (3.1, verified in code)

| Item | Where and how |
|---|---|
| Provider API keys | Sealed with AES-256-GCM. The key lives in the Android Keystore, alias `abhify_provider_keys_v1`, and never leaves secure hardware. The sealed blobs are stored in the app-private preferences file `abhify_providers`. Shown masked (`gsk_••••••••abcd`). |
| Account tokens | A separate Keystore alias, `abhify_session_v1`. |
| Backups | `android:allowBackup="false"`, `fullBackupContent="false"`, and data-extraction rules exclude everything, so keys, settings and databases are not in Android cloud or device-transfer backups. |
| Exports | JSON, MD and TXT chat exports never read the provider store (JVM test `syncAndExportCodeNeverTouchesProviderKeys`). Resume exports contain only resume text and results. |
| Logs and diagnostics | "Copy diagnostics" lists configured yes/no, model, problem and last error. Provider error text passes through `sanitize()`, which redacts key-like strings. |
| Prompts | Keys are sent only as the HTTP `Authorization` header to that provider. They are never placed in prompt text. |
| Configuration storage | AI mode, selected provider, fallback switches, memory profile (`memory_profile`), OCR permission (`ocr_allowed`), setup flag (`onboarded`): app preferences (non-secret). Chats, documents, resume versions: SQLite v6 (app-private, not encrypted by the app; Android's file-based encryption applies). |
| Per-device independence | No key, model path or provider is hard-coded. A new install has an empty provider store. Keys are not synced. |
| Validation | A key is checked with the selected provider's own model-list endpoint (no generation, no cost). An invalid key keeps all other settings, and the model is never deleted. |
| Model metadata | `models/state.json` plus `model_registry` hold name, version, SHA-256 and size. They never contain keys. |
| OCR privacy | ML Kit runs on the device. Images and text are not sent; Google's SDK sends usage metrics (disclosed in setup, Settings and the permission dialog). |
| Scans | Each build scans the APK and the project for Gemini, Groq, OpenRouter and Anthropic key patterns. 3.1.0: **no matches**. |
