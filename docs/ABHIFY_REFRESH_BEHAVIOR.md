# Refresh and pull-to-refresh

## News refresh: what was wrong
**Web:**
- **Duplicate requests:** repeated taps ran several requests.
- **A stale-response race:** a slow answer for a category you had left could overwrite the new one.
- **No visible feedback:** errors only showed as a brief toast.

**Both platforms:** refresh didn't say what happened.

**Android:** the fetch itself was correct (it always fetches).

The exact symptom you saw wasn't reproduced; these are the defects found in the code.

## News refresh now (both)
- **One at a time:** a second tap or pull while one runs is ignored.
- **Late answers:** for another category, they are dropped.
- **Clear status:** "Updated just now · 60 stories, 5 new". On failure: "Couldn't reach the news sources: the previous stories stay."
- **Failure keeps the list:** a failed refresh keeps the stories on screen.
- **Nothing else changes:** no AI briefing is made, and Listen isn't touched.
- **Android:** feeds are fetched with no cache (`useCaches = false`, `Cache-Control: no-cache`).

## Pull-to-refresh
| | Android | Web/desktop |
|---|---|---|
| Sections | News, Question Bank (Bank), MCQs | News, Question Bank, MCQs (touch screens) |
| How | Material3 `PullToRefreshBox` (no new library) | a small gesture handler (`bindPullToRefresh`) and an indicator |
| Page reload | never (native app) | the browser's own pull-to-reload is switched off (`overscroll-behavior-y: contain`, and the gesture is claimed only while pulling down at the very top) |
| Not started from | – (Compose nested scrolling) | text fields, chip rows, a scrolled inner list, open pages; never while text is selected |
| Desktop | – | ↻ buttons (keyboard-accessible) |

**Bug found by the live test:** the browser cancelled the touch sequence (`touchcancel`) as soon as it took over the gesture, so the first version never fired. It was fixed by a non-passive `touchmove` that calls `preventDefault` only while pulling down at the top.

## Tests
- **Browser, live:**
  - a pull at the top of News made exactly 1 feed request and the page did not reload;
  - a short pull did nothing;
  - News refresh: 3 triggers made 1 request in 1.06 s, with no briefing request.
- **Unit and contract:**
  - backend `test_web_news_refresh_and_pull_to_refresh_contracts`;
  - Android `RefreshBehaviourTest`.
- **Not run:** Android gestures on a phone.
