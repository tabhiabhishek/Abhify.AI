package ai.abhify.android

import ai.abhify.android.news.NewsArticle
import ai.abhify.android.news.NewsNormalizer
import ai.abhify.android.news.NewsPrefs
import ai.abhify.android.ui.ImageSlot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 5.6 regression: after a News refresh the first (featured) story kept the previous story's image. Root cause: the image state
 * was remembered without a URL key (produceState's unkeyed `remember`) and loading was skipped while it held any bitmap.
 */
class NewsImageTest {
    /** The pre-5.6 behaviour, modelled exactly: one state per card slot, kept across URL changes, load guarded by `value == null`. */
    private class OldSlot { var value: String? = null
        suspend fun onUrl(url: String, load: suspend (String) -> String?) { if (value == null) value = load(url) } }

    private suspend fun fakeLoad(url: String) = "bitmap-of-$url"

    @Test fun reproducesTheOldBugAndShowsTheFix() = runTest {
        val old = OldSlot(); old.onUrl("https://a/1.jpg", ::fakeLoad); old.onUrl("https://b/2.jpg", ::fakeLoad)
        assertEquals("bitmap-of-https://a/1.jpg", old.value)                                         // the bug: refreshed card shows the old image

        val a = ImageSlot.forUrl<String>(null, "https://a/1.jpg") { null }; a.resolve(::fakeLoad)
        val b = ImageSlot.forUrl(a, "https://b/2.jpg") { null }
        assertNotSame(a, b); assertNull(b.value)                                                       // new URL starts empty: placeholder, never the old picture
        b.resolve(::fakeLoad); assertEquals("bitmap-of-https://b/2.jpg", b.value)
        assertSame(b, ImageSlot.forUrl(b, "https://b/2.jpg") { null })                                 // unchanged URL keeps its slot (no reload)
    }

    @Test fun repeatedRefreshesAndALateOldLoadNeverCrossOver() = runTest {
        val slowA = CompletableDeferred<String?>()
        val a = ImageSlot.forUrl<String>(null, "https://a/1.jpg") { null }
        val pending = async { a.resolve { slowA.await() } }
        val b = ImageSlot.forUrl(a, "https://b/2.jpg") { null }; b.resolve(::fakeLoad)
        val c = ImageSlot.forUrl(b, "https://c/3.jpg") { "cached-c" }                                  // memory-cache hit shows at once
        slowA.complete("late-bitmap-a"); pending.await()
        assertEquals("late-bitmap-a", a.value); assertEquals("bitmap-of-https://b/2.jpg", b.value); assertEquals("cached-c", c.value)
    }

    @Test fun missingAndBrokenImagesFallBackConsistently() = runTest {
        val none = ImageSlot.forUrl<String>(null, null) { null }; none.resolve(::fakeLoad); assertNull(none.value)
        val broken = ImageSlot.forUrl<String>(null, "https://x/broken.jpg") { null }; broken.resolve { null }
        assertNull(broken.value); assertTrue(broken.failed)
    }

    @Test fun cardsAreKeyedByArticleAndOldRefreshesAreDropped() {
        val ui = File("src/main/java/ai/abhify/android/ui/NewsScreens.kt").readText()
        assertTrue(ui.contains("androidx.compose.runtime.key(f.id) { FeaturedCard(") && ui.contains("androidx.compose.runtime.key(a.id) { ArticleCard("))
        val img = File("src/main/java/ai/abhify/android/ui/RemoteImage.kt").readText()
        assertTrue(img.contains("remember(url) { ImageSlot(url,") && !img.contains("produceState("))
        val st = File("src/main/java/ai/abhify/android/ui/NewsState.kt").readText()
        assertTrue(st.contains("val seq = ++loadSeq") && st.contains("if (seq != loadSeq) return@launch"))
    }

    @Test fun duplicateStoryKeepsTheImageOfTheSameStoryOnly() {
        fun art(id: String, title: String, pub: String, t: Long, img: String?) = NewsArticle(id, title, pub, "https://x/$id", t, t, "world", "", img, "rss")
        val m = NewsNormalizer.merge(listOf(listOf(art("a", "Rain in Delhi", "BBC", 9, null)), listOf(art("a", "Rain in Delhi", "BBC", 5, "https://img/a.jpg")),
            listOf(art("b", "Markets", "Mint", 8, null), art("c", "Cricket", "ESPN", 7, "https://img/c.jpg"))), NewsPrefs())
        assertEquals(listOf("https://img/a.jpg", null, "https://img/c.jpg"), m.map { it.imageUrl })   // b never borrows c's image
    }
}
