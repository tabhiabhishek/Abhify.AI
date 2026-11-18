# Abhify.AI premium UI/UX redesign: audit and design specification

**Status:** approved 2026-11-06. **Pass 2 done 2026-11-09 (Android 4.5.1, web dev.8).** Pass 1: Android 4.5.0 (phases 1–4), desktop/web 2.0.0-dev.8 (tokens, themes, shell, launch, model picker). See the tracking table in section G for what each page received.

**Scope:** the Android app (4.4.0) and the desktop/web PWA (2.0.0-dev.7).

The cloud service has no UI and is out of scope. Ollama is used only by the desktop app and the cloud server; the Android app runs its model on the phone with llama.cpp. Both stay as they are.

---

## A. Audit

### A.1 Architecture as found
| | Android (`android/`) | Desktop/web (`frontend/` + `backend/`) |
|---|---|---|
| **UI technology** | Kotlin, Jetpack Compose, Material 3; one activity | Plain HTML/CSS/JS PWA, no framework, no build step, offline-first service worker |
| **Navigation** | Own back stack (`AppViewModel.Route`, 31 routes) + 5 bottom tabs (+ Settings as a hidden tab) + drawer | 4 top tabs (News, Interview, MCQ, Chat) + 6 bottom "sheets" opened from the header ⚙ |
| **Theme** | Tokens in `ui/Theme.kt` (`Brand`, `Extra`, `Dimens`); **dark, light, system** | CSS variables in `styles.css`; **dark only** |
| **Brand** | `abhify_logo.png` (the "A" mark); slate background + violet→pink accent (shared with the web) | `brand/logo-mark.svg/.png`, `logo.svg`; the same slate/violet/pink tokens |
| **Icons** | Material outlined vectors in the bottom bar and header (4.4); emoji elsewhere | Emoji everywhere |
| **Launch** | System splash (logo on navy) → `BrandSplash` (about 1.1 s, cold start, tap to skip) | None (the page renders immediately) |
| **Tests guarding the UI** | 231 JVM tests, including UI source checks (tokens, contrast, dialogs, splash) | `test_pwa.py` (10) and `test_pwa_behaviour.py` (9): manifest, icons, `--tap: 44px`, safe-area, offline text, element IDs, status texts. **The README guard tests** need the API table and version. |

### A.2 Page inventory: Android (every destination)
| # | Page | Route / tab | Purpose | Reached from |
|---|---|---|---|---|
| 1 | Launch | system splash + `BrandSplash` | Brand on start | App start |
| 2 | Welcome / Profile / Login / Register / Forgot / Link | WELCOME… | First run, optional account | First start; Connection mode |
| 3 | Setup wizard (8 steps) | SETUP | Device, AI mode, model, providers, OCR, Drive, privacy | After the first run; Settings → Run setup again |
| 4 | **Chat** (empty state = today's home) | Tab CHAT | Ask, documents and workspace context, prompts | Bottom bar |
| 5 | Career: Dashboard, Resumes, Jobs, Match, Interview, Question bank | Tab CAREER (6 sections) | Career copilot | Bottom bar |
| 6 | Study: Study (dashboard), Tutor, Plans, Cards, Quiz, News | Tab STUDY (6 sections) | Learning | Bottom bar |
| 7 | Documents + text viewer + PDF pages | Tab DOCS | Library, Q&A | Bottom bar |
| 8 | Knowledge: My Knowledge; Workspaces (Tasks, Notes, Meeting notes, Knowledge, Write, Linked) | Tab KNOWLEDGE | Vault and workspaces | Bottom bar |
| 9 | Settings hub | Tab SETTINGS | All settings rows | Header ⚙ |
| 10 | History | HISTORY | Conversations | Drawer, Settings |
| 11 | Personal memory | MEMORIES | Memory | Drawer, Settings, chat chip |
| 12 | Models on this phone | MODELS | Local AI | Drawer, Settings |
| 13 | AI providers | PROVIDERS | Online keys | Drawer, Settings |
| 14 | Answer style, instructions, voice, theme | SETTINGS (route) | Preferences | Settings |
| 15 | Account / Sync & network / Privacy / About | ACCOUNT, SYNC, PRIVACY, ABOUT | — | Drawer, Settings |
| 16 | Resume assistant (3.1) | RESUME | Older résumé tool | Documents card |
| 17 | Google Drive / Storage / Backup & restore / Semantic search / Connection mode | DRIVE, STORAGE, BACKUP, SEMANTIC, CONNECTION | — | Settings |
| 18 | Search everything | SEARCH | Unified search | Header 🔍, Linked view |
| 19 | Overlays | — | About 60 dialogs, consent, OCR question, task status, prompts, workspace picker | Various |

### A.3 Page inventory: desktop/web
| # | Page | Element | Purpose |
|---|---|---|---|
| 1 | Header | `.header` | Logo, greeting, MCQ score, install, ⚙ |
| 2 | News | `#tab-news` | AI news (offline cache) |
| 3 | Interview | `#tab-interview` | Generated interview questions |
| 4 | MCQ | `#tab-mcq` | Quizzes, score, streak |
| 5 | Chat | `#tab-chat` | Agent chat (documents, memory, web), voice |
| 6 | Settings sheet | `#settingsSheet` | Name, where the AI runs (server / on-device), backend URL, access token, test |
| 7 | Setup wizard | `#setupSheet` | Hardware check, model choice, downloads with confirmation |
| 8 | Memory & history | `#memorySheet` | Preferences, memories, delete |
| 9 | System & data | `#systemSheet` | Updates, **companion access (members, invitations)**, backup/restore, move data |
| 10 | Knowledge folder | `#knowledgeSheet` | Folder watcher, rescan, retry |
| 11 | Voice overlay, offline banner, toasts | — | — |

There are **no** desktop pages for documents, a prompt library, model management or user management beyond the above. They are not added (that would be new features).

### A.4 Navigation findings
**Android:**
1. **Duplicate destinations.** The drawer repeats 8 Settings rows (Memories, Models, Providers, Account, Sync, Privacy, Settings, About). Meanwhile Drive, Backup, Storage, Connection and Search are **not** in the drawer.
2. **Two things called "Settings":** the Settings hub (tab) and "Settings" (the answer-style route). The labels are confusing.
3. **No home:** the chat empty state does the job of a dashboard, and it disappears once a chat is open.
4. **Hidden in sub-sections:** News inside Study; Search only from one header icon.
5. **Overlap:** the legacy Resume assistant overlaps Career → Resumes; it's reachable only from a Documents card.
6. Active states and back navigation work (source review); there are no broken routes (the `when` over `Route` is exhaustive at compile time).

**Desktop:**
1. **No sidebar and no home.** The 4 tabs are learning tools; Knowledge, Memory, System and Companion access are hidden behind buttons inside the Settings sheet.
2. **No light theme.**
3. **Emoji icons**, inconsistent sizes.

### A.5 Visual, UX and accessibility findings (short)
- **Android:** 4.3–4.4 already fixed spacing, typography, Markdown, dialogs and contrast. What remains:
  - emoji tiles in rows;
  - a gradient used on many primary buttons;
  - "glass" cards everywhere, so cards look alike;
  - no motion system;
  - no reduced-motion handling for the launch screen.
- **Desktop:**
  - glass on every surface;
  - radial glow background;
  - sheets used for whole features (cramped on desktop);
  - focus styles inconsistent;
  - no `prefers-reduced-motion` or `prefers-color-scheme` handling.
- **Not verified visually on a device:** Android 4.3+, and the desktop in a browser in this pass.

---

## B. Design system

### B.1 Colours
The proposed palette is adopted with contrast-safe variants. These are **tokens**, used by role.

| Token | Dark | Light | Use |
|---|---|---|---|
| `brand` (primary) | **#635BFF** (buttons, active indicator; white text on it 4.6:1) | #635BFF | Primary actions, active navigation, selection |
| `brandText` | #8F89FF (on navy ≥ 4.5:1) | #4B44D6 (on #F7F7FC ≥ 4.5:1) | Brand-coloured **text and links** |
| `lavender` | #A78BFA | #7C3AED | Secondary accent (tags, AI labels) |
| `cyan` | #22D3EE | #0E7490 | **Sparingly:** AI-status dot, "local AI ready", selected highlights |
| `bg` | **#171827** | **#F7F7FC** | App background (flat; no radial glows) |
| `surface` | #1F2033 | #FFFFFF | Cards, sheets |
| `surfaceRaised` | **#25263A** | #FFFFFF + shadow | Dialogs, menus, composer |
| `border` | #33354D | #E4E4EF | Card and field borders |
| `text` / `textMuted` | #F1F2F8 / #A3A6BF | #171827 / #5B5E78 | Body / secondary (≥ 4.5:1) |
| `success` / `warning` / `error` / `info` | #34D399 / #FBBF24 / #F87171 / #60A5FA | #047857 / #B45309 / #B91C1C / #1D4ED8 | Status (always with an icon or word) |
| `brandGradient` | #635BFF → #A78BFA (→ a subtle #22D3EE tip) | same | **Only** on: the launch reveal, the home AI composer, the single primary CTA per screen, and the user's chat bubble |

**Retired:** the pink accent (#EC4899) and the radial glow backgrounds. The pink-violet gradient on every primary button becomes a solid `brand`. Glass translucency is kept only for the bottom bar and top bar over scrolling content.

### B.2 Typography (Inter, existing font)
| Style | Size / line height / weight | Use |
|---|---|---|
| Display | 34/40/700 | Launch, home greeting |
| Page title | 24/30/700 | Screen headers |
| Section title | 18/24/650 | Groups within a page |
| Card title | 16/22/600 | Cards and rows |
| Body | 16/24/400 | Text, chat answers |
| Body small / secondary | 14/20/400 | Descriptions |
| Caption / label | 12–13/16/500 | Meta, chips, navigation labels |
| Code | Monospace 14/20 | Code blocks and inline code |

Android: existing Material roles, mapped to the scale (sp, so the font scale applies). Web: CSS variables `--fs-*`, `--lh-*`.

### B.3 Spacing, radius, elevation, icons
- **Spacing:** 4-pt scale: 4, 8, 12, 16, 24, 32, 48. Page gutter 16 (phone) and 24–32 (desktop); card padding 16 (20 on desktop); card gap 12–16.
- **Radius:** chip 999, field 12, card 16, sheet/dialog 24, composer 20.
- **Elevation:**
  - dark: borders plus a slight surface lift, no glows;
  - light: shadow-1 `0 1px 2px rgba(23,24,39,.06), 0 1px 3px rgba(23,24,39,.08)`, shadow-2 for menus and dialogs.
- **Icons:** **Material Symbols outlined** (Apache-2.0) everywhere: Android vector drawables (as in 4.4), web inline SVG sprites. Sizes 20 (inline), 24 (navigation), with a 48 dp/px touch target. Emoji are removed from navigation and settings rows (kept only in user content and empty-state art where friendly).

### B.4 Motion
| Token | Duration | Easing | Use |
|---|---|---|---|
| `fast` | 120 ms | standard (0.2, 0, 0, 1) | Press, hover, toggles |
| `base` | 200 ms | standard | Sidebar collapse, tab switch, chips |
| `emphasis` | 320 ms | emphasized-decelerate (0.05, 0.7, 0.1, 1) | Dialogs, sheets, card enter |
| `launch` | ≤ 900 ms total | emphasized | Launch only |

- **Chat:** new messages fade and rise 8 dp once. Streaming text is never delayed; a soft caret is shown while generating.
- **Reduced motion:** Android `ANIMATOR_DURATION_SCALE == 0`, web `prefers-reduced-motion`. All motion becomes instant cross-fades; the launch shows a static logo for at most 300 ms.

### B.5 Components (reuse first)
| Component | Android (exists → change) | Web |
|---|---|---|
| AppShell | `HomeScreen` scaffold → new navigation structure (C) | New `.shell` grid (sidebar + main) |
| Sidebar / NavigationItem / SidebarGroup | Drawer `NavRow` → grouped navigation sheet with icons | **New** sidebar |
| PageHeader | `ScreenTitle` (exists) → add an optional subtitle and action slot | New `.page-header` |
| BentoCard | **New** (wraps `Glass` → `Card`) | New `.bento` |
| Buttons (Primary / Secondary / Icon) | `GradientButton` → solid brand; `IconAction` (exists) | `.btn` restyle |
| SearchInput | `Field` (exists) + leading icon | `.field` + icon |
| AIComposer | Chat composer (exists) → restyle; reused on Home | Chat input → restyle; reused on Home |
| MessageBubble | `Bubble` (exists) → user = brand gradient, AI = no card (plain text on background) | `.bubble` restyle |
| StatusIndicator | `StateChip`, `StatusLine` (exist) → cyan "AI ready" dot | New |
| Empty / Loading / Error states | `EmptyState` (exists) → illustrated variant | New |
| ConfirmationDialog | `Confirm` and `DialogText` (exist) | `<dialog>` restyle |
| SettingsSection | `Glass` + `NavRow` → `SettingsGroup` with a header | Sheet sections → grouped |
| ModelCard, DocumentCard, PromptCard | Existing rows → card styling | — |
| ThemeSelector | Exists (dark, light, system) | **New** (dark, light, system), saved in localStorage |

---

## C. Navigation architecture

### C.1 Android (mobile-first, not a shrunken desktop)
**Proposed (option N1, recommended):**
- **Bottom bar (5):** **Home** · Chat · Study · Documents · **More**.
- **"More"** opens a grouped navigation sheet (replacing today's drawer and the "Settings" tab):

| Group | Destinations |
|---|---|
| **Workspace** | Career, Knowledge & workspaces, History, Search, Saved prompts |
| **AI** | Models on this phone, AI providers, Answer style & personalization, Personal memory, Semantic search |
| **Data** | Google Drive, Backup & restore, Storage |
| **System** | Connection mode, Account, Sync & network, Privacy, Run setup again, About |

- **Header:**
  - Home: logo, greeting, 🔍, profile/theme.
  - Other tabs: page title, 🔍.
  - The ☰ drawer is removed (its content moves into More).
  - Back works as today (`BackHandler` and the back stack).
- **Duplicates removed:** each destination appears **once** in More (plus contextual shortcuts on Home). "Settings" is renamed to "Answer style & personalization".
- **The legacy Resume assistant** stays reachable: Career → Resumes → "Classic résumé assistant".
- **Option N2 (alternative):** keep Chat · Career · Study · Documents · Knowledge and put the bento Home inside Chat's empty state. Less change, but no real home.

### C.2 Desktop/web (hybrid)
- **≥ 1024 px:** a **left sidebar**, 264 px expanded / 72 px collapsed (preference in localStorage, 200 ms transition, tooltips when collapsed, a 3 px brand bar and tinted background for the active item).

| Group | Items |
|---|---|
| Brand | Logo, "Abhify.AI", collapse button |
| Main | **Home** · Chat · News · Interview · Quiz (MCQ) |
| Knowledge | Knowledge folder · Memory & history |
| System (bottom) | System & data (updates, backup, companion access) · Settings · Theme toggle |

- **600–1023 px:** the collapsed rail by default.
- **< 600 px (phone browser / installed PWA):** a bottom bar (Home, Chat, News, Quiz, More) plus a More sheet. **The PWA test contract is kept:** `--tap: 44px`, safe-area insets, element IDs, offline banner text.
- **Pages instead of sheets:** Knowledge folder, Memory & history, and System & data become full pages in the main area on desktop, and sheets on phones (the same DOM sections and IDs, different placement). Settings stays a dialog.
- **No breadcrumbs:** the hierarchy is at most 2 levels. A page header with a back arrow is used for sub-pages.

---

## D. Page-by-page redesign (summary)
| Page | Platform | New layout | Kept unchanged (functionality) |
|---|---|---|---|
| **Home (new presentation, existing data)** | Android, Web | **Bento grid:** hero "Ask Abhify.AI" composer (2×2, the only big gradient) → sends to the real chat. Then: recent conversations (2×1), continue studying (due cards / next lesson, from `StudyInsights`), recent documents, local AI status (model, ready / not installed → Models), Drive status (if connected), saved prompts (top 3), shortcuts (Career, Knowledge, Search). Empty states per card. | All data from existing stores; **no invented metrics** |
| Chat | Both | Focused workspace: slim header (title, model/mode chip, ⋯ menu); AI answers **without a card** (text on the background, max line length about 72 characters on desktop); user bubble with the brand gradient; actions row on hover/long-press; composer surface raised (attach, voice, prompts, send/stop). | Streaming, stop, regenerate, continue, edit, consent, documents, workspace context, voice |
| History | Android (page), Web (inside Memory & history) | Grouped by Today / This week / Older; search on top; row menu (rename, pin, archive, export) | All actions |
| Documents | Android | Search, filters, sort, **grid/list toggle**; document cards with type icon, pages, index status chip; viewer sheets | Import, OCR, Q&A, index, PDF pages |
| Knowledge / Workspaces | Android | Two-pane on tablets; a workspace header with sections as tabs | All |
| Knowledge folder | Web | A status card (watching / paused, counts), file table with status chips, actions bar | Watcher API |
| Saved prompts | Android | A page (More → Saved prompts) with search and cards, plus the existing chat sheet | CRUD |
| Models | Android | Model cards (size, RAM fit badge, installed / active, self-test), download progress bar | Downloads and confirmations |
| Setup wizard | Both | Stepper with progress, one decision per step, the same confirmations | All safety checks; **no automatic downloads** |
| Settings | Both | Grouped sections: Appearance, AI & models, Chat, Documents, Privacy, Data, Account, Advanced | Every setting and its meaning |
| Career, Study (all sections) | Android | Section tabs → segmented control; dashboards use bento cards; lists use cards with clear status | All |
| Drive, Backup, Storage, Connection, Account, Sync, Privacy, About | Android | Page header plus `SettingsGroup` sections; status at the top | All |
| News / Interview / MCQ | Web | Content cards on clean surfaces; a quiz card with progress; offline state | Offline cache, generation |
| System & data, Companion access | Web | Sections: Updates · Devices & invitations (member list, invitation create/expiry/revoke, statuses) · Data (backup, restore, move) | Membership authorization, no tokens shown |
| Dialogs (about 60 on Android, about 6 on web) | Both | `surfaceRaised`, radius 24, title, scrolling body (`DialogText`), primary on the right | Behaviour |

The full per-page checklist is in section G.

---

## E. Launch animation
| | Android | Desktop/web |
|---|---|---|
| **Stage 1** | System splash (unchanged mechanism), **now theme-aware**: navy #171827 (dark) or #F7F7FC (light) via `values` / `values-night` | Page loads with a theme-correct background (inline critical CSS: no flash of the wrong theme) |
| **Stage 2 (≤ 900 ms)** | `BrandSplash`: logo fade + scale 0.94 → 1 (320 ms) → a brand-gradient ring sweeps once behind the logo (300 ms) → "Abhify.AI" + tagline fade in (200 ms) → cross-fade to the app (200 ms). It ends as soon as the timeline ends; tap skips; **never waits for Drive, server or model.** | First load per session only (sessionStorage): the logo mark fades and scales in the sidebar brand slot while the shell fades in (≤ 600 ms). Never on route changes. |
| **Reduced motion** | Static logo plus name, 300 ms, no scale or ring | Instant |
| **Repeats** | Cold start only (as now) | Once per session |

The logo asset is unchanged (`abhify_logo.png` / `logo-mark.svg`). The improvements are size, padding, contrast ring and a crisp vector rendering on the web.

---

## F. Implementation roadmap (after approval)
| Phase | Work | Files (main) | Risk | Validation |
|---|---|---|---|---|
| 1 Design system | Tokens (B), both themes, motion tokens, icon set download (about 40 Material Symbols) | Android `ui/Theme.kt`, `Components.kt`, `res/drawable/ic_*`, `res/values(-night)/colors.xml`; Web `styles.css`, `icons.svg` | Contrast regressions → tests | Contrast tests for every token pair; PWA tests |
| 2 Shell and navigation | Android bottom bar + More sheet (N1); Web sidebar, rail, bottom bar | `HomeScreens.kt`, `AppViewModel.Tab`, `MainActivity.kt`; `index.html`, `app.js` (navigation only) | Moved destinations; PWA ID contract | Test: every Route reachable from More or Home; PWA behaviour tests |
| 3 Launch | Theme-aware system splash; ring animation; reduced motion; web intro | `themes.xml`, `values-night`, `Splash.kt`; `index.html`, `styles.css` | Startup time | Splash timing test, no blocking |
| 4 Home | Bento (both) from existing data | New `HomeDashboard.kt`; `app.js` home section | Fake data (forbidden) → source check | Every card bound to an existing store |
| 5 Chat | Layout, bubbles, composer, actions | `HomeScreens.kt` (chat), `MarkdownText.kt` styles; web chat CSS | Streaming behaviour | Existing chat tests, manual streaming |
| 6 All other pages | Per section G | All `ui/*Screens.kt`; web sheets → pages | Breadth | The section G checklist |
| 7 Personalization | Recent items, shortcuts (existing data only) | Home | — | — |
| 8 Responsive and accessibility | Large font, narrow screens, keyboard focus (web), TalkBack | — | — | Tests and a device check |
| 9 QA | All suites, builds | — | — | Report |

- **Delivery:** Android 4.5.0 (one debug build per 2–3 phases, so you can check on the phone), and web 2.0.0-dev.8 (source).
- **No new dependencies:** icons are resources or inline SVG; motion uses Compose/CSS.
- **Database:** no change.

---

## G. Acceptance criteria and per-page checklist
**Global criteria (measurable):**
- Every token pair used for text is ≥ 4.5:1 (tested).
- Every Route and section is reachable from Home, the bottom bar or More (tested).
- No emoji in navigation or settings rows.
- One solid primary action per screen; the gradient appears only in the 4 approved places (source check).
- The launch is ≤ 900 ms and never waits on I/O; reduced motion is honoured.
- All existing tests pass (Android 231+, backend PWA and README guards).
- No mock data in Home (source check).
- Light and dark complete on every page.

**Per-page tracking** (updated after implementation pass 1, 2026-11-06).

Key:
- ✔ = done and checked.
- ◐ = restyled through the shared design system only (tokens, cards, buttons, chips, icons, type), with no page-specific layout change yet.
- — = not started.
- 📱 = needs the phone: no device was connected, so Android light/dark/responsive checks are pending.

"Tests" means automated (JVM or pytest/Node) checks that exist for that page.

| Page | Audit | Design approved | Implemented | Functionality verified | Light | Dark | Responsive | Navigation | Tests |
|---|---|---|---|---|---|---|---|---|---|
| A: Launch (system splash + reveal) | ✔ | ✔ | ✔ | ✔ (JVM) | 📱 | 📱 | 📱 | n/a | ✔ |
| A: Home (bento) | ✔ | ✔ | ✔ | ✔ (source + build) | 📱 | 📱 | 📱 | ✔ | ✔ |
| A: Chat (composer, model chip, header) | ✔ | ✔ | ✔ | ✔ (JVM) | 📱 | 📱 | 📱 | ✔ | ✔ |
| A: Model sheet (new) | ✔ | ✔ | ✔ | ✔ (JVM) | 📱 | 📱 | 📱 | ✔ | ✔ |
| A: More (grouped navigation, replaces drawer + Settings tab) | ✔ | ✔ | ✔ | ✔ (JVM: every route reachable) | 📱 | 📱 | 📱 | ✔ | ✔ |
| A: Models (Use for chat, no re-download) | ✔ | ✔ | ✔ (fix + section) | ✔ (JVM) | 📱 | 📱 | 📱 | ✔ | ✔ |
| A: Career (dashboard + 6 sections) | ✔ | ✔ | ✔ dashboard + tabs (4.5.1); sub-section layouts ◐ (groups) | ✔ (JVM source) | 📱 | 📱 | 📱 | ✔ (via More/Home) | ✔ |
| A: Study (Overview, Tutor, Plans, Cards, Quiz, News) | ✔ | ✔ | ✔ Overview, Create, Quiz, tabs (4.5.1); Tutor/Plans/Cards ◐ (groups) | ✔ (JVM source) | 📱 | 📱 | 📱 | ✔ | ✔ |
| A: Documents + viewers | ✔ | ✔ | ✔ (4.5.1: header, search, grid/list, action sheet) | ✔ (JVM: every action kept) | 📱 | 📱 | 📱 | ✔ | ✔ |
| A: Knowledge + Workspaces (7) | ✔ | ✔ | ✔ tabs, My Knowledge header/search/item view (4.5.1); workspace sub-views ◐ | ✔ (JVM source) | 📱 | 📱 | 📱 | ✔ (via More/Home) | ✔ |
| A: History, Memory, Providers, Answer style, Saved prompts, Search | ✔ | ✔ | ✔ settings groups + segmented controls (Answer style); History/Search lists ◐ | unchanged logic | 📱 | 📱 | 📱 | ✔ | ✔ |
| A: Drive, Backup, Storage, Semantic, Connection, Account, Sync, Privacy, About | ✔ | ✔ | ✔ settings groups (4.5.1) | unchanged logic | 📱 | 📱 | 📱 | ✔ | ✔ |
| A: Welcome / Login / Register / Forgot / Link / Profile | ✔ | ✔ | ✔ Welcome promises (4.5.1); sign-in forms ◐ | unchanged logic | 📱 | 📱 | 📱 | unchanged | existing |
| A: Setup wizard (8 steps) | ✔ | ✔ | ✔ stepper, centred column, action bar, groups (4.5.1) | unchanged logic | 📱 | 📱 | 📱 | unchanged | ✔ |
| A: Resume assistant (classic) | ✔ | ✔ | ◐ | unchanged code | 📱 | 📱 | 📱 | ✔ (More → AI) | existing |
| A: Dialogs and overlays (~60) | ✔ | ✔ | ◐ (theme surfaces) | unchanged code | 📱 | 📱 | 📱 | n/a | existing |
| W: Shell (sidebar, rail, tab bar, theme) | ✔ | ✔ | ✔ | ✔ (Node + pytest) | ✔ (headless) | ✔ (headless) | ✔ (1280 px, 390 px) | ✔ | ✔ |
| W: Launch (once per session) | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | n/a | — |
| W: Home (bento) | ✔ | ✔ | ✔ | ✔ (browser E2E, real data) | ✔ | ✔ | ✔ (1280, 390) | ✔ | ✔ |
| W: Chat + voice + model picker | ✔ | ✔ | ✔ | ✔ (browser E2E + live Ollama) | ✔ | ✔ | ✔ (1280, 390) | ✔ | ✔ |
| W: News / Interview / MCQ | ✔ | ✔ | ◐ | unchanged code | ✔ (News, headless) | ✔ (News) | ✔ (News) | ✔ | existing |
| W: Knowledge folder, Memory & history, System & data + Companion access | ✔ | ✔ | ✔ full pages on wide screens, sheets on phones (inner content ◐) | ✔ (browser E2E) | ✔ | — | ✔ | ✔ (sidebar, one at a time) | ✔ |
| W: Setup wizard | ✔ | ✔ | ◐ (restyled sheet) | unchanged code | ✔ (screenshot) | — | ✔ | unchanged | existing |
| W: Settings (+ Default chat model) | ✔ | ✔ | ✔ (new field) / ◐ | ✔ (pytest + browser E2E) | ✔ | — | — | ✔ | ✔ |

**Pass 2 (2026-11-09):** Android 4.5.1 page layouts; web Home and full pages; live desktop model validation (see CHAT_MODEL_SELECTION.md §4).

**Still open after pass 2:**
- every 📱 check (no phone connected);
- the ◐ sub-views (Tutor/Plans/Cards, Career sub-sections, workspace sub-views, sign-in forms, History/Search lists, per-dialog layouts, web setup wizard, inner content of the web pages);
- web dark-theme screenshots of the full pages.

**Not completed in pass 1 (kept for history):**
- page-specific layouts for the ◐ pages;
- the web Home bento;
- web sheets → full pages on desktop;
- every 📱 check (install 4.5.0 on the phone).

These are the next passes; no functionality was removed to get here.

## Decisions (approved 2026-11-06)
1. **Palette:** adopt B.1 (violet #635BFF + cyan accent, navy/light neutrals; **pink retired**)? It changes the look of both apps, and the web and Android stay consistent.
2. **Android navigation:** N1 (Home · Chat · Study · Documents · More; the drawer and the Settings tab merge into More), or N2 (keep the current tabs, bento inside Chat)?
3. **Desktop/web scope:** earlier Android prompts said "don't modify the desktop app". This redesign includes it, and it touches uncommitted dev.7 work. Confirm desktop is in scope.
4. **Order:** Android first (it's on your phone), then web? Or both per phase?
