package ai.abhify.android

import ai.abhify.android.chat.Companion
import ai.abhify.android.chat.PromptBuilder.Msg
import ai.abhify.android.ui.Brand
import ai.abhify.android.voice.SpeechStream
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private fun srcOf(path: String) = File("src/main/java/ai/abhify/android/$path").readText()

/** 5.6 My Friendly AI: one personality, follow-ups resolved for retrieval, older turns summarised from the user's own words. */
class CompanionTest {
    private val h = listOf(Msg("user", "How does RAG chunking work in my Abhify knowledge base?"), Msg("assistant", "It splits documents…"))

    @Test fun followUpsUseTheActiveTopicForRetrieval() {
        assertTrue(Companion.isFollowUp("why?", h)); assertTrue(Companion.isFollowUp("and what about overlap?", h)); assertTrue(Companion.isFollowUp("explain that again", h))
        assertFalse(Companion.isFollowUp("What is the capital of France and why is it famous for art?", h))
        assertFalse(Companion.isFollowUp("why?", emptyList()))                                           // nothing to follow
        assertEquals("why? How does RAG chunking work in my Abhify knowledge base?", Companion.retrievalQuery("why?", h))
        assertEquals("Explain transformers", Companion.retrievalQuery("Explain transformers", h))
        val chain = h + Msg("user", "why?") + Msg("assistant", "Because…")                              // a follow-up of a follow-up keeps the real topic
        assertEquals(h[0].content, Companion.activeTopic(chain))
    }

    @Test fun earlierTurnsAreTheUsersOwnWordsAndSmallTalkStaysLight() {
        val long = (1..12).flatMap { listOf(Msg("user", "question $it about kotlin"), Msg("assistant", "answer $it")) }
        val e = Companion.earlier(long, 8)!!
        assertTrue(e.contains("\"question 8 about kotlin\"") && !e.contains("question 9 about") && !e.contains("answer"))   // only outside the window, only user text
        assertNull(Companion.earlier(h, 8))
        assertTrue(Companion.smallTalk("Thanks!")); assertTrue(Companion.smallTalk("good morning")); assertFalse(Companion.smallTalk("thanks, now explain LoRA"))
        assertTrue(Companion.turn("hi", emptyList()).contains("just chatting"))
    }

    @Test fun charterIsHonestAndIsUsedOnEveryChatPath() {
        assertTrue(Companion.CHARTER.contains("You are an AI: never claim feelings, experiences or memories"))
        assertFalse(Companion.CHARTER.contains("I feel"))
        val vm = srcOf("ui/AppViewModel.kt")
        assertTrue(vm.contains("val rq = ai.abhify.android.chat.Companion.retrievalQuery(question, history)"))
        assertTrue(vm.contains("RagIndex.retrieve(g.db, docIds, rq, 8") && vm.contains("brain.resolve(rq,"))
        // online prompts get the earlier-turn summary only when chat history may go online
        // 7.2.1: the default is PrivacyDefaults.ONLINE_HISTORY_NEW_INSTALL (off for new installs; existing installs keep theirs)
        assertTrue(vm.contains("Companion.turn(question, if (g.prefs.getBoolean(\"online_history\", ai.abhify.android.settings.PrivacyDefaults.ONLINE_HISTORY_NEW_INSTALL)) history else emptyList(), win)"))
    }
}

/** 5.6 Talk to Me: sentence streaming, ordering, cancellation on interruption, lifecycle. */
class SpeechStreamTest {
    @Test fun completedSentencesAreSpokenInOrderWhileGenerating() {
        val s = SpeechStream(1)
        assertTrue(s.feed("Retrieval augmented generation adds your documents").isEmpty())            // unfinished sentence: wait
        assertEquals(listOf("Retrieval augmented generation adds your documents to the prompt."), s.feed("Retrieval augmented generation adds your documents to the prompt. It then"))
        assertTrue(s.feed("Retrieval augmented generation adds your documents to the prompt. It then").isEmpty())   // nothing twice
        assertEquals(listOf("It then cites them."), s.finish("Retrieval augmented generation adds your documents to the prompt. It then cites them [S1]."))
        assertEquals(2, s.spoken.size)
    }

    @Test fun hindiDandaCodeBlocksAndCancellation() {
        val hi = SpeechStream(2, minChars = 10)
        assertEquals(1, hi.feed("यह एक छोटा उत्तर है। और").size)
        val code = SpeechStream(3)
        assertTrue(code.feed("Here is the code:\n```kotlin\nval x = 1. val y = 2. more more more more\n").isEmpty())   // never mid code block
        val c = SpeechStream(4); c.cancel()
        assertTrue(c.feed("A complete sentence that is long enough to speak. Another.").isEmpty() && c.finish("x y z.").isEmpty())   // interrupted: no stale audio
    }

    @Test fun interruptionStopsSpeechAndGenerationAndListensAgain() {
        val v = srcOf("ui/VoiceState.kt")
        val i = v.substringAfter("fun interrupt()").substringBefore("fun onPartialAnswer")
        assertTrue(i.contains("stream?.cancel(); stream = null") && i.contains("awaitingAnswer = false") && i.contains("vm.stopSpeaking()") && i.contains("if (wasGenerating) vm.stop()"))
        assertTrue(i.indexOf("awaitingAnswer = false") < i.indexOf("vm.stop()"))                       // the cancelled answer is never spoken afterwards
        assertTrue(v.contains("stream?.cancel(); stream = ai.abhify.android.voice.SpeechStream(++turnSeq)"))   // one stream per turn
        assertTrue(srcOf("ui/AppViewModel.kt").contains("if (voiceTurn) voiceState.onPartialAnswer(sb.toString())"))
        val voice = srcOf("voice/Voice.kt")
        assertTrue(voice.contains("TextToSpeech.QUEUE_ADD, null, \"abhify-\${from + k}\"") && voice.contains("AUDIOFOCUS_LOSS_TRANSIENT) pauseSpeaking()"))
        assertTrue(srcOf("ui/VoiceScreens.kt").contains("Lifecycle.Event.ON_STOP) v.stop()"))           // no microphone in the background
        assertFalse(File("src/main/AndroidManifest.xml").readText().contains("FOREGROUND_SERVICE_MICROPHONE"))
    }
}

/** Brand tokens (shared with the web's :root). 7.0 replaced the 5.6 "Neural" values with the approved "Synapse" set. */
class NeuralTokensTest {
    @Test fun charcoalVioletAndElectricCyan() {
        assertEquals(Color(0xFF06070D), Brand.Bg1); assertEquals(Color(0xFF0E1020), Brand.Surface); assertEquals(Color(0xFF3DE0FF), Brand.Cyan)
        assertEquals(Color(0xFF7350F5), Brand.Violet); assertEquals(Color(0xFF9D7BFF), Brand.Synapse)
        assertTrue(srcOf("ui/Aurora.kt").contains("Triple(Color(0xFFF1ECFF), Brand.Synapse, Color(0xFF1B1450))"))   // the Synapse core
    }
}

/** 5.6 provider reliability: a rate-limited provider rests (Retry-After honoured) instead of being asked again at once. */
class ProviderCooldownTest {
    @Test fun rateLimitCooldownSkipsTheProviderThenRecovers() {
        val s = ai.abhify.android.providers.ProviderStore(MemKv(), MemBox())
        s.saveKey(ai.abhify.android.providers.Provider.GROQ, "gsk_FAKEtestkey00000000000000000000abcd")
        val p = ai.abhify.android.providers.Provider.GROQ
        assertTrue(s.usable(p))
        s.coolDown(p, "30", now = 1_000L)
        assertEquals(30_000L, s.cooldownLeft(p, now = 1_000L))
        assertEquals(60_000L, ai.abhify.android.providers.ProviderRules.cooldownMs(null)); assertEquals(900_000L, ai.abhify.android.providers.ProviderRules.cooldownMs("99999"))
        assertEquals(0L, s.cooldownLeft(p, now = 31_001L))
        assertTrue(srcOf("providers/Providers.kt").contains("if (r.code == 429) store.coolDown(p, r.headers[\"retry-after\"])"))
    }
}

/** 5.6 bilingual voice commands: Hindi and Hinglish reach the same read-only commands; nothing destructive is matched loosely. */
class BilingualCommandsTest {
    private fun id(s: String) = ai.abhify.android.voice.AppActions.match(s)?.spec?.id
    @Test fun hindiAndHinglishCommands() {
        assertEquals("plan_day", id("आज का प्लान बताओ")); assertEquals("plan_day", id("aaj ka plan batao")); assertEquals("plan_day", id("mera din plan karo"))
        assertEquals("briefing", id("brief karo")); assertEquals("briefing", id("मुझे ब्रीफिंग सुनाओ"))
        assertEquals("project_summary", id("project mein kya atka hai")); assertEquals("project_summary", id("प्रोजेक्ट में क्या अटका है"))
        assertEquals("weekly_review", id("is hafte review karo"))
        assertEquals("open_study", id("padhai kholo")); assertEquals("open_news", id("khabrein sunao"))
        assertNull(id("plan ke baare mein kuch batao yaar"))                                              // unclear → an ordinary question
        ai.abhify.android.voice.AppActions.SPECS.forEach { s -> assertTrue(s.id.isNotBlank()) }
    }
}
