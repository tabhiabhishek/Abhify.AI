package ai.abhify.android

import ai.abhify.android.chat.Identity
import ai.abhify.android.chat.PromptBuilder
import ai.abhify.android.voice.VoiceSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** 7.1.0-dev.2: assistant identity (same cases as the desktop and web apps) and the voice style / pitch. */
class IdentityTest {
    private val cases = JSONObject(File("../../backend/tests/data/identity_cases.json").readText())

    @Test fun sharedNameRulesMatchTheDesktop() {
        val names = cases.getJSONArray("names")
        val wrong = (0 until names.length()).map { names.getJSONObject(it) }.mapNotNull { c ->
            val want = if (c.isNull("out")) null else c.getString("out")
            val got = runCatching { Identity.validate(c.getString("in")) }.getOrNull()
            if (got == want) null else "${c.getString("in")} → $got (want $want)"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test fun sharedIdentityQuestionsMatchTheDesktop() {
        val qs = cases.getJSONArray("questions")
        val wrong = (0 until qs.length()).map { qs.getJSONObject(it) }.mapNotNull { c ->
            val a = Identity.answer(c.getString("q"), "Nova")
            val got = when { a == null -> null; a.startsWith("Yes.") -> "rename"; else -> "name" }
            val want = if (c.isNull("a")) null else c.getString("a")
            if (got == want) null else "${c.getString("q")} → $got (want $want)"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
        assertTrue(Identity.answer("What is your name?", "Nova")!!.contains("Nova"))
    }

    @Test fun promptUsesTheCurrentNameAndNeverTheHardcodedOne() {
        val p = PromptBuilder.buildNamed("Nova", "hi", emptyList(), emptyList(), "")[0].content
        assertTrue(p, p.startsWith("You are Nova, the user's AI assistant in the Abhify.AI app") && !p.contains("You are Abhify.AI"))
        assertTrue(PromptBuilder.build("hi", emptyList(), emptyList(), "")[0].content.startsWith("You are Abhify, the AI assistant of the Abhify.AI app"))
        assertEquals("Abhify", Identity.saved(null)); assertEquals("Abhify", Identity.saved("<bad>")); assertEquals("Nova", Identity.saved("Nova"))
    }

    @Test fun voiceStylePersistsAndOlderStylesMapToTheThreeChoices() {                    // 7.1.1: Male / Female / Assistant only
        assertEquals(VoiceSettings.Style.MALE, VoiceSettings.fromJson(VoiceSettings(style = VoiceSettings.Style.MALE).toJson()).style)
        mapOf("ROBOT" to VoiceSettings.Style.MALE, "DEEP" to VoiceSettings.Style.MALE, "SOFT" to VoiceSettings.Style.FEMALE, "CUSTOM" to VoiceSettings.Style.ASSISTANT,
            "FRIENDLY" to VoiceSettings.Style.ASSISTANT, "ENERGETIC" to VoiceSettings.Style.ASSISTANT, "FEMALE" to VoiceSettings.Style.FEMALE).forEach { (old, now) ->
            assertEquals(old, now, VoiceSettings.fromJson("""{"style":"$old","customPitch":1.4,"voiceName":"x-local"}""").style) }
        assertEquals(VoiceSettings.SOFT_PITCH, VoiceSettings.fromJson("""{"style":"ROBOT","customPitch":0.5}""").pitch, 1e-4f)      // one soft tone, no effects
        assertNull(VoiceSettings.fromJson("""{"voiceName":"en-us-x-sfg-local"}""").voiceName)                 // the three choices decide the voice
        assertEquals(VoiceSettings.Style.ASSISTANT, VoiceSettings.fromJson("{}").style)
    }
}
