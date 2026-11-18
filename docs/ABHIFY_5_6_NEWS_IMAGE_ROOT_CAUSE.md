# News refresh image bug: root cause and fix (5.6)

**Symptom (reported):** after refreshing News, the first (featured) story kept showing its previous image. The other stories showed their new images.

## How images flow
1. The feed (RSS/Atom/Google News) is parsed: `ArticleParser` on Android, `news_center` on the desktop.
2. Each article gets an id: SHA-1 of its normalised URL.
3. `merge` removes duplicates across feeds.
4. The merged list goes into `NewsState.result` on Android, or `renderNewsCenter` on the web.
5. Each card is drawn and loads its image: `RemoteImage` with `ImageCache` (memory LRU cache keyed by URL) on Android; a plain `<img>` and the browser cache on the web.

## Root cause 1 (Android, the reported bug): image state not tied to its URL
- **What the old code did:** `RemoteImage` used `produceState(initialValue = cache[url], key1 = url) { if (value == null) value = load(url) }`.
- **Why that kept the old image:** Compose's `produceState` keeps its state in a `remember { }` **without a key**. When the same card is reused for a different URL, the state still holds the **old bitmap**. The `value == null` guard then skips loading the new URL, so the old picture stays.
- **Why the first story was the one affected:** a refresh keeps the previous stories on screen while it loads (stale-while-revalidate), so cards are reused, not recreated. The featured story is a single fixed slot (`list.firstOrNull { it.imageUrl != null }`), and the cards were not keyed by article. So whenever the first story changed, that slot got a new article but kept its old image state.
- **Also checked:**
  - The `ImageCache` key is the URL, so the cache itself was correct.
  - The fetch uses `useCaches = false`, so there is no stale HTTP cache.
  - The single-refresh guard in `NewsState` was already in place, so concurrent refreshes were not the cause.

## Root cause 2 (both platforms): a duplicate copy could drop a valid image
When the same story came from two feeds, `merge` kept the newest or prioritised copy. If that copy had no image, a valid image from the other copy of the **same** story was dropped.

## Fix
| Where | Change |
|---|---|
| `ui/RemoteImage.kt` | `ImageSlot` holds the image state for **one URL**, created with `remember(url)`, and loading runs in `LaunchedEffect(url)`. A new URL starts empty, so the placeholder shows, never the old picture. A slow load for an old URL can only write into that old, discarded slot. |
| `ui/NewsScreens.kt` | Featured and list cards are wrapped in `key(article.id)`, so a card, and its image, always belongs to one story. |
| `ui/NewsState.kt` | A refresh sequence number: an older refresh finishing late is dropped (`if (seq != loadSeq) return@launch`), on top of the existing cancel. |
| `news/NewsCenter.kt` `merge`, `backend/news_center.py` `merge` | The kept copy takes the image from another copy of the **same** story (same URL id, or same publisher and title), never from a different story. |
| Web `renderNewsCenter` | Not affected by root cause 1: it rebuilds every card's `<img>` from that article's own `image_url` on each render, and it already had a `newsSeq` guard. A regression test covers this. |

No cache-busting parameters were added: the image resources themselves were never stale.

## Cases covered
- **Valid, missing and broken images:** a missing or failed image shows the same placeholder (`ImageSlot.failed`, the web's `ph` class).
- **Images that change:** a changed image URL, or the same article with an updated URL, gets a new slot.
- **Reordered stories:** the cards are keyed by article.
- **Refresh timing:** a slow old image load can't reach a new card, a slow old refresh is dropped, and consecutive refreshes are handled.
- **Offline:** cached news still works; `center.cached()` is unchanged.
- **No extra requests:** an unchanged URL keeps its slot and is not reloaded.

## Regression tests
- **`NewsImageTest` (Android JVM):**
  - first reproduces the **old behaviour** (an unkeyed slot keeps image A after the URL changes to B), then shows the fix;
  - repeated refreshes and a late old load never cross over;
  - missing and broken images fall back to the placeholder;
  - cards are keyed by article id, and a superseded refresh is dropped;
  - a duplicate story inherits only its own image.
- **`test_news_center.py::test_duplicate_story_keeps_image_of_same_story_only` (backend).**
- **Web:** a JS-harness test for the render path (see the test report).

**Still to check on a phone:** refresh News several times and confirm the featured image changes with the story (checklist test 61).
