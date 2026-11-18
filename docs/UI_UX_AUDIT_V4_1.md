# Abhify.AI Android: UI/UX audit (source at 4.2.0; the request referred to 4.1.0)

**Date:** 2026-11-06.

**Method:** a read of the Compose source for every route and tab, the theme, shared components, the Markdown parser and renderer, startup, and navigation. No new screens or features are proposed.

## Stack
- **UI:** Jetpack Compose with Material 3, a single `MainActivity`, and a custom back stack (`AppViewModel.stack` of `Route`, plus `Tab` for the bottom bar).
- **Theme:** `ui/Theme.kt`. It holds the brand tokens shared with the web app (`Brand`: slate background, violet → pink accent), `Extra` (glass panels, hairlines, status colours), dark and light schemes, Inter typography, and shapes. Themes: dark, light and system (an existing setting).
- **Startup:** the AndroidX SplashScreen API (`Theme.Abhify.Splash`: the logo mark on navy), then straight into `Router`. No branded launch screen exists.
- **Brand assets:**
  - `abhify_logo.png`: the "A" mark in a circle (blue/green).
  - `abhify_badge.png`: the mark plus the word "Abhify" and *older taglines* ("Learn • Build • Share • Grow", "AI. Code. Community."), which conflict with the requested tagline.
  - The launcher foreground.
- **Shared components (`ui/Components.kt`):** Glass card, GradientButton, Field, SwitchRow, NavRow, StateChip, StatusLine, AiModeChip, ScreenTitle, Pill, Logo, Badge.
- **Markdown:** `chat/Markdown.kt` (parser) and `ui/MarkdownText.kt` (renderer). It is used for AI answers, study sessions, résumé versions, interview feedback, knowledge items and Q&A.

## Page inventory (every destination)
| # | Destination | Kind | Page wrapper |
|---|---|---|---|
| 1 | Welcome, Profile, Login, Register, Forgot, Link | Onboarding routes | `AuthColumn` (gutter 20, width 480, gap 12) |
| 2 | Setup wizard (7–8 steps) | Route | own layout |
| 3 | Home shell: header, drawer, bottom bar | Route HOME | — |
| 4 | Chat tab (empty state = **main dashboard**), message list, context bar, composer | Tab | LazyColumn (gutter 12) |
| 5 | Career tab: Dashboard, Resumes, Jobs, Match, Interview, Question bank | Tab with sections | `CPage` (14 / 680 / 10) and `Page` in LifeScreens |
| 6 | Study tab: Study (dashboard), Tutor, Plans, Cards, Quiz, News | Tab with sections | `Page` (LifeScreens), `TabPage` (14 / 680 / 10), LazyColumn |
| 7 | Documents tab and document viewer (dialog) | Tab | `TabPage` |
| 8 | Knowledge tab: My Knowledge (vault); Workspaces: Tasks, Notes, Meeting notes, Knowledge, Write, Linked | Tab with sections | `Page`, `TabPage`, own |
| 9 | Settings tab | Tab | `TabPage` |
| 10 | History, Memories → Personal memory, Models, Providers, Settings (answer style), Account, Sync, Privacy, About | Routes | `Page` (ManageScreens: 16 / 640 / 10) |
| 11 | Resume (legacy resume assistant) | Route | own |
| 12 | Google Drive, Storage, Backup & restore, Semantic search, Search everything, Connection mode | Routes | `Screen` (16 / 680 / 10) |
| 13 | Overlays: task status, OCR question, consent dialogs, ~60 AlertDialogs | Dialogs | Material defaults |

## Findings
### Branding and launch
- **No branded launch screen.** The system splash shows only the icon, then the app appears abruptly.
- **Conflicting taglines.** Welcome and About show `abhify_badge.png`, whose baked-in taglines differ from "Learn AI. Think Deeper. Build the Future". Nowhere is "Abhify.AI" written as text.
- **Inconsistent names.** The header says "Abhify", the drawer "Abhify", About "About Abhify". The product name is **Abhify.AI**.

### Consistency and layout
- **Six page wrappers with different values:**

| Wrapper | Gutter | Max width | Gap |
|---|---|---|---|
| Chat | 12 | — | — |
| CPage, Page, TabPage | 14 | 680 | 10 |
| ManageScreens Page | 16 | 640 | 10 |
| Screen | 16 | 680 | 10 |
| AuthColumn | 20 | 480 | 12 |

  These values are repeated literally. **There are no spacing tokens.**
- **Cards:** the glass card uses 14 dp padding with a 6 dp gap. Several screens override it (padding 12, nested cards).
- **Headers:** the back button is a `TextButton` with the "←" glyph. It has no accessibility description, and long titles wrap to two lines and push actions. The home header's icon buttons (🔍, ⚙, ☰) vary in size; ☰ has no description.
- **NavRow:** a bare emoji with no tile, a one-line subtitle that truncates useful status text, and no minimum height (touch target under 48 dp with large fonts).
- **Chips:** three private copies of the same function (`Chips` twice, `Sections`) with different text weights.
- **Helper text:** five private copies (`Muted`, `Note`, `Hint`) with 2 different sizes (bodyMedium vs bodySmall). That's acceptable but inconsistent.
- **Empty states:** plain grey sentences ("No decks yet.", "No plans yet.", "Nothing found."), sometimes with no hint of what to do next.
- **Bottom bar:** emoji icons at 18 sp with labels at labelMedium. The selected indicator is fine.

### Text formatting (root causes, not individual labels)
The Markdown parser handles headings 1–4, paragraphs, `-`/`*` lists, numbered lists, fenced code, `**bold**`, `*italic*`, `` `code` `` and tables. Gaps:

| Problem | Cause |
|---|---|
| Single line breaks vanish ("Q: …\nA: …" becomes one line; addresses, poems, step lines) | The paragraph builder joins lines with spaces |
| `> quote` shows the raw `>` | No blockquote block |
| `---`, `***` show as text | No horizontal rule |
| `#####`, `######` show the hashes | Headings limited to 1–4 |
| `[label](https://…)` shows raw brackets; bare URLs aren't styled | No link spans |
| `__bold__`, `***bold italic***`, `~~strike~~` show the markers | Not parsed |
| `\*` and `\_` show the backslash | No escapes |
| `<br>`, `<b>…</b>` from some models appear as tags | No handling |
| Nested lists (indented `-`) are flattened | Indentation discarded |
| `+ item` and `- [ ] task` aren't lists | Not recognized |
| `~~~` fences aren't code | Only ``` recognized |
| Uniform 6 dp between every block | Headings, lists and paragraphs need different spacing |
| Tables use a fixed 140 dp per column | Short columns waste space; long ones wrap a lot |

Other text issues:
- **Inline styles** are rebuilt as AnnotatedStrings on every recomposition (during streaming, every token).
- **Document viewer:** up to 60,000 characters in **one** `Text` inside a dialog. That is a heavy layout, its "— Page N —" markers are unstyled, and there's no paragraph spacing.
- **Truncation:** `NavRow` subtitles are truncated to one line; some card titles use `maxLines = 2` where the full text matters. Answer bubbles already collapse very long answers with "Show more", which is correct.

### Theme
- **Two modes, both with tokens.** Contrast is already tested (`ThemeTokens`, WCAG checks).
- **Hard-coded colours:** pages use `Brand.Violet2` directly for accent text (about 20 places). It's readable in both modes (checked) but not a token. Code-highlight colours are hard-coded too and are low-contrast on the light code background (green #86EFAC on #F1F5F9).

### Navigation
- **The structure is sound:** 5 tabs plus Settings from the header, the drawer for secondary routes, and the hardware back with `BackHandler`. Nothing should be restructured.
- **Issues are presentational:** header hit areas, missing descriptions, and the title not ellipsized.

### Performance (UI)
- Markdown inline styling is rebuilt each recomposition; the parse itself is remembered.
- The document viewer renders one giant Text.
- Chat uses a LazyColumn with stable message content (good). The other long lists are capped (history 50 per page, search 8 per type).
- **No blocking work at startup:** AppGraph is created lazily; the index backfill is delayed 8 s on IO.

## Proposed design system (applied in 4.3)
- **Tokens (`Dimens`):**
  - gutter 16; gap 12; card padding 16; card gap 8; small gap 8;
  - max content width 680;
  - minimum touch target 48;
  - radii: card 14, field 12, dialog 24.
- **Typography (Inter):** display (splash brand), headline, title L/M/S, body L/M/S, labels, code (monospace 14/20). The existing sizes are kept; display and code styles are added.
- **Components:**
  - **`PageBody`:** the one scroll wrapper used by all six wrappers.
  - **`ScreenTitle`:** 48 dp back button with a description, one-line title.
  - **`IconAction`:** an emoji or icon button with a description and a 48 dp target.
  - **`NavRow`:** an icon tile, 56 dp minimum height, 2-line subtitle.
  - **`Glass`:** 16 dp padding.
  - **`EmptyState`:** icon, title, hint.
  - **`BrandLockup`:** logo, "Abhify.AI", tagline.
  - **`SectionChips`:** one shared chip row.
- **Colours:** keep the existing violet/pink brand from the web app; no new accent colours. Code-highlight colours get light-mode variants. Gradients are limited to the primary button and the user's chat bubble (as now).

## Plan
1. Tokens and shared components; route the six page wrappers through `PageBody`.
2. Branded launch screen (`BrandSplash`) after the system splash: no fake loading, tap to skip, once per cold start.
3. Brand lockup on Welcome, About and the unsupported-device screen, replacing the badge with conflicting taglines.
4. Main dashboard (the chat empty state): hierarchy, a two-column suggestion grid, grouped status. The same actions as before.
5. Markdown: fix every gap listed above, cache the styled text, use better spacing; table column widths by content.
6. Document viewer: paragraph-split lazy rendering with styled page markers.
7. Navigation chrome: header buttons, bottom-bar sizing and descriptions.
8. Empty states on list pages, using the same wording as before plus a hint.
9. Tests: Markdown parser cases, splash text, tokens and contrast. Then lint, build and report.
