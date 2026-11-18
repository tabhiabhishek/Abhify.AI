# Abhify.AI v3.1: OCR benchmark

## Status: NOT RUN on a device

| Item | Detail |
|---|---|
| Phone | None connected (`adb devices` is empty) |
| Emulator | Not possible: Windows Hypervisor Platform is disabled; enabling it needs admin rights and a reboot |
| Desktop OCR | Not applicable: ML Kit runs only on Android |

**No latency, memory or accuracy figure in this file comes from a measurement.** The benchmark is built and ready; the result tables below are empty on purpose.

## What was verified (build and JVM tests)

| Item | Result | How verified |
|---|---|---|
| Engine | Google ML Kit text recognition **16.0.1**, bundled Latin model | Gradle dependency tree |
| Model in the APK (no download) | `assets/mlkit-google-ocr-models` (1,486,803 bytes) | APK inspection |
| Native library | `libmlkit_google_ocr_pipeline.so`: 11,064,544 bytes (arm64-v8a), 11,626,128 bytes (x86_64) | APK inspection |
| APK size impact | 3.0.0 debug 52,634,646 → 3.1.0 debug 68,395,640 bytes (+15.8 MB). ML Kit plus `libomp` = 11.2 MB compressed for the two debug ABIs; the rest is Play-services and datatransport code. A release build (arm64 only) would carry roughly half the native part. | APK inspection |
| Permissions added | none | `aapt2 dump permissions` diff, 3.0.0 vs 3.1.0 |
| Runs concurrently with Qwen2.5 | **No, by design**: `AppGraph.withOcr` refuses while generating, unloads the model first, and blocks model loading until OCR ends | Code plus JVM test `MemoryTest.ocrUnloadsTheLanguageModelAndBlocksItWhileRunning` |
| Pipeline logic | text pages are never OCR'd; scanned pages one at a time; images released; engine closed on success, failure and cancel; page limits; low-memory stop | 6 JVM tests with a scripted engine |
| CER/WER functions | Levenshtein-based, checked against known values | JVM test |
| Privacy (per ML Kit terms, 2025-05-14) | images and results stay on the device; the SDK sends usage and performance metrics to Google | Documentation |

## The benchmark (ready to run)

**Test:** `androidTest/OcrBenchmarkTest`, run by `RUN-TESTS.cmd` in the v3.1 kit.

**Inputs:** synthetic and anonymised (fictional "Priya Sharma" resume, `example.com` email). No real resume is used or uploaded. The ground truth is the drawn text.

| # | Input | Expected OCR pages |
|---|---|---|
| 1 | Resume, selectable-text PDF | 0 (must be exact with no OCR) |
| 2 | Resume, scanned PDF (image page) | 1 |
| 3 | Mixed PDF: text page + scanned page | 1 |
| 4 | Resume screenshot, PNG 1080 px wide | 1 |
| 5 | Photo-like JPEG: 2400 px, rotated 2°, speckle noise, quality 60 | 1 |
| 6 | Small-print document PNG (30 px font) | 1 |
| 7 | 3-page scanned PDF | 3 |

**Recorded per input:**
- total time and time per OCR page;
- PSS before and peak (sampled every 50 ms);
- CER and WER against the ground truth;
- whether the email, phone, date and "35%" survived;
- unclear-line count;
- device model, Android version, RAM total and free.

**Where results go:** logcat tag `AbhifyOcrBench`, and the app-private file `files/ocr-benchmark.json`. The kit pulls it with `adb shell run-as ai.abhify.android cat files/ocr-benchmark.json`.

## Results

| Input | Device | ms total | ms / page | Peak PSS Δ | CER | WER | Key fields |
|---|---|---|---|---|---|---|---|
| (pending: no device) | | | | | | | |

## Engine choice and limits

- **Selected:** ML Kit bundled Latin. It is the only candidate that is offline, needs no model download and adds about 6 MB per ABI. Tesseract, PP-OCR and Xiaomi-OCR-0 were not evaluated, per the owner's decision; Xiaomi-OCR-0 is deferred (see [ABHIFY_V3_1_XIAOMI_OCR_COMPATIBILITY.md](ABHIFY_V3_1_XIAOMI_OCR_COMPATIBILITY.md)).
- **Known limits of the chosen engine:**
  - Latin script only (no Devanagari or Chinese models bundled);
  - handwriting is not a target;
  - per-line confidence is used to flag unclear lines.
- A real resume's accuracy will only be known after the device run. Accuracy on synthetic, clean text is an upper bound.
