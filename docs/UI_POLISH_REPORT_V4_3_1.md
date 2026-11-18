# Abhify.AI 4.3.1: UI polish report

**Build:** `build/Abhify-4.3.1-debug.apk`, versionCode 4030199, SHA-256 `85c2aa76…78ca15`. Same app id and debug key; permissions unchanged.

**Scope:** targeted UI fixes only. No features, AI, Drive, server, database, permission or signing changes.

## Device availability: BLOCKED
- `adb devices` listed **no device**. No emulator is installed on this PC.
- No APK was installed, **no screenshots were taken**, and nothing in this report is a visual observation.
- Every finding below comes from **source-level review** of the 4.3.0 code.

## Screens inspected (source level)
- Launch screen.
- Home shell: header, drawer, bottom bar.
- Chat home (dashboard) and chat messages.
- Documents and document viewer.
- Search everything.
- Settings.
- Personal memory.
- Study (all sections).
- Career (all sections).
- Workspaces.
- Google Drive, Backup and restore.
- All `AlertDialog` call sites (about 60; 23 with plain-text bodies).

## Problems found and fixed
| # | Page / area | Problem (source level) | Severity | Fix |
|---|---|---|---|---|
| 1 | Launch screen, **light theme** | Android's system splash is always navy, but the branded screen followed the app theme. A light-theme user would see a navy-to-white jump at launch. | Medium | The branded screen always uses the dark brand palette (`AbhifyTheme("dark")`), so it continues the system splash seamlessly; the app behind it keeps the user's theme. |
| 2 | 23 dialogs (delete confirmations, Drive conflict, memory contradiction, model size, backup, document consent…) | Body is a plain `Text` without scrolling. Long messages can **clip** on small screens or with large system fonts. | Medium | New shared `DialogText`: scrolls when needed, body-size text, theme colour. Applied to **all 23**. |
| 3 | 3 dialogs (Drive conflict, lesson edit, document action) | Several buttons in a `Row` inside `dismissButton`, which can overflow sideways at large fonts. | Low–Medium | Changed to `FlowRow`, so buttons wrap to a second line. |
| 4 | Settings rows, empty states, bottom bar, dashboard starters | **TalkBack** read decorative emoji before the label (for example "brain, Personal memory"; "speech balloon, Chat"). | Low (accessibility) | Decorative emoji are hidden from accessibility (`clearAndSetSemantics`); the labels are still read. |

### Checked, no change needed
- **Launch screen:**
  - uses the existing logo; "Abhify.AI" and the exact tagline are centred in the safe area with 32 dp margins;
  - about 1.1 s, cold start only, tap skips;
  - doesn't wait on Drive, a server or the model;
  - not lengthened.
- **Dashboard:** same content and actions; two-column starters with equal height; widths fit 320 dp screens (2 × ~150 dp cards).
- **Markdown:** a representative answer was checked by a new test. It covers headings, `<br>` and `<b>`, nested and checkbox lists, code with `__init__`, a table, a link, a quote, a divider, escapes and `snake_case`, and parses into the expected blocks and text. There is still a single renderer.
- **Header and bottom bar:** titles and labels ellipsize instead of clipping; header buttons have descriptions and 48 dp targets.

## Icons: evaluated, not changed
- **Today:** the app uses emoji for the bottom bar, settings rows and some buttons.
- **No Material icons library** (`material-icons-core` or `-extended`) is available in this project or the offline build cache.
  - `icons-core` (small) lacks most of the icons needed (chat, work, school, description, folder), so it would leave a mix of vectors and emoji.
  - `icons-extended` is very large (tens of MB of classes before shrinking; the release build doesn't shrink) — a significant dependency.
  - Hand-writing vector path data can't be visually verified without a device.
- **Decision:** emoji kept, consistently, for now.
- **Recommended, after your approval:** either add a small set of vector drawables as resources (about 12 icons, verified on the phone), or adopt `icons-extended` together with R8 shrinking for release.

## Theme results
- **Source and test level:** dark and light token contrast is tested (accent text and code colours ≥ 4.5:1 in both themes). The launch screen is now theme-independent.
- **Visual check: not tested** (no device).

## Accessibility results
- **Source level:** descriptions on Menu, Search, Settings and Back; headings marked; decorative emoji are silent; dialogs scroll at large fonts.
- **On-device TalkBack and large-font check: not tested.**

## Tests, lint, build
- **JVM:** **226 run, 0 failed, 4 skipped** (the opt-in live tests). The 3 new tests in `V431Test.kt` cover:
  - every plain dialog body uses `DialogText` and no dialog button row is a non-wrapping `Row`;
  - the launch screen uses the dark palette and decorative emoji are silent;
  - a representative Markdown answer.
- **All earlier tests unchanged.**
- **Lint:** 0 errors (82 warnings).
- **Debug build:** successful. Permissions identical, same signing certificate, 0 secret-scan hits.

## Status summary
| Item | Status |
|---|---|
| Launch screen, dashboard, Markdown, dialogs, accessibility labels, themes | **Source-level reviewed** (and unit-tested where possible) |
| Rendering on a phone, screenshots, light/dark visuals, large font, small screen, TalkBack | **Not tested / BLOCKED** (no device) |
| Icon replacement | **Evaluated; pending your decision** |

## Remaining issues
1. **Physical-device review is still needed:** the launch screen, dashboard, a long AI answer, the document viewer, the dialogs at the largest font, and both themes.
2. **Icons:** a decision on vector icons (above).
3. **Dialog bodies with custom layouts** (forms, checklists) already scroll where long. Their individual spacing wasn't redesigned.
