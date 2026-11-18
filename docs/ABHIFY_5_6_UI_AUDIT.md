# Abhify.AI 5.6 UI audit: Neural Futuristic design

## Shared design language
Android `ui/Theme.kt` (`Brand`, `Extra`, `Dimens`, `Motion`) and the web `frontend/styles.css :root` use the **same token values**.

| Token | Value | Use |
|---|---|---|
| bg-0 (Android `Brand.Bg1`) | `#07080D` near-black | app background |
| surface (`Brand.Surface`) | `#131620` charcoal | cards |
| raised (`Brand.Bg2`) | `#1A1E2B` | dialogs, sheets, composer |
| line (`Brand.Line`) | `#30364A` | hairlines (≥ 1.39:1 on every surface; `#262B3B` failed the 1.2:1 check and was rejected) |
| text / muted | `#F3F5FA` / `#A3A9BE` | body / secondary text (≥ 4.5:1, enforced by `ContrastTest`) |
| violet | `#6D4AFF` (white text 5.1:1); `#A594FF` for violet text | primary actions, active navigation |
| electric cyan (`Brand.Cyan`) | `#22D3EE` | second brand colour: ready states, the neural core, the voice waveform |
| brand gradient | violet → `#4F7BFF` → cyan | used sparingly: the launch ring, send buttons, the neural core |
| ambient light | violet 22 % / cyan 9 % / violet 7 % (down from 30/20/13 %) | restrained background light, drawn once, never animated |

Feature accents (Study teal, Docs blue, Career coral, Knowledge amber, Memory magenta, System indigo) are kept, so each area stays recognisable.

## Android: screens inspected (code audit; no emulator system image is installed on this PC, so no Android screenshots)
| Screen | Built from | Finding | 5.6 action |
|---|---|---|---|
| Splash / onboarding | `BrandSplash`, `BrandLockup` | Already uses the theme | Picks up the new tokens |
| Home | `HomeTab`: HeroCard (greeting and core), TodayCard, Continue working, dismissable Suggestions, Your spaces, Active projects, Learning and Knowledge tiles | Already the dashboard the brief describes; all data from records | New palette |
| Assistant / chat | `HomeScreen`, composer, `DocumentBar`, `ChatContextBar` | Document reading used a one-off spinner row | Moved to the shared `BusyBanner` |
| Talk to Me | `VoiceOverlay`, `AiOrb`, `Waveform` | Core states are real; a voice error was not shown as ERROR; "Interrupt" was missing while still answering | Error state mapped; Interrupt while answering; "You: …" transcript; cyan waveform; ends in the background |
| Agent | `AgentOverlay` | Consistent | – |
| Study, Discover, Skill map | `BrainScreens.SkillsCard`, `DiscoverScreens` | – | Revision plan and learning path added |
| News, article | `NewsScreens` | **Image bug** (see the root-cause doc); cards were not keyed | Fixed: keyed cards and URL-bound image state; same placeholder for missing or broken images |
| More, Knowledge Center, Documents, Projects, Tasks | Bento/Glass/PageKit | Import and task progress used one-off spinner rows | Moved to `BusyBanner` |
| AI Models, Performance, System | `ManageScreens`, `V45Screens` | `BusyCard` duplicated the loading style | Now `BusyBanner` |
| Settings, Privacy, About, AI providers | `SettingsGroup`, `NavRow`, `SwitchRow` | Consistent | "Measured this session" provider stats |
| Google Drive | `V34Screens` | One-off progress row | `BusyBanner` (percentage in the text) |

**Shared components:** `Glass`, `BentoCard`, `AccentCard`, `PageHeader`, `SettingsGroup`, `EmptyState`, `ErrorBanner`, `BusyBanner`, `InfoNote`, `ActionSheet`, `DialogText`, `AuroraNavBar`, `AiOrb`.

**Five destinations:** HOME · ASSISTANT · STUDY · NEWS · MORE. The bottom bar (`AuroraNavBar`) is unchanged in structure.

**Checks:** `UiPolishTest`, `ContrastTest`, `NeuralTokensTest` and the 5.x UI tests pass.

**Android visual validation: PENDING on a real phone.** Checklist test 73 covers the look; test 61 covers News images.

## Desktop/Web (inspected in a real browser: headless Chrome against an isolated backend with seeded test data)
Screenshots were taken BEFORE and AFTER at **1440, 1024 and 390 px**, 15 screens each, 45 PNGs per set, in the session scratchpad: `u56_before\` and `u56_after\`, named `<screen>_<width>.png`. The screens: 01 Home · 02 Home briefing · 03 Assistant · 04 conversation · 05 Study skills · 06 Discover · 07 News · 08 More · 09 Projects · 10 Project status overview · 11 Agent panel · 12 Search palette · 13 Settings · 14/15 light theme. An overflow check found nothing in any AFTER shot. **Kept in the project:** 8 before/after pairs (Home 1440/390, Assistant, Study skills, News 1440/390, Project overview, Settings 1024) in [screenshots/ui56/](screenshots/ui56/).

| Finding (before) | Change (after) |
|---|---|
| Rainbow "Aurora": seven feature hues, a glow or gradient on every surface, three background lights | One system in `styles.css`: the shared tokens above plus spacing, radius, elevation, type and motion scales. Accents are violet or cyan only. Blur only on navigation and sheets. Animation off under reduced motion. |
| A 10-item sidebar (5 destinations plus a "Workspace" group); phone tabs at the top | Exactly **5 destinations**. Desktop: expandable sidebar with Settings and Collapse at its foot. Phone: a bottom bar. Workspace pages remain under More, the launcher and the palette. |
| "Hey **Abhi**!" was a hard-coded default name, also sent to prompts | The configured name, or a neutral greeting |
| Home dominated by a gradient hero; Continue showed one chat; Today was hidden | Dashboard: neural core with Ask and quick actions, Continue working, Today, Active projects, AI on this computer, AI news, Quiz, Briefing, Recent activity. Real endpoints only. |
| Assistant had no history or agent entry; emoji mic and send buttons; toolbar overflow at 390 | History and Agent chips, a history popover, SVG composer |
| News: the active category wasn't highlighted on load (bug); random gradient placeholders; no category | Editorial cards (source · date · category), the active chip marked, one placeholder for missing or broken images bound to its story |
| Search palette at 390: titles squeezed to one word per line | Fixed |
| Settings at 1024: the sheet was taller than the screen and its top unreachable (bug) | Scrollable |
| Projects page highlighted "Home"; double card borders | Tracked correctly; one card style |
| No shared empty or error states | `stateHtml()` used for Discover, plan and briefing |

**Service worker:** `SHELL_REV` (`ui56.1`) is added to the cache name, so installed browsers drop the old shell. The app version is unchanged.

**Not done on the web:**
- "Learn this topic" and "Compare sources" exist only on Android.
- Some sheet buttons and Recent activity rows still use emoji glyphs.
- The light theme was shot only on Home and News.
- The 600–1023 px rail layout was not shot.
