# Vendored: Mermaid (diagrams in Study lessons)

The Study lesson screen uses Mermaid (`frontend/study-ui.js`) to draw a lesson's ```` ```mermaid ```` diagram. It is loaded lazily, only when a lesson contains a diagram.

These files are **not modified**. `test_study_plus.py` checks every hash below. Owner approved this library on 2026-11-23.

| File | Source | Version | SHA-256 |
|---|---|---|---|
| `mermaid-11.4.1.min.js` | npm `mermaid@11.4.1`, `dist/mermaid.min.js`. Registry integrity `sha512-Mb01JT/x6CKDWaxigwfZYuYmDZ6xtrNwNlidKZwkSrDaY9n90tdrJTV5Umk+wP1fZscGptmKFXHsXMDEVZ+Q6A==`, verified | 11.4.1 | `a43bc1afd446f9c4cc66ac5dd45d02e8d65e26fc5344ec0ef787f88d6ddb6f9e` |
| `LICENSE` | from the npm package | MIT | `ec9fb67dcb25eccc416ed56e1aab819222c805a2a4bfe4cb19e7556bf2ffde80` |

**Safety settings:**
- `securityLevel: 'strict'`: no click handlers, and HTML labels are escaped.
- `startOnLoad: false`.
- Diagram text comes from the local model's lesson.
- If a diagram fails to render, the text is shown instead.

**Network:** none at run time.

**Updating:**
1. Download the new version.
2. Verify the npm integrity hash.
3. Replace the files.
4. Update this table and the test.
