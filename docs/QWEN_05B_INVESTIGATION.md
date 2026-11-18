# Qwen2.5 0.5B investigation and local model recommendations (2026-11-09)

## What was checked
| Step | Result |
|---|---|
| Catalog entry (Android) | `qwen2.5-0.5b-instruct-q4_k_m`, pinned Hugging Face revision `9217f5db…`. **The SHA-256 (`74a4da8c…a9db`) and size (491,400,032 bytes) match what Hugging Face serves** (`X-Linked-ETag` / `X-Linked-Size`). Downloads verify. |
| The exact file, run directly | Downloaded the pinned file (hash verified) and ran it in Ollama 0.40.13 (llama.cpp-based) with the app's ChatML format, greedy. **It works.** Self-test prompt → "Okay". 12 + 30 → "42". Correct definition. Multi-turn recall correct. 435 MB in memory, 240–270 tokens/s on the dev PC GPU. |
| App self-test | Lenient (any non-empty answer passes), and it runs on the CPU. Not the cause. |
| Template / tokens | The engine uses the model's own chat template (ChatML for both sizes) and stops on llama.cpp's end-of-generation tokens; same for 1.5B and 0.5B. |
| GPU path | **Root cause found:** the "use GPU" result of **Test GPU** was stored as one phone-wide switch (`gpu_enabled`). Test GPU only checks the model that is active at the time (usually the 1.5B). After it passed, **every** model ran on Vulkan, including the 0.5B, which was never checked there (the catalog marks Vulkan as not validated for both). The install self-test runs on the CPU, so the 0.5B passed install, then failed or gave bad output in chat. The CPU fallback only triggers on a load error, not on bad output. |
| Phone | **Not tested:** no phone or emulator was connected, so the exact failure on your phone couldn't be observed. |

## Fix (Android, `model/GpuPolicy.kt`, `AppGraph.gpuEnabled`)
- **Per-model GPU:** GPU use is validated per model. A model runs on the GPU only after it passed Test GPU itself; otherwise it uses the CPU path its self-test ran on.
- **Upgrade:** the old phone-wide switch keeps meaning the 1.5B (if installed), so the working 1.5B behaves exactly as before. The 0.5B starts on the CPU.
- **Picker status:**
  - a model that failed to start shows the real error with **Try again**;
  - a catalog model that isn't on the phone shows **Get it**, which opens AI models (the download is confirmed there; nothing downloads from the picker);
  - "In use" appears only for the active model.
- **Tests:** `NavigationAndGpuTest.gpuIsValidatedPerModel`.

**Second likely cause (no code change needed):** in the chat picker, the 0.5B is listed from the catalog and shown as **not installed** until you download it in AI models. Before this build that row was just greyed out, which can look like "doesn't work". It now has the **Get it** button.

**To verify on the phone:**
1. Install the build.
2. AI models → download Qwen2.5 0.5B (468 MB) if it isn't there.
3. Switch to it from the chat chip and ask a question.
4. Check that the Models screen shows "CPU" for it.
5. Optional: run Test GPU while the 0.5B is active to validate it on your GPU.

## Recommendations (only measured or published facts)
| Model | Where | Facts |
|---|---|---|
| Qwen2.5 0.5B Q4_K_M | Android catalog | 468 MB download. Verified working (above). Catalog minimum 2.7 GB RAM. For 3–4 GB phones or fast short answers. |
| Qwen2.5 1.5B Q4_K_M | Android catalog | 1.07 GB. Works on your phone (your report). Catalog minimum 5.5 GB RAM. Default for 6 GB+ phones. |
| qwen2.5 (7B) Q4_K_M | Desktop (installed) | Measured on the dev PC (RTX A2000 12 GB): 84–91 tokens/s, 4.4 GB VRAM. |
| llama3.2 (3B) Q4_K_M | Desktop (installed) | Measured: 2.4 GB VRAM. Answered correctly in the switching tests. |
| Gemma 3 1B / 4B, Llama 3.2 1B, Qwen2.5 3B | Not installed | **Not evaluated:** they would need a download and real tests on your phone and PC first. Adding one to the Android catalog also needs a pinned revision, a SHA-256 and a phone test. Each is a download for you to approve. |

Model choice stays yours; nothing is switched or downloaded automatically.
