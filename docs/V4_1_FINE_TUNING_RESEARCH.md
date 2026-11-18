# v4.1: opt-in fine-tuning research

**Research only.** Nothing was trained or downloaded for this phase, and nothing in the app trains anything. The Qwen2.5 weights are unchanged.

**Code added:** pure dataset-safety and evaluation-gate logic (`training/DatasetPolicy.kt`) with tests. There is no UI, no export, no upload and no training.

## 1. What exists today
| Item | Fact (checked) |
|---|---|
| Phone model | Qwen2.5-1.5B-Instruct Q4_K_M GGUF, **1,117,320,736 bytes**; Qwen2.5-0.5B-Instruct Q4_K_M, **491,400,032 bytes**. Apache-2.0. Run by llama.cpp through JNI with **one** model and context. |
| Phone | Owner's Redmi K50i, Android 14, 8 GB RAM (the app is device-adaptive: memory profiles and HeavyGate) |
| Dev PC | **NVIDIA RTX A2000 12 GB** (driver 581.42), Intel Xeon E-2288G, **15.8 GB RAM** |
| Personalisation without training | Personal memory (relevant-only), RAG over documents with citations, custom instructions, personalities |
| Dataset tooling | `DatasetPolicy` (v4.1): validation, JSONL export format, manifest and versioning, activation gate. Pure functions; nothing calls them automatically. |

**Personal memory and RAG do not change model weights.** They add selected text to the prompt at answer time.

## 2. Where could training run?
| Option | Feasibility | Notes |
|---|---|---|
| **On the phone** | **Not practical** | Training needs the higher-precision weights (≈3.1 GB in fp16 for 1.5B parameters), gradients, optimizer state and activations. That exceeds what an 8 GB phone can spare next to Android. It would also overheat the phone and drain the battery for hours. The quantized Q4 GGUF can't be trained directly. llama.cpp's training code is experimental and isn't exposed by Abhify's JNI. |
| **Desktop GPU (dev PC)** | **Feasible for a pilot** (estimate, not measured) | QLoRA (4-bit base + LoRA adapters) on a 1.5B model is commonly run in under 8 GB of VRAM with gradient checkpointing; plain LoRA in bf16 (~3.1 GB of weights) also fits in 12 GB. A pilot of 1,000 examples × 3 epochs × ~512 tokens (≈1.5 M tokens) is in the range of tens of minutes on this class of GPU. **This must be measured before relying on it.** |
| **External hosted service** | Possible, **not recommended** for personal data | The data leaves the user's devices. It would need explicit approval each time, plus a review of the provider's data terms. |

## 3. Method
- **LoRA / QLoRA.** The base model is frozen; small low-rank adapter matrices are trained. The adapter is a few megabytes to tens of megabytes. The original model stays intact, which makes rollback trivial.
- **Toolchain (desktop, after approval):** Python with `transformers` and `peft` (plus `bitsandbytes` for 4-bit), or Unsloth.
  - It needs the **fp16/bf16 base model** (Qwen/Qwen2.5-1.5B-Instruct safetensors, ≈3.1 GB **download**, which needs approval).
- **Getting it onto the phone:**
  - **(a) Adapter at runtime:** convert the LoRA to GGUF (llama.cpp `convert_lora_to_gguf.py`) and load it with llama.cpp's adapter API. This needs a **native (JNI) change** (approval). The download is small (adapter only), and switching back is instant.
  - **(b) Merge and re-quantize:** merge the adapter → `convert_hf_to_gguf.py` → `llama-quantize` Q4_K_M. This gives a full ~1.1 GB model. No native change, but a big download, and quantization may erase small gains. **Evaluate after quantization.**
- **Distribution:** through the existing `ModelStore` and `Downloader` (pinned URL plus SHA-256). Keep the original model installed and active until the user switches.

## 4. Dataset policy (implemented as code, `DatasetPolicy`)
Never automatic: no training on all conversations, and no upload of chats.

The future workflow would be:
1. The user **selects** examples (for example, answers they rated 👍 or saved).
2. **Preview** the selection.
3. **Sensitive-data filter:**
   - `MemoryKit.sensitive`: email, phone, card, 12-digit ID, PAN, password or PIN, health or financial details;
   - `DatasetPolicy.secrets`: API keys, OAuth or bearer tokens, private keys, `key=`/`token=` credentials.
   - These are **always rejected**, even if approved.
4. **Manual review:** each example needs `approved = true`, set by the user. Code never sets it.
5. **Export:** chat-format JSONL.
6. **Versioning:** a manifest with dataset version, policy version, counts, source kinds, the SHA-256 of the data, and the target base model plus its SHA-256.
7. **Training configuration** (desktop, documented separately when approved).
8. **Evaluation** (§5).
9. **Model comparison** (§5).
10. **Rollback:** the original model is kept; the adapter or merged model is removed.

Also rejected: duplicates, too short or too long pairs, and error messages stored as answers.

Not allowed in any dataset: passwords, API keys, OAuth tokens, sensitive personal data, and unreviewed private documents.

## 5. Evaluation (required before any model is even offered)
Compare the candidate with the **current** model on:

| Metric | How (reusing existing app checks) |
|---|---|
| Task accuracy | A held-out 20% of the user's approved examples; plus `retrieval_eval.json` answers with citations |
| Instruction following | Parse rates of structured outputs the app already validates: interview feedback JSON (`AiJson.feedback`), MCQs (`Learning.validMcq`), flashcards (`Studio.parseCards`), plan lists (`Studio.parseList`), memory suggestions (`MemoryKit.parseCandidates`) |
| Personalisation | Blind A/B preference by the user on 20 prompts |
| Hallucination | No-answer queries must produce "The documents don't cover this"; no invented sources or page numbers |
| Latency and memory | Tokens per second and peak RAM **on the phone** |
| Size and regression | File size; all of the above against the current model |
| Safety | The refusal behaviour of the base model on a fixed safety prompt set |

**Gate (`DatasetPolicy.mayOffer`, tested):** a candidate is offered only if **all** of these hold:
- the user explicitly approved it;
- task accuracy and instruction following are not more than 2 points worse;
- hallucination is not more than 1 point higher;
- speed is at least 80% of the current model's;
- size is at most 120% of the current model's.

Even then, activation is a separate explicit user action, and the original model stays for rollback. `APP_MAY_TRAIN = false`.

## 6. Expected benefit vs cost
- **Benefit (unproven):** more consistent tone and format, and fewer format errors in structured tasks. Facts about the user are better served by memory and RAG, which are updatable and deletable, unlike weights.
- **Costs and risks:**
  - curating hundreds to thousands of good examples;
  - a privacy risk: a model can memorise and later repeat personal text;
  - a ≈3.1 GB base download on the PC;
  - a native change for adapters;
  - a possible quality loss after quantization;
  - maintenance whenever the base model changes.

## 7. Recommendation
**Do not fine-tune on personal data now.** First:
- improve prompts, memory and retrieval (including the optional semantic search, see v3.6);
- complete physical-device acceptance.

If the owner wants evidence, run a **desktop QLoRA pilot on non-personal data** that targets a measurable weakness of the small model. One example is JSON or format compliance for the app's structured tasks, built from public or synthetic examples. Measure it with §5 before considering any personal dataset.

## 8. Decisions needed from the owner
1. Approve or decline a **desktop pilot**. That includes downloading the Qwen2.5-1.5B-Instruct base (≈3.1 GB) and the Python training stack on the dev PC.
2. If the pilot passes the gate: approve the **JNI LoRA-adapter support** (option a) or the merged-model route (option b).
3. Whether any **personal** data may ever be used. The default is no; if yes, only through the §4 workflow.
4. External services: decline (recommended) or name an approved provider.

## Tests (`V41Test.kt`, 4 tests)
- Only approved, safe, unique and well-formed examples pass. Secrets and personal data are always rejected.
- The export format, manifest versioning and checksum.
- The activation gate (approval plus no regression).
- **No training code anywhere** in the app or the native layer; the dataset code has no database, file, network or AI access; the model file is written only by the verified download path.
