# Roadmap v3.5–v4.1: acceptance checklist

**Install:**
- Install the **latest** build over the current app: `adb install -r Abhify-4.1.0-debug.apk`. Do not uninstall and do not clear data.
- Intermediate versions can be installed in order (3.5.0 → … → 4.1.0) to exercise each migration. Every version is additive.

**Status values used below:**
- **PASS-JVM:** verified by JVM tests on the build machine.
- **PENDING-DEVICE:** needs the phone.
- **PENDING-CONFIG:** needs the Drive OAuth client.
- **PENDING-APPROVAL:** blocked on an owner decision.
- **RESEARCH:** documented only.

| # | Version | Check | Status |
|---|---|---|---|
| 1 | all | Upgrade from 3.4 keeps chats, memories, documents, study, career, settings, the model; About shows 4.1.0 | PENDING-DEVICE (migrations PASS-JVM; `DbMigrationTest` built) |
| 2 | 3.5 | Drive → upload a file, close the app, reopen: the upload finished or continues; the queue shows its status | PENDING-CONFIG + DEVICE (queue logic PASS-JVM with a simulated Drive) |
| 3 | 3.5 | Airplane mode during an upload → it waits; network back → resumes from where it stopped | PENDING-DEVICE (resume PASS-JVM) |
| 4 | 3.5 | Same-name, different file → a conflict that survives a restart; Replace / Keep both / Later | PENDING-CONFIG (PASS-JVM) |
| 5 | 3.5 | Cancel uploads → items "Cancelled", Retry works, nothing deleted | PENDING-DEVICE (PASS-JVM) |
| 6 | 3.5 | Scheduled backups are off by default; enabling Drive without a passphrase is refused | PENDING-DEVICE (rules PASS-JVM) |
| 7 | 3.5 | Schedule a daily backup to a picked folder; "Back up now" creates `abhify-backup-auto-*.json` there; history shows ✓ | PENDING-DEVICE |
| 8 | 3.5 | Keep = 2, run 3 backups → only the oldest scheduled one is removed; manual backups untouched | PENDING-DEVICE (retention PASS-JVM) |
| 9 | 3.6 | Settings → Semantic search shows "keyword active / not installed", with the download disabled | PENDING-DEVICE |
| 10 | 3.6 | No embedding model is downloaded or bundled; document Q&A works as before | PASS-JVM (source checks) / PENDING-DEVICE |
| 11 | 3.6 | Approve an embedding model | PENDING-APPROVAL |
| 12 | 3.7 | Career → Interview (job topic) → the answer is saved with a score; "This job" filter; the dashboard shows practice and career goals | PENDING-DEVICE (PASS-JVM) |
| 13 | 3.7 | Existing résumés, jobs, applications and attempts are intact | PENDING-DEVICE |
| 14 | 3.8 | Add "Preferred framework: Flask", then "Preferred framework: FastAPI" → conflict dialog with Keep existing / Replace / Keep both / Merge | PENDING-DEVICE (PASS-JVM) |
| 15 | 3.8 | Memory screen: Overview counts and Needs review; Keep both is not suggested again | PENDING-DEVICE |
| 16 | 3.8 | A memory shows its source; "Open chat" opens it; old memories show "Source not recorded" | PENDING-DEVICE |
| 17 | 3.8 | A follow-up question in a chat about a project uses the project memory; an unrelated question uses none | PENDING-DEVICE (PASS-JVM) |
| 18 | 3.9 | Cards: Again / Hard / Good / Easy show their intervals; "Review N due cards"; old decks are all due once | PENDING-DEVICE (PASS-JVM) |
| 19 | 3.9 | A plan with a past target date is refused; a tight plan saves with a warning; lessons can be edited, added, deleted | PENDING-DEVICE (PASS-JVM) |
| 20 | 3.9 | Study → Today shows the recommended next session with a reason | PENDING-DEVICE |
| 21 | 4.0 | 🔍 finds an item from each area; nothing goes online | PENDING-DEVICE (SQL PASS-JVM) |
| 22 | 4.0 | Choose a workspace in chat → the answer lists 📝 workspace parts; online mode asks before sending | PENDING-DEVICE (PASS-JVM) |
| 23 | 4.0 | Search → Add to workspace / New workspace from this / Study plan / Memory suggestion | PENDING-DEVICE |
| 24 | 4.0 | Deleting a workspace keeps linked chats, documents and memories | PENDING-DEVICE (PASS-JVM) |
| 25 | 4.0 | Production signing and release | PENDING-APPROVAL (evaluation in `V4_0_PRODUCTION_READINESS.md`) |
| 26 | 4.1 | No training anywhere; model files unchanged | PASS-JVM (source checks) |
| 27 | 4.1 | Fine-tuning pilot | PENDING-APPROVAL / RESEARCH |
| 28 | all | Chat on the phone's model, OCR, voice, Career, Study and Documents work as in 3.4 | PENDING-DEVICE |

**Phone tests:** `RUN-TESTS.cmd` in `build/Abhify-Android-Test-v4.1/` runs `DbMigrationTest` (v2 → v15 on a scratch database), `PdfTextTest`, `OcrBenchmarkTest` and `ResumeExportTest`. None of them touch your data.
