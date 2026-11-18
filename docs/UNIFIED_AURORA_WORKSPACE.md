# Unified Aurora workspace (development consolidation, 2026-11-09)

Android build `build/unified/Abhify-4.6.1-unified-preview-debug.apk` (still 4.6.1 / 4060199); desktop/web 2.0.0-dev.8 (installer `dist/windows/2.0.0-dev.8/`). Build details: `build/unified/BUILD_MANIFEST.md`.

## Brand launcher
**Desktop/web:**
- **Where it opens:** clicking the **Abhify brand** at the top of the sidebar. In the 600–1023 px icon rail and in the collapsed sidebar, it's the logo; on phones (< 600 px), the **header logo**.
- **What it shows:** every implemented destination, in three groups.
- **Closing:** the brand again, a click outside, **Esc**, or choosing a destination.
- **Keyboard:** arrow keys move between items; focus returns to the brand.
- **Selected state:** the current page is marked (`aria-current`).
- **Status line:** shows the chat model and AI status from state the page already has.
- **No side effects:** opening the launcher makes **no request** (measured: 0) and loads no model.

**Android:**
- The **Abhify logo** in the header opens the same grouped destinations as a bottom sheet.
- The bottom bar (Home · Chat · Study · News · More) and More are unchanged.
- The sheet closes before it navigates, and back navigation works as before.

### Route mapping (existing pages only)
| Group | Destination | Desktop/web opens | Android opens |
|---|---|---|---|
| Workspace | Home | Home tab | Home tab |
| | Chat | Chat tab | Chat tab |
| | AI Task Runner | Task Runner page | Route `TASKS` |
| | Study | Interview and Quiz tabs (the web's study pages) | Study tab |
| | News | News tab | News tab |
| | Documents | Knowledge folder page (documents and knowledge are one folder on the desktop) | Documents |
| | Knowledge | (same as Documents) | Knowledge tab |
| | Memory | Memory & history page | Route `MEMORIES` |
| AI System | AI Models | Models & setup (existing model manager) | Route `MODELS` |
| | Performance | Performance page | AI models screen (its Performance section) |
| | System | System & data page | More (System group) |
| Application | Settings | Settings dialog | Answer style & personalization (route `SETTINGS`) |
| | Profile | n/a (the web has no profile page) | Account (route `ACCOUNT`) |
| | About | n/a on the web | About (route `ABOUT`) |

The **command palette** (Ctrl+K) keeps every command and adds **AI Models**, **Documents & Knowledge folder** and **Open the Abhify navigation**.

## Aurora refinements (same design system)
- **New tokens** (dark and light, in `styles.css`):
  - `--sidebar`: a translucent midnight gradient (white-lavender in light mode);
  - `--focus`: a cyan focus ring;
  - `--glow`: a restrained ring and soft violet glow, used only for the active navigation item and the launcher.
- **Launcher style:** feature-coloured icon tiles; respects reduced motion.
- **Home:** quick actions (Chat, New AI task, Study, News) next to the greeting. Card order and hidden cards are unchanged.

## Build output and cleanup
The cleanup was done only after the unified build passed and its SHA-256 was verified. Report: `build/unified/CLEANUP_REPORT.md`.

**Removed** (explicit list, inside `build/` only; each logged with its SHA-256): the superseded intermediate previews
- `Abhify-4.6.0-debug.apk` (+ its instrumentation APK);
- `Abhify-4.6.1-debug.apk` (+ its instrumentation APK);
- `Abhify-4.6.1-news-preview-debug.apk`;
- `Abhify-4.6.1-tasks-preview-debug.apk`.

**Kept:**
- `build/unified/` (the unified APK, instrumentation APK, checksums, manifest, report);
- the **4.5.1 rollback** (the last build validated on the phone);
- `build/windows/` (used by the installer build);
- evidence logs, sandbox results and the source snapshot.

**Waiting for your decision:** the 12 older `Abhify-Android-Test-*.zip` kits and `taskb-input/` (Ollama installer copy and a sample).

**Not touched:** source, tests, docs, Git, databases, model weights, Ollama models, user data, settings.

## Tests
| Test | Result |
|---|---|
| Android `V48Test` | Launcher lists every major destination using existing tabs and routes only; opening it starts nothing; reached from the header logo; bottom bar unchanged |
| Browser `launcher_e2e.mjs` (17 checks) | Open and close by brand, outside click, Esc; focus; current page marked; 0 requests and no model on open; all 12 destinations open their existing page; model unchanged; palette; collapsed sidebar; 820 px rail; 390 px phone; reduced motion |
| Regressions | Web 43/43, Task Runner 11/11 (live Ollama), backend 347 passed + 1 opt-in skipped, Android 275/0/4, lint 0 errors |

## Limitations
- **Phone:** no physical Android device was connected, so the Android launcher is verified by unit tests and the build only.
- **Web:** has no Profile or About page, so the launcher lists none. Documents and Knowledge are one page on the desktop.
- **Desktop installer:** built (unsigned, like earlier builds), not installed or run in this session.
