# Automatic setup (2.0.0-dev.6)

## What you do
1. Run the installer (`Abhify.AI-Setup-<version>.exe`). It installs Abhify.AI **with its own Python runtime and all libraries**: no Python, CUDA or packages to install, and no administrator rights. Nothing is downloaded by the installer.
2. Start Abhify.AI. The setup window opens. Click **⚡ See what will be installed**.
3. Check the list (components, download size, disk space) and click **Start automatic setup**. That one confirmation covers everything listed; nothing is downloaded before it.
4. Wait. You can **Pause**, **Cancel**, and later **Resume / retry**; nothing already downloaded is lost.
5. When it says **✅ Setup complete**, the AI has been installed **and tested**. From then on Abhify.AI works without internet (see `OFFLINE_OPERATION.md`).

## What happens, step by step (`backend/setup_manager.py`)
| Step | What | From where | Size (approx.) | Verified by |
|---|---|---|---|---|
| 1 | Detect this computer: OS, CPU, RAM, disk, NVIDIA GPU and VRAM, CUDA for Whisper, installed Ollama and models | local | — | — |
| 2 | Choose the profile that fits: **Minimal** (`qwen2.5:1.5b`, CPU-friendly), **Balanced** (`qwen2.5` 7B: 6 GB+ GPU or 16 GB RAM), **Quality** (`qwen2.5:14b`: ~11 GB+ VRAM) + `nomic-embed-text` for document search | local | — | — |
| 3 | **Ollama runtime**, only if it isn't installed: official installer → silent per-user install → started | `https://ollama.com/download/OllamaSetup.exe` | ~1.6 GB download, ~5 GB installed (estimate) | Windows Authenticode signature must be **Valid** and from **Ollama Inc.**; otherwise the file is deleted and **never run** |
| 4 | **Models** that aren't installed yet | Ollama registry, through your local Ollama | Minimal ~1.3 GB · Balanced ~5 GB · Quality ~9.3 GB | Ollama checks every layer's SHA-256; Abhify.AI requires Ollama's final "success" **and** the model in Ollama's list |
| 5 | **Validation:** a real answer from the chat model and a real embedding vector | local | — | Setup isn't marked complete otherwise |
| 6 | Record versions, digests, sizes and paths in `config\components.json` | local | — | — |

**Already installed? Skipped.** A second run with everything in place downloads nothing.

**GPU or CPU.** Ollama uses a supported NVIDIA GPU automatically and falls back to the CPU otherwise. The profile is chosen from detected RAM and VRAM. A detected GPU alone isn't trusted: setup only finishes after a real answer (step 5). On the dev PC the 7B model ran **100% on the GPU** (verified with `ollama ps`). You can change the profile later in Settings → System & data → Models & setup; that never deletes a working model.

**Disk space.** Checked before anything is downloaded, both on the drive with Ollama's models folder and on the drive with Abhify.AI's data. Not enough space: setup refuses with the exact numbers.

## No internet on first launch
The plan lists what is missing and says that internet is needed once. Start is disabled until you're online, then press **Re-check**. Everything that doesn't need the AI (saved quizzes and news, settings, history) still opens.

## Failures and recovery
| Problem | What you see | What to do |
|---|---|---|
| Connection dropped during a model download | "The connection to Ollama was interrupted… Press Download again to resume" | Resume / retry: continues where it stopped |
| Ollama installer download interrupted | Stopped | Resume / retry: the `.part` file continues (HTTP Range) |
| Installer signature not valid | "…not validly signed by Ollama Inc. … deleted and not run" | Check your network (proxy or captive portal?), retry; or install Ollama yourself from ollama.com |
| Ollama doesn't start | "Ollama didn't start…" | Start the Ollama app from the Start Menu, then retry |
| Model can't run (for example, not enough memory) | Ollama's own reason, e.g. "model requires more system memory…" | Choose a smaller profile in Models & setup |
| Disk full | Exact sizes needed and free | Free space or choose a smaller profile |

Where things are stored: `DATA_AND_MIGRATION.md` → *Components, models and storage*.

## Not included in automatic setup (known limitations)
- **Whisper (private voice mode):** its model (~145 MB for `base`) is downloaded from Hugging Face the **first time** you use private voice mode, so that first use needs internet. Not prefetched yet.
- **macOS/Linux:** Ollama must be installed by hand (the plan says so); everything else works the same.
- **Tested only with stand-ins and in Windows Sandbox:** the full automatic run (real Ollama installer, real downloads) has been exercised in Sandbox by the dev.4/dev.5 model tests through the same steps, but **not** yet through this new one-click flow on real hardware or in Sandbox. See `UPDATE_RECOVERY_TEST.md` §A.
