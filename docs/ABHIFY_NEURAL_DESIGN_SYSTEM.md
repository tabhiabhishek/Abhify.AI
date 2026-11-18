# Abhify Neural design system

The **Neural Futuristic** identity is built on the existing Aurora system ([AURORA_DESIGN_SYSTEM.md](AURORA_DESIGN_SYSTEM.md)). It is not a second set of tokens.

- Midnight surfaces, an electric violet brand, and azure (electric blue) and mint/cyan accents were already the Aurora values.
- 5.3 adds the **abstract holographic neural core** and its six activity states, and documents the tokens under one name.
- The values below are the source of truth: Android `ui/Theme.kt` (Brand, Accent, Dimens, Motion) and web `frontend/styles.css :root`.
- WCAG contrast is checked in `ContrastTest`.

## 1. Tokens

| Group | Token (Android `Brand.*` / web `--*`) | Value | Use |
|---|---|---|---|
| Background | `Bg1` | `#0B0D1E` midnight | dark background |
| Surface | `Surface` / `Bg2` / `Glass` (90 %) | `#151833` / `#1C2042` / `#151833E6` | cards, sheets, glass over light (controlled: 90 % opaque, no heavy blur) |
| Line | `Line` | `#2A2F5A` | borders, dividers |
| Text | `Text` / `Muted` | `#F4F5FF` / `#A9AED6` | body / secondary (≥ 4.5:1) |
| Brand | `Violet` | `#6D4AFF` | primary buttons, active navigation (white text 5.1:1) |
| Accent: electric blue | `Azure` | `#3B82F6` | core, gradients |
| Accent: cyan | `Mint` (`Cyan`) | `#2DD4BF` | ready, listening, searching scan |
| Status | `Green` `#34D399` · `Amber` `#FBBF24` · `Red` `#FF8A8A` · `Coral` `#FF7A6B` | | ok, warning, error, attention |
| Gradients | `Accent` violet→azure→mint (icons only) · `Deep` violet→indigo→azure (white text ≥ 6:1) | | send/launch, own chat bubbles, hero |
| Feature hues | `Accent.CHAT / STUDY / DOCS / CAREER / KNOWLEDGE / MEMORY / SYSTEM` | dark/light text-safe pairs | section tabs, tiles, progress |
| Spacing | `Dimens.gutter 16 · gap 12 · cardPadding 16 · small 8 · tiny 4 · large 24` (dp) | | page and card rhythm |
| Size | `touch 48 · rowHeight 56 · maxWidth 680` (dp) | | touch targets, readable width |
| Radius | cards `20` · fields `12` · composer `20` · dialogs `24` (dp) | | |
| Motion | `FAST 120 · BASE 220 · EMPHASIS 340 · BREATH 2600` ms; Standard / Emphasized easing | | every duration becomes 0 with reduced motion |
| Typography | Inter (bundled), Material 3 scale; app text-size setting 0.9–1.3× on top of the phone's | | |
| Breakpoints (web) | phone < 600 px · tablet 600–1023 · desktop ≥ 1024 (sheets become pages) | | |

## 2. Components (existing, reused)

| Component | Android | Web |
|---|---|---|
| Page header, section groups | `PageHeader`, `SettingsGroup`, `NavRow` (vector icons) | `.sheet h2`, `.set-h` |
| Cards | `Glass`, `BentoCard`, `AccentCard`, `FeatureTile` | `.glass`, `.b-card`, `.task-card` |
| Buttons | `GradientButton`, Material `Button`/`OutlinedButton`/`TextButton` | `.btn-primary`, `.btn-ghost`, `.chip` |
| Inputs | `SearchField`, `OutlinedTextField` | `.input`, `.select` |
| Navigation | `AuroraNavBar` (five items), `SegmentedTabs`, brand launcher | tabs + sidebar, launcher, Ctrl+K palette |
| Dialogs and sheets | `AlertDialog`, `ActionSheet` | `.sheet-backdrop` (page on ≥ 1024 px) |
| Status | `Pill`, `StatusTag`, `StatusLine`, `InfoNote` | `.tag`, `.status` |
| Loading / empty / error | `LinearProgressIndicator`, `EmptyState`, warn-coloured text | skeletons, `.empty`, `.err` |

## 3. The abstract holographic neural core (`AiOrb`, Android; `.orb`, web)

A sphere of violet → azure → mint light. 5.3 adds a **neural layer**: three nodes on an inner ring joined by faint links, rotating with the core. Every state comes from real activity and is never simulated.

| State | Set when (Android `orbStateOf`) | Visual |
|---|---|---|
| **Idle** | nothing running | slow breathing |
| **Listening** | voice input active (`vm.listening`) | expanding mint ring |
| **Searching** | retrieval over your documents/knowledge is running (`vm.searching`, set around `RagIndex.retrieve`) | cyan scan arc sweeping the core |
| **Thinking** | an answer was requested, no text yet | fast swirl |
| **Responding** | the answer is streaming | slow swirl |
| **Offline** | no network, and nothing on the phone can answer (Online mode or no phone model) | grey, still |
| Error | the phone model failed in phone-only mode | coral, still |

**Performance:**
- The core is a few Canvas draws (gradients, three dots, three lines, one arc), animated only while visible.
- **Static fallback:** reduced motion, Offline and Error are still, and the state is always in the spoken description.
- Nothing runs in the background.

**Web:** `.orb[data-state]` already shows idle / listening / thinking / generating / error. Adding Searching and Offline there is part of the desktop parity step.

## 4. Rules

- Controlled glass: 90 % opaque surfaces and no heavy blur. No particle effects or continuous animations off-screen.
- Neon only as accents: violet for actions, cyan for "ready/searching", never body text on gradients other than `Deep`.
- Touch targets ≥ 48 dp. Labels are always visible in the bottom bar. Emoji in rows map to vector icons (`AppIcons`, tested).
- Same tokens on both platforms, but layouts differ: a phone gets the five-item bottom bar; the desktop gets the five-item sidebar and wider multi-panel pages.
