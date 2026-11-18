# Abhify.AI Android 3.4.0: known limitations

1. **Not tested on a physical phone yet.** Only the JVM tests and the build checks have run. See [V3_4_TEST_REPORT.md](archive/android-3.x/V3_4_TEST_REPORT.md).
2. **Google Drive needs a one-time OAuth client** (Android client, package `ai.abhify.android` + debug SHA-1) in Google Cloud Console.
   - Until it exists, Connect shows a "not configured" message.
   - Real-account Drive testing is **pending**. The client logic was tested against a simulated Drive.
3. **Drive uploads run while the app is open,** not as a background job. If Android stops the app, the upload stays in the retry queue.
4. **Drive tokens are not stored.** You may be asked to confirm the Google account again after about 45 minutes, or after the app restarts.
5. **No automatic or two-way Drive sync.** Everything is started by you.
6. **Document search is keyword-based** (FTS4 + BM25). There are no semantic embeddings yet, which is planned as an optional, approved download in 3.6. Phones without FTS4 fall back to slower plain text matching.
7. **Memory suggestions and document answers come from the small on-phone model.** They can be imperfect; the citation ✓ marks and the approval step are the safeguards.
8. **Backups.**
   - They hold the extracted text of documents, not the original files.
   - Very large backups are built in memory.
   - An encrypted backup cannot be restored without its passphrase.
9. **Restore never updates existing rows,** by design (additive only). If an item already exists on the phone (same ID), the phone's version is kept and the backup's version is ignored.
10. **The default AI mode changes only for new installs.** Updated installs keep their previous mode (normally Auto).
11. **Lint reports 75 warnings,** mostly KTX style suggestions and newer-dependency notices, and 0 errors.
12. **This is a debug build.** It has no production signing and is not for the Play Store.
