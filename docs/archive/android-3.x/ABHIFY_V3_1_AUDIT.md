# Abhify.AI v3.1: audit (Phase 0)

**Date:** 2026-11-04.

**Inspected:** the working tree at `D:\Abhify.AI\android` (uncommitted 3.0.0 on branch `feature/m2-knowledge-packaging`), the pinned llama.cpp source at `D:\AndroidDev\src\llama.cpp` (commit 620d2225), Hugging Face model metadata and the ML Kit documentation.

**No application behaviour was changed during the audit.**

## 1. Current architecture (verified in code)

| Area | Finding |
|---|---|
| Platform | Kotlin 2.3.0 + Jetpack Compose, single activity, `minSdk 29` / `targetSdk 36`. Debug ABIs arm64-v8a + x86_64; release arm64-v8a only. |
| Navigation | Hand-rolled route stack in `AppViewModel` (`Route`) plus five tabs (`Tab`: Chat, Learn, Documents, Work, Settings). |
| State | `AppViewModel`; `StudioState` for Documents, Learn and Work. |
| Local AI | `LlmEngine` (JNI → `abhify_llm.cpp`, llama.cpp v0.7.0, text only). One model loaded at a time. `AppGraph.ensureModel()` loads lazily under a mutex and refuses when free RAM is below the model estimate plus margin (`ModelSelector.loadBlocker`). `AbhifyApp.onTrimMemory(≥ BACKGROUND)` unloads the model. Catalog: Qwen2.5 1.5B Q4_K_M (est. 1,450 MB runtime) and 0.5B Q4_K_M (750 MB); context 1024/2048/4096. |
| Upload / extraction | `docs/DocumentReader` (system picker, 10 MB cap) → `docs/Documents.extract`: TXT, MD, CSV, JSON, code (encoding detection), DOCX (zip, `word/document.xml`, zip-bomb guard), PDF through `docs/PdfText` (pdfbox-android, text layer, `[[Page N]]` markers). |
| Images and scanned PDFs | **Refused with a message.** No OCR code and no bitmap handling anywhere. |
| Document library | `library_docs` (v5), Library Q&A with BM25 (`docs/Library`), summaries, actions, flashcards, quiz, comparison, citations. |
| Resume code | **None** (catalog item #73 "Not implemented"). |
| AI task runner | `AppViewModel.aiTask`: routing plus a consent dialog (Send / This phone / Cancel) before sensitive text goes online. |
| Database | `ChatDb` SQLiteOpenHelper **v5**. v2–v4 are inline methods; v5 is `SchemaV5` (additive). |
| Privacy | Keys in the Keystore AES-GCM box. Backups disabled. Library and Work data are local only and never synced. |
| Tests | 107 JVM unit tests (sqlite-jdbc for migration SQL). androidTest: `DbMigrationTest` (v2→v5), `PdfTextTest`, `OnDeviceE2ETest` (deletes the model, so not for the owner's phone). |

## 2. Memory risks found

1. **No coordination between heavy jobs.** Nothing stops a future OCR job and local generation from running together. `ensureModel` only checks free RAM at load time.
2. **pdfbox loads the whole PDF into memory** (`PDDocument.load(bytes)`). Bounded by the 10 MB file cap, which is acceptable.
3. **No device memory profile.** Document budgets for the local model are fixed (3,200–3,500 characters).
4. **No low-memory handling in the foreground.** `onTrimMemory` only acts at `TRIM_MEMORY_BACKGROUND`.

## 3. OCR candidates (verified facts)

### Google ML Kit Text Recognition v2, bundled

| Item | Fact |
|---|---|
| Artifact | `com.google.mlkit:text-recognition:16.0.1` (latest; 2024-08) |
| Size | About 4 MB per architecture (Latin) |
| Runs on | API 23+ |
| Offline | Yes |
| Privacy | Per the ML Kit Terms (modified 2025-05-14), input images and results stay on the device. **ML Kit sends performance and usage metrics to Google** and may fetch updates. |
| License | Google APIs Terms of Service (proprietary, free) |

### Xiaomi-OCR-0

| Item | Fact |
|---|---|
| Repository | **`xiaomi-research/Xiaomi-OCR-0`**. The URL in the request (`XiaomiMiMo/Xiaomi-OCR-0`) returns HTTP 401 (missing or private). |
| Weights | 873,438,784 parameters, BF16, one 1.75 GB safetensors file |
| License | Apache-2.0 |
| Architecture | `Qwen3_5ForConditionalGeneration` (hybrid linear- and full-attention text model, 12-layer ViT vision encoder) |
| Runtime support | The pinned llama.cpp contains the `qwen35` text architecture and converts the vision tower as a Qwen3-VL `mtmd` projector. |
| Community conversions (not official) | GGUF Q4_K_M about 530–542 MB plus projector 205–207 MB; LiteRT-LM int8 1.31 GB |

Details: [ABHIFY_V3_1_XIAOMI_OCR_COMPATIBILITY.md](../../ABHIFY_V3_1_XIAOMI_OCR_COMPATIBILITY.md).

### Not evaluated

Tesseract and PP-OCR, per the brief. A second engine is only justified by benchmark results.

## 4. Test environment available

| Resource | Status |
|---|---|
| Physical phone | **None connected** (`adb devices` is empty) |
| Emulator | **Not possible.** No system image, and Windows Hypervisor Platform is disabled. Enabling it needs admin rights and a reboot of this office PC, which I won't do. |
| Consequence | Instrumented tests, the OCR benchmark and the memory measurements can be **built but not run** here. |

## 5. Proposed changes (the scope implemented in v3.1)

1. **Memory profiles (Low / Balanced / Quality, plus Auto)** from `ActivityManager.MemoryInfo`, `isLowRamDevice` and trim callbacks. They control the OCR image size, the OCR page limit, the PDF render resolution and the local document budget.
2. **Heavy-work gate:**
   - OCR and local generation never run at the same time.
   - OCR unloads Qwen first. The model is reloaded on the next local answer; files are never deleted.
   - Under memory pressure, new heavy work is refused.
3. **Extraction pipeline, page by page:**
   - Text-layer pages are extracted directly. Only pages without text are rendered (Android `PdfRenderer`, one page at a time, bitmap recycled) and OCR'd. This handles mixed PDFs.
   - Images are decoded bounded with `ImageDecoder`, which applies EXIF rotation.
   - Results report the method and warnings per page.
4. **ML Kit bundled Latin OCR** behind an `OcrEngine` interface. It runs only after a one-time permission that discloses the metrics.
5. **Resume Assistant:**
   - It reuses the library, extraction and AI task runner.
   - Contact details, sections and skills are parsed by rules (no AI, so nothing is invented).
   - AI summary, suggestions, interview questions, a job-description comparison and an improved draft. A **grounding check** flags numbers and terms in AI output that are not in the resume.
   - The original is never changed. Corrections and AI outputs are stored as separate versions.
   - Export as `.md` or `.txt`, or share.
6. **Database v6 (additive):**
   - `library_docs` gains `pages`, `ocr_pages`, `warnings` and `role`.
   - New table `resume_versions`.

## 6. Dependencies to add

| Dependency | Type | Size | License |
|---|---|---|---|
| `com.google.mlkit:text-recognition:16.0.1` | Runtime | +about 4 MB per ABI (arm64 + x86_64 in debug) | Google APIs ToS |

Everything else uses existing code or Android framework APIs (`PdfRenderer`, `ImageDecoder`, `ActivityManager`).

## 7. Compatibility concerns and missing information

1. ML Kit metrics collection conflicts with a strict "nothing leaves the phone" stance. It is disclosed and opt-in.
2. Xiaomi-OCR-0 integration needs:
   - building `mtmd` into the app;
   - a new JNI path for images;
   - an approved download of about 735 MB.

   It cannot be measured without a device. **Not integrated.**
3. Peak memory, latency and accuracy on the Redmi K50i are **unknown** until the instrumented benchmark runs on it.
