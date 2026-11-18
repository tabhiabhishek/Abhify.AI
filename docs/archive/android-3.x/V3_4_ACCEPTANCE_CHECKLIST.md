# Abhify.AI Android 3.4.0: install and acceptance checklist

## Install (keeps all data)
1. Check the hash: `certutil -hashfile Abhify-3.4.0-debug.apk SHA256` must match `SHA256SUMS.txt`.
2. Phone: USB debugging on, connected, "Allow" the computer.
3. Install over the current version:
   ```
   platform-tools\adb.exe install -r Abhify-3.4.0-debug.apk
   ```
   **Do not uninstall and do not clear app data.** If adb reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, stop and report it. Do not work around it by uninstalling.
4. Optional: run `RUN-TESTS.cmd` (phone tests that never touch your data), then `COLLECT-LOGS.cmd` if something fails.

## Checks
Mark each check PASS, FAIL or N/A. All checks are currently **NOT RUN**.

| # | Check | Expected |
|---|---|---|
| 1 | Open the app after the update | Old chats, memories, documents, model and settings are all still there; About shows 3.4.0 |
| 2 | Ask a normal question offline (airplane mode) | Answered by 📱 This phone (Qwen) |
| 3 | Settings → Personal memory | The explanation text is shown; existing memories are listed under "General" |
| 4 | Add a memory "Prefers PyTorch examples" (Preferences, high) | Saved; the category filter shows it |
| 5 | Ask "Show me a short PyTorch example" | Answer shows "🧠 1 personal memory used"; tapping it lists the memory |
| 6 | Ask "What is the capital of France?" | No memories used |
| 7 | In chat: "remember that my password is abc123" | Not saved; a notice explains why |
| 8 | Memory → Suggest from current chat | Candidates appear; only the ticked ones are saved |
| 9 | Add the same memory twice | Asks before saving a duplicate; "Merge duplicates" works |
| 10 | Memory → Export JSON / Markdown | Files are saved and readable |
| 11 | History: search a word from an old chat | Found |
| 12 | History: Archive a chat → Archived tab → Restore | It moves between Active and Archived; nothing is deleted |
| 13 | History: date filter From/To | Only chats in range are shown |
| 14 | Documents: import a PDF, then import the same PDF again | The second import says "already in your library" |
| 15 | Documents → Use in chat; ask about its content | Answer cites [S1]…; "Sources sent" shows ✓ for cited ones |
| 16 | Ask something not in the document | "The documents don't cover this" plus a general-knowledge label or warning |
| 17 | With an online provider configured and Auto mode: ask with documents selected | The "Send document passages online?" dialog appears; "This phone" keeps it local |
| 18 | ⚙ Online context | Recent messages and memories switches work; the line above the input says what is sent |
| 19 | Settings → Storage | Sizes are shown; Clear cache works; the model is still present afterwards |
| 20 | Backup: all categories, with passphrase → Save to file | File is created; "last backup" is updated |
| 21 | Restore the same file: wrong passphrase, then the correct one | Wrong: clear error. Correct: preview shows 0 new rows; restore adds nothing |
| 22 | Google Drive → Connect (without an OAuth client) | Clear "isn't configured" message; the app keeps working |
| 23 | Google Drive (only after [GOOGLE_DRIVE_SETUP.md](../../GOOGLE_DRIVE_SETUP.md)) | Connect, upload, conflict dialog, download, trash with confirmation, Disconnect |
| 24 | Career, Study, Workspace, voice, OCR | Work as in 3.3 |

Send back: this table with results, plus the `results` folder from `RUN-TESTS.cmd` and the logs from `COLLECT-LOGS.cmd` if anything failed.
