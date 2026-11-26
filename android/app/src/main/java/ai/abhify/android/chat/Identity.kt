package ai.abhify.android.chat

/**
 * 7.1.0-dev.2: the assistant's personal name on THIS phone (separate from the product name Abhify.AI). Stored in the app's own
 * preferences ("assistant_name"); the Windows and Web apps keep their own name, nothing is shared or synced.
 * Same rules and cases as the desktop (backend/identity.py) and the web app: backend/tests/data/identity_cases.json.
 */
object Identity {
    const val DEFAULT_NAME = "Abhify"
    const val MAX_LEN = 24
    val SUGGESTIONS = listOf("Abhi", "Aira", "Nova", "Astra", "Ava", "Alex", "Echo", "Aria", "Orion", "Athena", "Jarvis", "Neo", "Friday")

    /** The clean name, or IllegalArgumentException with a message for the user. */
    fun validate(raw: String?): String {
        val s = raw.orEmpty()
        if (s.any { Character.getType(it).toByte() in BAD_TYPES }) throw IllegalArgumentException("The name can't contain control or invisible characters.")
        val n = s.replace(Regex("\\s+"), " ").trim()
        if (n.isEmpty()) throw IllegalArgumentException("Enter a name for your assistant.")
        if (n.codePointCount(0, n.length) > MAX_LEN) throw IllegalArgumentException("Use at most $MAX_LEN characters.")
        val kinds = n.map { kind(it) }
        if ('x' in kinds || kinds.first() != 'L' || kinds.last() == 'P' || n.none { it.isLetter() })
            throw IllegalArgumentException("Use letters, and optionally digits, spaces, apostrophes, dots or hyphens.")
        return n
    }

    /** The saved name, falling back to the default when it is missing or invalid. */
    fun saved(raw: String?): String = runCatching { validate(raw ?: DEFAULT_NAME) }.getOrDefault(DEFAULT_NAME)

    /** Replaces "You are Abhify.AI," at the start of the prompts. */
    fun intro(name: String): String = if (name == DEFAULT_NAME) "You are Abhify, the AI assistant of the Abhify.AI app,"
        else "You are $name, the user's AI assistant in the Abhify.AI app (the user chose the name $name),"

    private val RENAME = Regex("(?i)\\b(?:can|could|may|how\\s+(?:do|can))\\s+i\\s+(?:change|rename|choose|pick|set)\\s+(?:your\\s+name|you)\\b|\\b(?:change|rename)\\s+(?:your\\s+name|you)\\b")
    private val ASK = Regex("(?i)^\\W*(?:(?:hi|hey|hello)[\\s,!]+)?(?:what(?:'s|\\s+is)\\s+your\\s+name|your\\s+name|who\\s+are\\s+you|" +
        "what\\s+(?:should|do|can)\\s+i\\s+call\\s+you|tell\\s+me\\s+your\\s+name|do\\s+you\\s+have\\s+a\\s+name)(?:\\s*,?\\s*\\p{L}+)?\\W*$")

    /** The app's own reply to an identity question ("what is your name?"), or null for anything else. */
    fun answer(message: String, name: String): String? {
        val t = message.trim()
        if (t.length > 120) return null
        if (RENAME.containsMatchIn(t)) return "Yes. I'm $name now, and you can rename me any time: More → Settings → AI personalization → Assistant identity. " +
            "The new name is used in chat, in my answers and in voice on this phone."
        if (ASK.matches(t)) return "I'm $name, your AI assistant in Abhify.AI. I run on this phone, and you can rename me in Settings → AI personalization."
        return null
    }

    private val BAD_TYPES = setOf(Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED)
    private fun kind(c: Char): Char = when (Character.getType(c).toByte()) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER, Character.OTHER_LETTER,
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> 'L'
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> 'M'
        else -> if (c in " .'-") 'P' else 'x'
    }
}
