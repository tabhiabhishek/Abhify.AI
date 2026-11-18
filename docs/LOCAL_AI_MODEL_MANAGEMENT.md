# Phase 3: local AI and model management

**Status:** done in code (4.4.0). **Phone verification:** pending.

- **Runtime:** llama.cpp (pinned commit) through JNI. One model at a time.
- **Models:** Qwen2.5 1.5B / 0.5B Instruct (Q4_K_M GGUF), from pinned Hugging Face revisions with SHA-256 (`model/ModelCatalog`).
- **Settings → Models on this phone:**
  - catalog, installed models, sizes, selected model, status and a self-test;
  - download with progress (foreground service), resume, cancel and checksum verification;
  - delete (asks first). Models are never deleted automatically.
- **Device adaptation:** `device/Memory` profiles (low / balanced / quality) set context and threads. `HeavyGate` unloads the model during OCR so the phone doesn't run out of RAM.
- **Creativity (4.4):** Settings → Answer style → Creativity. It sets the phone model's temperature:

  | Setting | Temperature |
  |---|---|
  | Precise | 0.2 |
  | **Balanced** (the previous fixed value, still the default) | **0.3** |
  | Creative | 0.7 |

  Online providers keep their own settings.
- **Offline:** chat works without internet once a model is installed. There's no mandatory server or cloud.
- **Not applicable:** "Ollama status" is a desktop feature.
- **Not exposed on purpose:** raw context size and sampling knobs. The memory profiles choose safe values per device.

**Tests:** `V44Test.personalizationDefaultsKeepTheOldBehaviour`, plus the model catalog and downloader tests.
