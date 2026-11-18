# Xiaomi-OCR-0: deferred (owner decision, 2026-11-04)

**Owner decision:** do not integrate, download, convert or benchmark Xiaomi-OCR-0 in v3.1. Reconsider only if device tests show ML Kit can't extract resumes or images well enough.

**What was done:** only the metadata checks below, made before the decision. No weights were downloaded, and the app has no Xiaomi code, dependency or model.

## Facts gathered (2026-11-04, Hugging Face API and the pinned llama.cpp source)

| Item | Fact |
|---|---|
| Official repository | `xiaomi-research/Xiaomi-OCR-0` (commit bbd8cf8d21f2). `XiaomiMiMo/Xiaomi-OCR-0` returns HTTP 401 (missing or private). |
| License | Apache-2.0 |
| Base model | Qwen/Qwen3.5-0.8B-Base |
| Architecture | `Qwen3_5ForConditionalGeneration`: 24 text layers (hybrid linear and full attention, hidden 1024, vocabulary 248,320) plus a 12-layer ViT vision encoder (patch 16) |
| Weights | 873,438,784 parameters, BF16, `model.safetensors` 1,746,937,856 bytes |
| Community conversions (unofficial, unverified) | GGUF Q4_K_M about 529–542 MB plus vision projector about 205–207 MB; LiteRT-LM int8 1,314,747,056 bytes |
| llama.cpp v0.7.0 (620d2225, the app's pinned version) | Has the `qwen35` text architecture and converts this model's vision tower as a Qwen3-VL `mtmd` projector (code inspection only) |
| What the app would need | build `libmtmd`; a new JNI image path; a model download of about 735 MB (approval required); unload Qwen2.5 during OCR |

## Not verified

Runtime on Android, peak memory, latency, accuracy, and behaviour on an 8 GB phone. **Verdict: NOT VERIFIED, not integrated.** The app works fully without it.
