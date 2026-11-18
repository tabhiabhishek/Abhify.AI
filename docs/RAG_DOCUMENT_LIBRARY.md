# Document library and RAG (Abhify.AI Android 3.4)

RAG (retrieval-augmented generation) means that answers are based on the most relevant passages from your own documents.

## Importing documents
- **Sources:** the system file picker (which includes Google Drive, Downloads and SD cards) and Drive downloads made from inside Abhify.
- **Formats:** PDF (text, or text recognition with ML Kit for scanned pages), DOCX, TXT, MD, images (recognised on the phone) and others as before.
- **Duplicates:** the SHA-256 of the file is stored. Importing the same file again shows "already in your library" instead of adding a copy.
- **Source:** each document records where it came from (picker, Drive, share).

## Indexing
- **Chunks.** After import, the text is split into chunks of about 1,000 characters. Each chunk keeps the page number it started on, for PDFs and recognised scans.
- **Search index.** Chunks are stored in `doc_chunks`. When the phone supports SQLite FTS4, they are also stored in a full-text index (`doc_chunks_fts`).
- **Older documents.** Documents from before 3.4 are indexed in the background about 8 seconds after app start.
- **Re-index.** The **Re-index** button on a document rebuilds the chunks for one document.
- **Deleting.** Deleting a document deletes its chunks and its index rows.

## Answering from documents
1. Choose the documents for this chat: tap the **📚 Documents** chip, or use **Use in chat** on a document.
2. For each question:
   - The full-text index finds candidate chunks (any of the question's words).
   - The candidates are ranked with BM25.
   - The best 8 chunks are added to the prompt as `[S1] name, p. N …`.
3. The model is told to answer only from those passages, cite them as [S1], [S2] …, and say *"The documents don't cover this"* when the answer isn't there, followed by a short answer clearly labelled as general knowledge.
4. Under the answer:
   - **Sources sent:** lists every passage sent; the ones actually cited get a ✓.
   - If nothing is cited: *"No source cited: treat this as general knowledge, not from your documents."*

## Privacy
- **Local model:** with the phone's model, nothing leaves the phone.
- **Online model:** if the chat would go to an online provider, Abhify first asks **"Send document passages online?"**. You can choose:
  - **Send:** only the selected passages go to the provider.
  - **This phone:** the question is answered by the local model.
  - **Cancel.**
- **Approval per selection:** the approval applies to the current selection of documents. Changing the selection asks again.
- **No automatic upload:** documents are never uploaded to Drive automatically.

## Limitations
- **No semantic embeddings.** Ranking is keyword-based (BM25), so questions phrased very differently from the text can miss passages.
- **No FTS4 support.** On phones without FTS4, candidate search falls back to slower plain text matching. The results are the same, only slower.
- **Small local model.** It may occasionally cite incorrectly. The ✓ marks show which citations exist.
