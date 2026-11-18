# Phase 2: AI chat experience

**Status:** done in code (4.4.0). **Phone verification:** pending.

| Capability | How it works | Since |
|---|---|---|
| Streaming | Real token streaming from llama.cpp (`LlmEngine` callback flow) and providers (SSE). Markdown re-renders as text arrives (styled lines cached). | 2.0 / 4.3 |
| Stop | ■ in the send button cancels the request. The partial answer is kept and marked "stopped". | 3.0 |
| Regenerate | On the last answer (the same question and history). | 3.0 |
| Continue | When an answer stopped at the length limit (`chat/Continuation.kt`). | 3.0 |
| Edit the last question | Replaces that question and the answers after it in place (as before). | 3.0 |
| **Edit an earlier question** | ✏ on any earlier question → the composer says "it will be sent in a new chat". The messages before it are copied into a new conversation "… (edited)" and the edited question is sent there. **The original chat isn't changed.** Not offered in private chats (they aren't stored). | **4.4** |
| Response actions | Copy, share, copy code, save to Knowledge, 📒 note, read aloud, 👍/👎 | 3.x |
| History | FTS search, rename, pin, folders, archive, dates, paging; export .md, .txt and **.pdf** | 3.4 / **4.4** |
| Errors | Named provider errors, model missing, out of memory, network; "Ask <provider> instead…" only after consent | RC.4 / 3.4 |

**Not applicable:** the Android app has no Ollama.

**Tests:** `V44Test.editingAnEarlierQuestionNeverChangesTheOriginalChat`, plus the older chat and continuation tests.

**Open:** phone check.
