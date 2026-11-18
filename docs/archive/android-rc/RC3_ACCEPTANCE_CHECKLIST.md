# RC.3 Android acceptance checklist

Results are split into **automated (mocked)** results on the PC and **real** results on a phone. Nothing here is marked as passing on a phone unless it was done there.

## Automated, development PC (2026-11-03)
| Suite | Result | Real or mocked |
|---|---|---|
| Android JVM unit tests | **77 run, 0 failed** (2 opt-in network tests skipped) | mocked HTTP for providers and cloud |
| Provider rules/store/client/flow tests (23) | all pass | **mocked** provider responses built from the official docs' formats |
| Cloud service | **42 passed** (incl. 6 shared-provider tests) | **mocked** providers (httpx MockTransport) |
| Kotlin client ↔ real cloud server (`CloudIntegrationTest`, rc.2) | PASS | real server + real local Ollama (development PC only) |
| Built APK credential scan | 0 key-shaped strings in 191 files | real |
| Lint | 0 errors | real |
| **Real Gemini / Groq / OpenRouter calls** | **NOT TESTED**: no keys available | — |

## On the phone (Redmi K50i): owner to run
Install: `platform-tools\adb.exe install -r Abhify-2.0.0-rc.3-debug.apk`. This is an **in-place upgrade; never uninstall or clear data.**

| # | Test | Expected | Result |
|---|---|---|---|
| A1 | App opens after upgrade | old chats in History, model still installed (no download) | |
| A2 | Database upgrade (`RUN-TESTS.cmd` → db_migration) | PASS | |
| A3 | Memory persistence: add a memory, close the app (swipe away), reopen | memory still listed | |
| A4 | Airplane mode: chat in *On this phone* mode | answers, label `📱 On this phone · Qwen2.5…` | |
| A5 | Privacy → Allow online AI **off**: AI providers says nothing is sent | disclosure line says "Answers on this phone" | |
| A6 | AI providers → Groq → paste key → Validate & save | "Key works · N free models", masked key shown | |
| A7 | Same with a wrong key | "API key is invalid", **not saved** | |
| A8 | Tick the free-plan box, allow online AI, Online mode → Groq | answer labelled `☁ Groq · …`; usage "Requests left today…" | |
| A9 | Export all (History) → open the file | chats + memories only, **no key** in it | |
| A10 | Replace key, Remove key (confirm) | masked key changes / provider "not configured" | |
| A11 | Auto with Gemini first and a wrong Gemini key (or quota) | notice "Gemini unavailable… Trying Groq", answer from Groq | |
| A12 | Airplane mode in Auto | answer from the phone (if fallback on) or a clear "offline" message | |
| A13 | Stop during a long online answer | stops; partial answer kept | |
| A14 | OpenRouter: model list shows only free models; "Usage" shows free requests today | | |
| A15 | Gemini: can't be used until "no billing linked" is ticked | provider "needs attention" | |
| A16 | Edit & resend, Regenerate, Copy, Share, a table answer, code highlighting | work | |
| A17 | History: search, pin, rename, share | work | |
| A18 | Light/dark theme; text sizes readable; keyboard doesn't cover the message box | | |
| A19 | Second phone (if available): install → no keys present → AI providers opens once | | |

Send back: `results\` from RUN-TESTS.cmd, plus screenshots or notes for A1–A19.
