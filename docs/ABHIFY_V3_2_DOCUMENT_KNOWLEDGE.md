# Abhify.AI 3.2: documents, OCR and My Knowledge

## Documents

**Unchanged from 3.1:**
- **Extraction:** pdfbox text layers first, DOCX, TXT, MD, CSV.
- **OCR:** ML Kit for scanned pages and photos. It is opt-in, works one page at a time, frees each image, and unloads Qwen before running.
- **Library actions:** summary, key points, action items, notes, flashcards, quiz, compare, view, rename, delete.

**New in 3.2: Q&A over selected documents**
- Tick one or more documents and ask. Only the ticked documents are searched; with none ticked, the whole library is.
- The answer cites `[S1]…` with the document name and **page** (PDF) or section. The source list shows exactly what was sent.
- When nothing matches, Abhify says so and sends nothing to the AI.

**Unchanged rules:**
- Bounded excerpts: about 2,500–3,500 characters for the phone's model (by memory profile) and about 20,000 for online providers.
- Consent before any online use.
- **⭳ Knowledge** saves a reference to a document in My Knowledge. The text stays only in the library.

## My Knowledge (Knowledge tab)

**What can be saved:** chat answers (⭳ Save), document and library results, resume results and versions, study sessions, quiz results, flashcards, interview feedback, and document references.

**Managing items:**
- Search (title, text, tags).
- Categories: Documents, Career, Study, Notes, AI answers.
- Tags, favourites ★, sort by recent or A–Z.
- Open (updates "recently used"), edit (title, category, tags, favourite), delete with confirmation.

**Ask selected items:**
- Only the **ticked** items are searched: there is no implicit whole-vault search.
- Retrieval runs on the phone (BM25 keyword search, no embedding model, no download).
- Answers cite `[S#]`; a "no match" result sends nothing.
- Online use needs consent.

**Workspaces:** tasks, notes, meeting notes, workspace knowledge and writing tools. This is the former Work tab, unchanged.

## Storage

- `vault_items` (database v7): local, never synced.
- Deleting a saved document reference leaves the library document in place.
- Privacy → "Delete all documents, notes, tasks, plans and flashcards" also removes Career, Study and My Knowledge data.

## Tests

`VaultAndShareTest` (selected-only retrieval, tags, redaction), the v7 migration test, `StudioTest` (retrieval, page citations), `ExtractionPipelineTest`. `OcrBenchmarkTest` is pending a device.
