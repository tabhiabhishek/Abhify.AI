# Navigation: AI and System settings consolidated in Settings (2026-11-09)

Settings is now the one place for AI, models, performance and system configuration. Existing pages open from it; nothing is duplicated.

## Android
**Moved from More to Settings** (route `SETTINGS`, renamed from "Answer style & personalization"):
- **AI:**
  - AI mode;
  - AI providers;
  - Personal memory;
  - Semantic search.
- **Models & performance:**
  - AI models on this phone (download, switch, speed test, Performance);
  - the memory profile card.
- **System:**
  - Connection mode;
  - Sync & network;
  - Run setup again.
- **Diagnostics:** copy diagnostics (no keys) and the security summary.
- **Kept in Settings:** Appearance, Answer style, Custom instructions, Voice & audio (now also the reading speed), Learning progress. The duplicate "Abhify server" card was removed; it's Connection mode under System.

**Remaining in More:**
- **Workspace:**
  - AI Task Runner;
  - **Projects** (new);
  - Documents, Career, Knowledge & workspaces;
  - Conversation history, Search everything, Saved prompts;
  - **New private chat** (crash fixed).
- **Documents and text recognition:** OCR setting and self-test, Resume Assistant.
- **Data:** Google Drive, Backup & restore, Storage, Privacy & data deletion.
- **Application:** Settings, Abhify account, About.

**Other Android changes:**
- **Bottom bar:** unchanged (Home · Chat · Study · News · More).
- **Brand launcher:**
  - System opens Settings;
  - Projects added to Workspace;
  - More added to Application, so More stays reachable.
- **Back navigation:** unchanged (the existing route stack).

## Desktop/web
- **The Settings dialog now has sections:**
  - **AI & models:** AI Models & setup, Performance;
  - **System & your data:** System & data, Memory & history, Knowledge folder;
  - **General:** name, where the AI runs, default chat model, connection;
  - **Voice & audio:** Private voice mode;
  - **Application:** reset progress.
- **Sidebar:**
  - Performance and System & data are no longer separate entries; they're in Settings and the brand launcher;
  - **Projects** was added next to Task Runner;
  - Settings stays.
- **Unchanged:** the brand launcher keeps every destination and adds Projects. The command palette keeps every command and adds Projects.
- **One page at a time:** System, Memory, Knowledge and Performance now close any other open page, whichever way they are opened. Before, opening them from Settings could leave two pages stacked.

## Tests
| Test | Result |
|---|---|
| Android `NavigationSettingsTest` (3) | every AI and System entry is in Settings and not in More; More keeps its own entries and reaches Settings, Account and About; the launcher points System to Settings and keeps More |
| Android `V45Test` / `V451Test` | **changed on purpose:** "every destination reachable" now searches More + Settings + the project list; the Settings title assertion is now "Settings" |
| Browser `stability_e2e.mjs` | Settings sections, Settings → Performance, Settings → AI Models, the sidebar consolidated, the launcher lists Projects |
| Browser `web_e2e.mjs` (43) / `launcher_e2e.mjs` (17) | all pass. Three checks were updated to the new paths (System and Performance through Settings; Projects in the launcher list). |
| Physical Android device | not run (no device connected) |
