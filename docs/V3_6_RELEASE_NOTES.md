# Abhify.AI Android 3.6.0: release notes

**Build:** debug build, versionCode 3060099. App ID `ai.abhify.android`, same debug signing key.

**Database:** v10 → v11 (additive: an empty `chunk_vectors` table).

## New
- **Semantic search infrastructure (optional, off).**
  - Embedder interface, vector storage, hybrid ranking (BM25 + cosine, Reciprocal Rank Fusion).
  - Vectors are recomputed only for changed chunks.
- **Settings → Semantic search.** Shows that keyword search is active, the evaluated model, and its download size and RAM cost. The download button is disabled pending approval.
- **Evaluation.** A 31-query retrieval evaluation, measured on the dev PC with an already-installed embedding model:
  - hybrid Recall@5 0.944 vs keyword 0.870, on a small set;
  - details in [V3_6_SEMANTIC_SEARCH_EVALUATION.md](V3_6_SEMANTIC_SEARCH_EVALUATION.md).

## Not done (approval needed)
- No embedding model is downloaded or bundled. Retrieval in the app is **unchanged keyword search**.
- No native (JNI) change.

## Tests
- 185 JVM tests: 0 failed, 4 skipped (the 3 live-network tests plus the opt-in evaluation). There are 5 new tests.
- The evaluation itself was run separately with `ABHIFY_EMBED_EVAL=1`.
