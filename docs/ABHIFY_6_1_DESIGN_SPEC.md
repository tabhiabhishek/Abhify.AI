# Abhify.AI shared design specification (6.1)

There is one design language for Android, desktop and web. The values below are the source of truth:
- **Android:** `android/.../ui/Theme.kt` (`Brand`, `Accent`, `Dimens`, `Motion`).
- **Web:** `frontend/styles.css` `:root`.

When a value changes, change both files. `ContrastTest` (Android) checks the contrast ratios.

## Colour (dark; light in brackets)
| Role | Value | Use |
|---|---|---|
| Background | `#07080D` (`#F5F6FA`) | App background |
| Surface | `#131620` (`#FFFFFF`) | Cards |
| Raised | `#1A1E2B` (`#FFFFFF`) | Sheets, dialogs, composer |
| Line | `#30364A` (`#D5D9E5`) | Hairlines (at least 1.2:1) |
| Text / Muted | `#F3F5FA` / `#A3A9BE` (`#0E1017` / `#4E556B`) | Body / secondary text (at least 4.5:1) |
| Violet | `#6D4AFF` | Primary buttons, active navigation (white text) |
| Violet text | `#A594FF` (`#5130E0`) | Brand-coloured text and icons |
| Cyan | `#22D3EE` | Second brand colour: ready states, neural core, waveform (fill only; dark text on it) |
| OK / Warn / Bad | `#34D399` / `#FBBF24` / `#FF8A8A` | Status only, always with a word, never colour alone |

**Gradients:**
- The brand gradient (violet → cyan) is used sparingly: send button, launch ring, hero.
- Coral and pink are retired as brand accents. In 6.1 the Home hero's coral glow became cyan.
- Each feature has one hue (`Accent`: chat, study, docs, career, knowledge, memory, system) for its icon tiles and progress.

## Typography
- **Text font:** Inter or the system sans.
- **Code font:** Cascadia Code / Consolas (web), monospace (Android).
- **Android:** sizes come from `MaterialTheme.typography` and follow the user's font scale. The smallest is `labelSmall` at 13 sp; there are no literal sp sizes.
- **Web:** sizes come from `--fs-xs … --fs-2xl`.

## Spacing, size and shape
- **Spacing:** a 4-point scale (Android `Dimens`; web `--sp-1 … --sp-8`).
  - Page gutter 16. Gap between cards 12. Card padding 16.
- **Touch targets:**
  - **Android:** at least 48 dp (`Dimens.touch`). In 6.1 the composer buttons went from 46 to 48 dp, and the code Copy button got a 48 dp target.
  - **Web:** at least 44 px.
- **Corner radii:**

  | Element | Radius |
  |---|---|
  | Fields | 12 |
  | Cards | 20 |
  | Composer | 20 |
  | Dialogs | 24 |
  | Hero | 28 (web `--r-xl`) |
  | Chips | pill |

  Some older Android screens still use literal values between these (10/14/16). They are listed in the 6.1 audit as low-priority drift.

## Motion
| Token | Android `Motion` | Web | Use |
|---|---|---|---|
| Fast | `FAST` 120 ms | `--dur-fast` | Press, toggles |
| Base | `BASE` 220 ms | `--dur-base` | Tabs, chips, expand, navigation indicator, **message appear** |
| Emphasis | `EMPHASIS` 340 ms | `--dur-slow` | Dialogs, sheets, page changes |
| Standard easing | `cubic-bezier(0.2, 0, 0, 1)` | `--ease` | Most motion |
| Emphasized easing | `cubic-bezier(0.05, 0.7, 0.1, 1)` | `--ease-out` | Entering surfaces |

**Rules:**
- Animate opacity and transform only, never layout-heavy properties.
- No motion that blocks input.
- The AI orb "breathes" only while it is visible.
- **Reduced motion:** Android's "Remove animations" makes every `Motion.tween` instant (`reducedMotion()`). In 6.1 the splash fade and the new message-appear animation also follow it. The web `prefers-reduced-motion` disables non-essential motion.
- **New chat messages (6.1):** they fade in and rise 12 dp once. Messages that were already there, or that scroll back into view, never replay it.

## Component states
- **Hover:** web only. Raised surface plus line colour.
- **Focus-visible:** 2 px focus ring (`--focus`). On Android, TalkBack focus uses the system ring.
- **Pressed:** scale 0.97 (`pressScale`, Fast).
- **Selected:** violet fill or violet indicator, plus a text or semantic label ("selected").
- **Disabled:** 38% opacity. It is never the only signal.

## Navigation and terms
**Five primary destinations, the same words everywhere:** Home · Assistant · Study · News · More.
- **Android:** a bottom bar.
- **Desktop and web:** an expandable sidebar, plus a bottom bar on narrow web.
- **Under More:** Knowledge, Workspace (Projects, Tasks), Career, AI & System (Models, Providers, Memory, Performance), Settings & Privacy, About.

**Back:**
- Android system Back closes the innermost thing first: dialog → overlay (Voice, Agent) → detail view (project, task, resume, study session, knowledge item) → tab → app.
- A non-bar tab (Documents, Career, Knowledge) goes back to where it was opened from: Home or More.

## Content states (same wording style on every platform)
- **Loading:** a skeleton or "Loading…". Never a fake empty state such as "0 documents" before the data arrives. Home does this in 6.1.
- **Empty:** say what to do next ("No documents yet: add one in More → Documents").
- **Error:** say what failed and what to do ("Couldn't reach the backend. Retry"). Never a raw exception.
- **Destructive actions:** confirm with the item's name ("Delete this task?"), or offer undo. 6.1 added confirmations for to-do and AI-task deletion.
- **Copy:** visible feedback ("Copied ✓").
- **AI output:** labelled with where it came from (phone, or a named provider). A mode never changes the model by itself.
