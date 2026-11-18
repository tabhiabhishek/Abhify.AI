# News: reading inside Abhify.AI

## Root causes
1. **Immediate redirect, web:** each card's main action, "Read more ↗", was a plain link to the publisher (`target=_blank`). Tapping the card body did nothing, so the obvious tap left the app.
2. **Immediate redirect, Android:** every card had its own "Read more" button that opened the site straight away. Tapping the card opened an action sheet whose first entry was also "Read more at publisher".
3. **"Another article also redirects":** the same thing, since every card's visible action led out. There was no shared selection state that leaked between cards. The new reader still has per-story state (below).
4. **Content:** feeds give at most a 320-character description, and Google News items none. A meaningful summary needs the story's own text.

## Behaviour now (both platforms)
**Opening a story:**
- A tap on a story (or "Read in app") opens the **reader**: title, publisher, date (or "date not given"), and a summary of **that story only**.
- The site never opens by itself.
- Android keeps the old actions behind "⋯".

**Story text:**
- **Fetched in the background:** the publisher's page is read by the app, never opened as a navigation. Its article paragraphs are extracted, without scripts, navigation, footers or cookie banners.
- **Address safety:** only public https addresses are fetched. Every redirect is checked, so a feed link can't reach your network.
- **Google News:** its links need a browser, so it isn't fetched.

**Summary:**
- **Length:** 2–3 short paragraphs when the text supports it: what happened, the important details, why it matters. One short paragraph when it doesn't; never padded.
- **Source rules:** only the story's text, fenced as untrusted data, with no added facts, names, numbers, dates, quotes or outcomes.
- **Limited text:** with only the headline and description, it says so ("Based on limited source content") and never claims the full article was read.

**Model:**
- **Desktop:** the chat's chosen model.
- **Android:** your AI mode through `vm.aiTask`.
- **Model choice:** never switched automatically.

**Caching:** per article id and a hash of the text. A changed text gets a new summary; an empty fetch is retried next time.

**AI failure:** the description is shown with the error, a **Retry**, and the original link. News never crashes or redirects.

## Read more (staged)
1. **First tap:** shows the story's own paragraphs in the app. If there are none, it says why and that the next tap opens the original.
2. **Second tap:** opens **exactly this story's** https link (web: a new tab; Android: the browser). An invalid link shows a message and opens nothing.

An "Original article" link is also always shown.

## Per-story state
- **Identity:** each story is tracked by its stable id (a hash of the normalized URL).
- **Switching stories:** opening another story resets the Read more stage and stops reading aloud.
- **Late summaries:** a summary that arrives late for a story you left is dropped (sequence numbers). Android cancels only this reader's own pending summary.
- **Back:**
  - web: browser Back, Esc or "← News";
  - Android: system Back closes the reader.
  
  The list, category and scroll position stay. Nothing is refetched and no briefing is made.
- **Listen:** reads the title and summary (existing News Listen).
- **Refresh:** doesn't open or reset a story. If a refresh removes the open story, the reader says so and closes.

## Measured (desktop, live)
| | Result |
|---|---|
| Open a BBC story → summary (fresh) | **5.3 s**, 13 paragraphs extracted; summary from the article text |
| Open the same story again (cached) | **0.28 s** |
| First Read more | expanded 25 paragraphs in the app, 0 sites opened |
| Second Read more | opened exactly that story's URL, once |
| A then B, then C then D quickly | the reader showed B, then D; late answers ignored |

## Tests
- **Backend `test_news_reading.py` (14 tests):**
  - extraction;
  - URL safety, 8 cases, including private IPs and a private redirect;
  - own-text summary and cache;
  - Google News limited and never fetched;
  - changed text gets a new summary;
  - AI failure keeps the reader;
  - 404;
  - web contracts (no link out from a card, stale guard, staged Read more).
- **Android `NewsReadingTest` (4 tests):** extraction, link safety, honest prompts and per-story cache, per-story state and staged Read more.
- **Browser `career_e2e.mjs`:** 8 reading checks, all passing (live BBC feed and Ollama).
- **Not run:** on a phone.

## Limits
- **Unreadable pages:** pages that need JavaScript, a subscription or a cookie wall give no text; then the reader uses the description and says so.
- **Google News items** are always "limited".
