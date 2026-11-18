# RC.4: documents in chat and privacy

## What you can attach

Tap **📎** in the chat. The Android system file picker opens, and Abhify gets access only to the file you choose.

| Type | Status |
|---|---|
| TXT, MD, CSV, TSV, JSON, LOG and code files | Supported. Read as UTF-8 or UTF-16; Latin-1 is used as a fallback. |
| DOCX | Supported. Paragraphs, tabs and tables are read from `word/document.xml`. |
| PDF | **Not available yet.** You get a clear message instead. Save the PDF as TXT or DOCX. |
| Images and OCR | **Not available yet.** You get a clear message. |

## Limits

- Files can be up to 10 MB. Larger files are refused.
- Up to 200,000 characters of text are kept. The chip says when a document was cut short.
- A DOCX that unpacks to more than 30 MB is refused, as protection against zip bombs.

## Where the text goes

**Extraction:** the text is extracted on the phone. Extracting sends nothing anywhere.

**On this phone mode:** the text, or up to about 3,500 characters of the most relevant sections, is given to the local model. Nothing leaves the phone.

**Online and Auto modes:**

1. Before any document text is sent, a dialog asks **Send this document online?** and names the provider or server.
2. If you choose **No**, the question is not sent at all.
3. If you choose **Send**, up to about 24,000 characters are sent with your question to that provider:
   - the whole document, if it fits;
   - otherwise, the sections that best match your question.
4. You are asked once for each document, in this app session.

**Storage:**

- The document is **not saved**. It stays attached for follow-up questions until you tap **Remove** or start a new chat.
- Your message shows `📎 name` so the chat history records that a document was used. The document text itself is not stored in the chat database, so it is not synced or exported.

**Not implemented:** RAG over a saved document library, embeddings and vector search. The excerpt selection is word-overlap ranking within one attached document.
