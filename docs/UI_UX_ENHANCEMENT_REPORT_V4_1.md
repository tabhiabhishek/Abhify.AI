# Abhify.AI Android: UI/UX enhancement report (built as 4.3.0)

**Build:** `build/Abhify-4.3.0-debug.apk`, versionCode 4030099, SHA-256 `e8154324…34fbf9`. Same app id and debug key; permissions unchanged. It installs over 4.1/4.2 with `adb install -r`.

**Scope:** presentation only. No new features, no new screens or destinations. No changes to AI, database, backup format, Drive, server, permissions or signing. No new dependencies.

**Audit:** [UI_UX_AUDIT_V4_1.md](UI_UX_AUDIT_V4_1.md).

## 1–2. Inventory and findings
See the audit. In short:
- **Pages:** 26 routes and tabs plus about 60 dialogs.
- **Layout:** six (later found: seven) page wrappers with different gutters, widths and gaps.
- **Branding:** no branded launch screen, and a logo badge with **conflicting taglines**.
- **Markdown:** 13 formatting gaps (lost line breaks, raw `>`, `---`, `[link](…)`, `~~`, `__`, `\*`, `<br>`, flat nested lists, and more).
- **Document viewer:** one 60,000-character Text.
- **Header buttons:** without accessibility descriptions.
- **Light theme:** too-light accent text in about 20 places.

## 3. Launch screen (splash)
- **Sequence:** Android's system splash (the logo on navy, unchanged), then the **branded launch screen** (`ui/Splash.kt`): the logo fades and scales in, then **"Abhify.AI"**, then **"Learn AI. Think Deeper. Build the Future"** (exact text; `BrandText.TAGLINE`, checked by a test).
- **Layout:** centred horizontally and vertically inside the safe area, with 32 dp side margins and a 420 dp text width. It never touches or clips at the edges.
- **Duration:** about 1.1 s in total (`SplashTiming`), then a 260 ms fade to the existing home screen. **Tap to skip.**
- **Nothing waits on it:** the app is composed underneath at the same time, and it doesn't depend on Drive, a server or the AI model.
- **Shown once per cold start** (`savedInstanceState == null`, saved across rotation). It doesn't reappear when navigating or returning to the app.

## 4. Branding
- **`BrandLockup`** (logo mark, **Abhify.AI**, tagline) replaces the old badge on Welcome, About and the unsupported-device screen. The badge's baked-in "Learn • Build • Share • Grow / AI. Code. Community." competed with the official tagline. The **logo mark itself is unchanged**: it's the existing `abhify_logo.png`.
- **The name "Abhify.AI"** is used consistently: header (chat), drawer, "About Abhify.AI", logo descriptions.
- **Palette unchanged:** slate with the violet → pink brand, shared with the web app. No new accent colours. Gradients stay only on the primary button and the user's chat bubble.

## 5. Typography and design system
- **Tokens (`Dimens` in `ui/Theme.kt`):**
  - gutter 16, gap 12, card padding 16, card gap 8;
  - max width 680, touch target 48, row height 56;
  - field radius 12, dialog radius 24.
- **Type:**
  - added **display** (34/40, splash and brand) and **headlineMedium** (28/34);
  - added **CodeStyle** (monospace 14/20);
  - the existing sizes are unchanged and stay in `sp`, so they follow the system font scale.
- **Shared components:**
  - **`PageBody`:** the single page wrapper, now used by **all 7** former wrappers (CPage, LifeScreens Page, ManageScreens Page, AuthColumn, TabPage, Screen, Resume).
  - **`ScreenTitle`:** 56 dp header, 48 dp back button labelled "Back", one-line ellipsized title marked as a heading.
  - **`IconAction`:** glyph buttons with a description and a 48 dp target (☰ Menu, 🔍 Search, ⚙ Settings, Back).
  - **`NavRow`:** a tinted icon tile, 56 dp minimum height, 2-line subtitle (was 1).
  - **`Glass`** cards: padding 16, item gap 8.
  - **`EmptyState`:** icon, title, hint.
  - **`SectionChips`:** one chip row for Career, Study, Knowledge and Work (replaces 4 copies).
  - **`BrandLockup`** and **`BrandText`**.
  - **`SectionTitle`** marked as a heading.

## 6–7. Text formatting and Markdown (root-cause fixes)
**Parser (`chat/Markdown.kt`), new support:**
- **Line breaks** inside paragraphs are kept ("Q: …\nA: …" no longer runs together).
- **Headings 1–6**, trailing `#` removed, "Title\n===" and "Title\n---" headings.
- **Block quotes**, **horizontal rules**, **`~~~` fences**.
- **Lists:** nested lists (depth from indentation), `+` bullets, task lists `- [x]` / `- [ ]`, and indented continuation lines kept in their list item.
- **Tables** without leading pipes. Extra cells are dropped, never shifted.
- **Inline:**
  - `[label](https://…)` links and bare `https://…` links (trailing punctuation excluded);
  - `~~strike~~`, `***bold italic***`, `__bold phrase__`;
  - backslash escapes.
- **Common HTML from models:** `<br>`, `<b>/<strong>`, `<i>/<em>`, `<code>`, `<s>`, `<p>`, `<u>` become their meaning. Other tags stay **visible as text**; HTML is never rendered. Inside `code` nothing is changed.
- **Bug fixed:** `__init__` (and any `_x_` right after another `_`) was half-italicised. That bug also existed in earlier versions.

**Renderer (`ui/MarkdownText.kt`):**
- Styled text is **cached per text and theme**. It was rebuilt on every recomposition, i.e. every streamed token.
- **Spacing by block type:** more space before headings, tight spacing within lists.
- **Headings:** a clear size per level, and marked as headings for TalkBack.
- **Lists:** nested bullets (• ◦ ▪ ▫) indented 18 dp per level; numbers keep their real values; tasks show ☑ / ☐ (done items struck through).
- **Quotes:** an accent bar with muted italic text. **Rules:** a divider.
- **Links:** underlined and tappable (they open in the browser).
- **Code:** inline code gets a code background. Code blocks get theme-aware syntax colours (light variants added; all ≥ 4.5:1, tested), a larger "Copy" target (still under 48 dp) and horizontal scrolling.
- **Tables:** column widths sized to content (72–240 dp), row dividers, sideways scrolling.

**Other text:**
- **Document viewer:** paragraphs are rendered lazily (only visible ones are laid out), "Page N" shown as small headings, paragraph spacing. Same content and the same 60,000-character limit.
- **Bottom bar labels** ellipsize instead of clipping. The header title is one line.

## 8. Main dashboard (chat home)
- **Same elements and actions:** greeting, mode explanation, status chips, provider cards and the same 4 starter prompts.
- **New visual hierarchy:**
  - smaller logo; the explanation width is limited for readability;
  - centred chip row;
  - a **"Try asking"** heading;
  - the starters as a **two-column grid of equal-height cards** with a 💡 icon.

## 9. Page-by-page status
"Shared" = improved through the shared wrapper, header, cards, chips, NavRow and Markdown. "Specific" = also changed on that page. **No page was tested on a device.**

| Page | Audited | Updated | Tested |
|---|---|---|---|
| Launch screen (new presentation of existing start-up) | ✓ | Specific | JVM (text, timing, cold-start rule); not on device |
| Welcome / Profile / Login / Register / Forgot / Link | ✓ | Shared (`PageBody`); Welcome specific (brand lockup, no old badge) | Not on device |
| Setup wizard | ✓ | Shared (cards, NavRow) | Not on device |
| Home shell: header, drawer, bottom bar | ✓ | Specific (header buttons and descriptions, name, bar sizing) | Not on device |
| Chat home (dashboard) | ✓ | Specific | Not on device |
| Chat messages, answers | ✓ | Markdown renderer | JVM (parser); not on device |
| Career: Dashboard, Resumes, Jobs, Match, Interview, Question bank | ✓ | Shared; tracker empty state; accent tokens | Not on device |
| Study: Study dashboard, Tutor, Plans, Cards, Quiz, News | ✓ | Shared; Plans and Cards empty states; chips | Not on device |
| Documents and document viewer | ✓ | Specific (viewer, empty state) | Not on device |
| Knowledge (vault), Workspaces (Tasks, Notes, Meeting, Knowledge, Write, Linked) | ✓ | Shared; vault, notes and linked empty states | Not on device |
| Settings tab | ✓ | Shared (NavRow) | Not on device |
| History | ✓ | Shared; empty state | Not on device |
| Personal memory (and review, overview) | ✓ | Shared; empty state | Not on device |
| Models, Providers, Answer style, Account, Sync, Privacy | ✓ | Shared (`PageBody`, header) | Not on device |
| About | ✓ | Specific (brand lockup, version) | Not on device |
| Resume assistant | ✓ | Shared (now `PageBody`) | Not on device |
| Google Drive, Storage, Backup, Semantic search, Search everything, Connection mode | ✓ | Shared; search and linked empty states | Not on device |
| Dialogs (~60) | ✓ (pattern review) | Dialog corner radius from the theme; long dialogs already scroll | Not on device |

## 10. Navigation
- **No destinations added or removed.** The behaviour is unchanged: the same back stack, tabs and drawer.
- **Presentation:**
  - header buttons have descriptions and 48 dp targets;
  - the bottom bar has 20 sp icons and semibold selected labels that ellipsize;
  - one chip style for in-tab sections.
- **No new page transitions were added.** The existing instant switches were kept, to avoid slowing navigation.

## 11. Dark and light themes
- Both modes keep their token sets.
- **Accent text** now uses `colorScheme.tertiary`. It's #A78BFA in dark mode (6.6:1) and #7C3AED in light mode (5.5:1); the previous light-mode value was too light.
- **Code colours** have light-mode variants.
- Everything is verified by contrast tests on the token values. It was not visually checked on a device.

## 12. Accessibility
- Descriptions on Menu, Search, Settings and Back, and on the launch screen (reads the name and tagline; "Skip").
- Headings marked for TalkBack.
- 48 dp targets for header buttons; a larger code "Copy" target (under 48 dp).
- NavRow at least 56 dp tall.
- Text sizes in sp, so the system font scale applies.
- Status is never colour-only (icons and text remain).

## 13. Performance
- Markdown styling is cached; on a streamed answer it's computed once per update instead of on every recomposition pass.
- The document viewer is lazy instead of one 60k-character layout.
- The launch screen doesn't delay initialization (it runs in parallel and can be skipped).
- Nothing else was changed (chat was already a LazyColumn).

## 14. Files modified
- **New:** `ui/Splash.kt`.
- **Changed:**
  - `ui/Theme.kt` (Dimens, type, code colours, dark flag);
  - `ui/Components.kt` (BrandText, BrandLockup, PageBody, IconAction, EmptyState, SectionChips, Glass, NavRow, ScreenTitle, SectionTitle, Field);
  - `ui/MarkdownText.kt` (rewritten), `chat/Markdown.kt` (rewritten, compatible);
  - `ui/MainActivity.kt`, `ui/HomeScreens.kt`, `ui/V3Screens.kt`, `ui/OnboardingScreens.kt`, `ui/ManageScreens.kt`, `ui/LifeScreens.kt`, `ui/CareerScreens.kt`, `ui/ResumeScreens.kt`, `ui/V34Screens.kt`, `ui/V38Screens.kt`, `ui/V39Screens.kt`, `ui/V40Screens.kt` (wrappers, empty states, accent tokens);
  - `build.gradle.kts` (version only).
- **Tests:** `V43Test.kt` (new).

## 15–19. Tests and build
- **JVM:** **223 run, 0 failed, 4 skipped** (opt-in live tests). The 7 new tests cover:
  - Markdown: line breaks, quotes, rules, headings 5–6 and setext, nested, task and plus lists, continuations, links, bare URLs, strike, bold-italic, `__` vs `__init__`, escapes, HTML handling, `~~~` fences, tables without pipes, speech output;
  - the exact brand text and tagline, splash timing, cold-start only, no old badge;
  - one page wrapper; no hard-coded accent text; contrast of accent and code colours in both themes.
- **All earlier tests pass unchanged,** including the 2.x Markdown tests.
- **Compose UI tests:** not added. The project has no Compose UI-test dependency, and adding one is a new dependency; it can be done on request.
- **Lint:** 0 errors (82 warnings, mostly KTX style and newer-dependency notices).
- **Debug build:** successful. Permissions are identical to 3.4.0, and the secret scans of the APK and the source have 0 hits.

## 20. Physical device
**Not performed.** No device was connected (`adb devices` was empty).

The launch screen, layouts, both themes, large font scales and small screens all need a check on the phone. A checklist:
1. Cold start: system splash, then the branded screen (logo, Abhify.AI, tagline), then home in about 1 s. Tap skips it.
2. Rotate or return to the app: no splash.
3. An AI answer with lists, code, a table, a link, a quote and line breaks.
4. Documents → view a PDF: page headings, smooth scrolling.
5. Settings → Theme light: accent text and code readable.
6. System font size at maximum: headers, bottom bar, NavRows.
7. Every tab and drawer entry opens as before.

## 21. Remaining issues (not done)
- **Emoji icons:** the bottom bar, NavRows and chips still use emoji. Switching to vector icons would need the Material icons dependency or new vector assets. It's a visual-identity decision for you.
- **Dialogs:** about 60 use Material defaults. Their content wasn't individually re-laid out (they already scroll where long).
- **Helper-text copies:** several screens still have private copies (`Muted`, `Hint`, `Note`) with slightly different sizes.
- **Not visually verified on a device** in either theme.
