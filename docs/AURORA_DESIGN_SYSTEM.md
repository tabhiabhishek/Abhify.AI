# Aurora design system (Android 4.6, desktop/web 2.0.0-dev.8)

One visual language for both apps. Android implements it in `android/.../ui/Theme.kt`, `Aurora.kt` and `PageKit.kt`; the web in `frontend/styles.css`. The values below are the source of truth for both.

## Direction
**Aurora** is a deep-indigo night (dark) or a warm lavender-white (light), lit by soft aurora light.
- Electric violet is the brand.
- Every feature has its own hue, so you always know where you are.
- The assistant has a face: the **AI orb**, which shows its real state.

## Colour tokens
| Token | Dark | Light | Use |
|---|---|---|---|
| Background | `#0B0D1E` | `#F6F5FF` | App base, with three aurora lights (violet top-left, azure top-right, mint or coral low; 8–30 % alpha, static) |
| Surface (cards) | `#151833` at 90 % | white at 92–95 % | Cards over the aurora |
| Raised | `#1C2042` | `#FFFFFF` | Sheets, dialogs, composer |
| Hairline | `#2A2F5A` | `#E6E3F7` | Borders |
| Text / muted | `#F4F5FF` / `#A9AED6` | `#13142B` / `#5A5C7A` | Body / secondary |
| Brand | `#6D4AFF` | `#6D4AFF` (buttons), `#5A3BF0` (Material primary) | Primary actions (white text 5.1:1) |
| Brand text | `#A594FF` | `#5536E8` | Links, accents |
| Success / warning / error | `#34D399` / `#FBBF24` / `#FF8A8A` | `#047857` / `#A14A06` / `#B91C1C` | Always with an icon or a word |

**Gradients:**
- **Aurora** (`#6D4AFF → #3B82F6 → #2DD4BF`): icons only (the launch ring, send buttons), because its mint end is too light for white text.
- **Deep** (`#5B3BF0 → #4338CA → #1D4ED8`): white-text surfaces (the Home hero, your chat bubbles); at least 6:1 at every stop.

### Feature identities
| Feature | Text (dark / light) | Tile gradient |
|---|---|---|
| Chat | `#A594FF` / `#5536E8` | `#5B3BF0 → #9333EA` |
| Study | `#34D3B4` / `#0F766E` | `#0F766E → #0D9488` |
| Documents | `#7DB2FF` / `#1D5FD1` | `#1D4ED8 → #0284C7` |
| Career | `#FF9A8C` / `#B23A0B` | `#E11D48 → #EA580C` |
| Knowledge | `#FBBF24` / `#92400E` | `#B45309 → #D97706` |
| Memory | `#E99BFF` / `#A21CAF` | `#A21CAF → #C026D3` |
| System | `#9AA6FF` / `#4338CA` | `#4338CA → #0284C7` |

These are enforced by tests:
- text ≥ 4.5:1 on every background;
- white labels on the first stop ≥ 4.5:1;
- white icons on the second stop ≥ 3:1.

The Android tests are `ContrastTest` and `AuroraDesignTest`.

## Motion
| Token | ms | Easing | Use |
|---|---|---|---|
| fast | 120 | standard `(0.2, 0, 0, 1)` | Press (scale 0.97), toggles |
| base | 220 | standard | Navigation pill, section tabs, chips |
| emphasis | 340 | emphasized `(0.05, 0.7, 0.1, 1)` | Page glide, sheets, progress |
| breath | 2,600 | ease-in-out | The orb's idle breathing |

- **Reduced motion** (Android "Remove animations"; web `prefers-reduced-motion`) makes every transition instant and stops the orb.
- **Infinite animations** exist only while their element is on screen. In chat history, every orb is still except the one on the message being written.

## The AI orb (real states only)
| State | Comes from | Look |
|---|---|---|
| Ready | Nothing running | Slow breathing |
| Listening | Voice input is on | Mint ring expanding |
| Thinking | Waiting for the first token / the answer | Fast swirl |
| Writing | Tokens streaming (Android) | Slow swirl |
| Needs attention / AI unavailable | Phone model failed to start (Android); backend not reachable (web) | Coral, still |

The state is also in the orb's accessible name ("Abhify.AI: thinking").

## Components (Android → web)
| Component | Android | Web |
|---|---|---|
| Aurora background | `BrandBackground` (`drawWithCache`) | `body` background (fixed radial gradients) |
| AI orb | `AiOrb(state)`, `orbStateOf(vm)` | `.orb[data-state]`, `updateOrb()` |
| Feature tile / accent card / gradient icon | `FeatureTile`, `AccentCard`, `AccentIcon` | `.b-card[data-acc]`, `.perf-tile[data-acc]` |
| Navigation | `AuroraNavBar` (feature-coloured animated pill) | `.tab[data-acc]`, `.side-btn[data-acc]` |
| Page change | `PageTransition` (fade + glide) | `.panel` `pageIn` |
| Section tabs | `SegmentedTabs(accent)` | n/a |
| Progress | `ProgressRing` (animated sweep), animated linear bars | `.meter` |
| Flashcard flip | `CardsView` (rotationY 0→180°) | n/a |
| Grouped rows | `SettingsGroup(accent)`, `LocalRowAccent`, `PageIdentity` | Sidebar groups |
| AI actions | `AiActions` + `ActionSheet` | `AI_ACTIONS` + `.ai-actions` |
