# Chat model selection (Android 4.5.0, desktop/web 2.0.0-dev.8)

## 1. Audit: why chat always answered with Qwen 2.5

The Android app and the desktop/web app use **different inference stacks**, so they are described separately.

### Android (on-device llama.cpp)
| Piece | Where | Finding |
|---|---|---|
| Model registry | `assets/model_catalog.json` → `model/ModelCatalog.kt` | Two real, pinned GGUF models: **Qwen2.5 1.5B Instruct** (1,065 MB, ≥ 5.5 GB RAM) and **Qwen2.5 0.5B Instruct** (468 MB, ≥ 2.7 GB RAM). |
| Installed models | `model/ModelStore.kt` (`state.json`: `models`, `active`) | Several models can be installed. `install()` makes the newly installed model active. |
| Switching | `ModelStore.setActive(id)` | **Existed but nothing in the UI called it.** |
| Loading | `AppGraph.ensureModel()` | It loads `store.active()`. When the active file differs from the loaded one, it unloads first, so only one model is ever in memory. It checks free memory (`ModelSelector.loadBlocker`) and falls back from GPU to CPU. |
| Online models | `providers/Providers.kt`, `AiMode` chip | Gemini, Groq and OpenRouter, with the user's own key and a model per provider. The AI mode chip (Auto / Online / Phone) was in the header. |
| Answer attribution | `Source(kind, model)`, stored with every message | Each answer already records the model that wrote it. |

**Root cause.** The last installed model is always the active one, and no screen called `setActive`. The Models screen even offered **"Download" again** for a model that was already installed but not active.

### Desktop/web (Ollama)
| Piece | Where | Finding |
|---|---|---|
| Model in use | `system_service.active_models()` | `LLM_MODEL` env var, else `settings.json → models.llm`. Setup profiles set it to `qwen2.5:1.5b`, `qwen2.5` (7B) or `qwen2.5:14b`. |
| LLM client | `agent.get_llm()` (cached) | Always built with `active_models()["llm"]`. |
| Chat endpoint | `POST /chat` | Fields `message` and `thread_id`. **No model field**, so no per-chat choice was possible. |
| Installed models | Ollama `/api/tags` (used by setup and diagnostics) | Lists chat **and** embedding models (e.g. `nomic-embed-text`). |
| Persistence | SQLite `messages` (schema v5) | No model column. |
| Browser-only mode (Abhify Lite) | `frontend/ondevice.js` | WebLLM chooses Qwen 2.5 1.5B or Llama 3.2 1B from the device's memory. |

**Root cause.** The backend had a single, fixed default model, and `/chat` had no way to ask for another one.

## 2. What was built

### Android
- **Model chip above the chat box (and on Home).** It shows the model that answers next: `Qwen2.5 1.5B Instruct`, `Groq · <model>`, or `Auto`. A spinner shows while a model loads; it turns red if the model couldn't start.
- **Model sheet** (bottom sheet). It lists only real options:
  - **Auto**;
  - every **catalog** model: installed ones are selectable; others are marked "Not installed. Download it in AI models." and are never downloaded from here;
  - every **online provider configured with a key**, only when online AI is allowed in Privacy (plus the Abhify server when signed in).

  It has **Manage models** and **AI providers** links.
- **Switching** (`AppViewModel.pickModel`):
  1. It is refused while an answer is being written.
  2. `store.setActive(id)`, then unload the old model and load the new one straight away, so a failure shows in the sheet, not on the next send.
  3. Picking a phone model sets the AI mode to *On this phone*; picking a provider sets *Online*. Nothing switches to online AI on its own.
- **Default model.** The active phone model is saved, so it is also the default for new chats. The Models screen says so, lists **"Also installed on this phone"** with **Use for chat** and **Remove**, and no longer offers to re-download an installed model.
- **History.** Earlier answers keep their stored model label (`Source`). Nothing is regenerated.
- **Limitation (no database change).** The choice applies to the next messages **in every chat**. It isn't stored per conversation, because that needs a new column (a schema change needing approval).

### Desktop/web
- `GET /models` (PC only): the chat models installed in Ollama, from `/api/tags`, with embedding models left out. It also returns the default, whether the default is installed, and whether `LLM_MODEL` locks it.
- `POST /chat` takes an optional `model`. The backend checks it is an installed chat model; otherwise it answers **400** with a clear message, and **503** if Ollama is down. It never substitutes another model. The answer reports `model`.
- `PUT /models/default` (PC only) saves `settings.json → models.llm` (the embedding model is unchanged) and rebuilds the cached LLM clients. It answers **409** when `LLM_MODEL` is set.
- **Chat UI.** A model chip above the composer opens a keyboard-accessible list (arrows, Enter, Esc).
  - **States:** loading, Ollama not reachable (with Try again), no chat model installed, the chosen model no longer installed, phone (*computer's default model*) and on-device mode (*the device chooses*).
  - **Persistence:** the choice is remembered **per conversation in this browser** (`abhify_thread_models`), and a new chat starts on the default.
  - **Labels:** each new answer shows the model that wrote it.
- **Settings → Default chat model** (on the computer only).
- **Limitation (no schema change).** The model isn't stored with messages in SQLite, so answers loaded from history have no model label. Paired phones can't choose a model; they use the computer's default.

## 3. Tests
| Suite | What it proves |
|---|---|
| Android `ModelPickerTest` (5) | The list comes from the real catalog. Installed/not-installed and the active entry are right. Online entries appear only for configured providers and only when allowed. Empty states are honest. `pickModel` really calls `setActive`, unloads and loads, never downloads, and never switches mid-answer. `ensureModel` loads `store.active()`. |
| Android `ModelSwitchStoreTest` (2) | `setActive` switches between installed models, persists, refuses a missing model and removes nothing. Per-message labels are kept. |
| Backend `test_chat_models.py` (8) | The list hides embedding models. The chosen model reaches the agent. Unknown or embedding models get 400 with no answer. The default path is unchanged. The default persists and rebuilds the LLM. The env lock gives 409. Ollama down gives an empty list and 503. There is one cached client per model. |
| PWA `test_model_chip_is_honest_about_the_model_list` | Runs the real `modelChipView` from `app.js` for every state. |

## 4. Live validation (2026-11-09)
### Desktop/web: done
Real Ollama 0.40.13 on the dev PC (RTX A2000 12 GB) with two installed chat models, `llama3.2:latest` (3.2B) and `qwen2.5:latest` (7.6B), plus `nomic-embed-text`. The backend ran on an isolated, throw-away data folder.

The proof of which model ran is **Ollama's own `/api/ps`**, not the app's label. Script: `live_models.py`.

| Check | Result |
|---|---|
| `GET /models` | Exactly the two chat models; the embedding model is left out. |
| Chat with `llama3.2:latest` (everything unloaded first) | Answer labelled `llama3.2:latest`; Ollama resident: **only `llama3.2:latest`**. |
| Chat with `qwen2.5:latest` | Answer labelled `qwen2.5:latest`; resident: **only `qwen2.5:latest`**. |
| No `model` field | The default (`qwen2.5`) is used and loaded. |
| `mistral:7b` (not installed) / `nomic-embed-text` | **400** with a clear message; nothing loaded, no answer. |
| `PUT /models/default` → `llama3.2:latest` | The next chat without a model uses llama3.2. **It persists across a backend restart.** |
| History (`/conversations/{id}`) | Messages have no model field, so the UI shows no label for reloaded answers (no false attribution). |
| Back-to-back switch | **Found:** Ollama kept the previous model for 5 minutes, so **both** were resident (4.4 + 2.4 GB VRAM). **Fixed:** the backend now releases the previous chat model on a switch. **Re-tested:** after each switch only the new model is resident. |

**Browser end-to-end:** real Edge driven over the DevTools protocol against that backend, script `web_e2e.mjs`. **25/25 checks passed:**
- Home is the default page, filled from real data;
- system/light/dark themes and the toggle;
- the chip shows the default; the list is the real one;
- the keyboard works (focus moves into the list; ArrowDown, Escape back to the chip);
- picking qwen2.5 then sending gives an answer tagged `qwen2.5:latest`, and Ollama has `qwen2.5:latest` resident;
- after a reload, the chat's choice is restored and reloaded answers carry no false tag;
- a new chat starts on the default;
- the Settings default-model list is correct;
- Memory and System open as full pages beside the sidebar, one at a time; a tab click leaves them;
- the sidebar collapses to 76 px, and becomes an icon rail at 820 px;
- at 390 px there is no sideways scroll, tap targets are at least 36 px, and Memory opens as a sheet;
- reduced motion turns off the reveal.

### Android: BLOCKED
No phone was connected (`adb devices` lists none) and there is no emulator, so the following were **not tested**:
- install over 4.4.0;
- switching between Qwen2.5 1.5B and 0.5B;
- unload and reload;
- answers labelled with the right model.

The logic is covered by JVM tests only. The APK itself was verified: SHA-256, package, version, debug certificate identical to 4.4.0, permissions identical.

**Not yet verified on hardware:** switching on a real phone (it needs a second installed model, e.g. the 0.5B, ~468 MB), and desktop switching against a live Ollama with two chat models.
