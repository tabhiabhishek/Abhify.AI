package ai.abhify.android.voice

/**
 * 5.4 App Action Registry: the only way a voice command can operate Abhify. Commands are matched from **the user's own
 * words** by fixed patterns (English and Hindi), never from AI output or document text; every action is a registered,
 * typed entry with validated parameters, a permission class and a confirmation rule. Execution happens in the trusted
 * layer (VoiceState), which reports the real result. Scope: Abhify only (no shell, files, other apps or system settings).
 */
object AppActions {
    enum class Kind(val label: String) {
        NAVIGATION("opens a screen"), READ_ONLY("reads your Abhify records"), REVERSIBLE("creates something (confirm, can undo)"),
        SENSITIVE("changes or deletes data: only on the confirmation screen"),
    }

    data class Spec(val id: String, val description: String, val kind: Kind, val param: String? = null) {
        val needsConfirmation get() = kind == Kind.REVERSIBLE || kind == Kind.SENSITIVE
    }
    data class Match(val spec: Spec, val arg: String? = null)

    private fun nav(id: String, what: String) = Spec(id, "Open $what", Kind.NAVIGATION)
    val SPECS: List<Spec> = listOf(
        nav("open_home", "Home"), nav("open_assistant", "Assistant"), nav("open_study", "Study"), nav("open_news", "News"), nav("open_more", "More"),
        nav("open_knowledge_center", "Knowledge Center"), nav("open_documents", "Documents"), nav("open_projects", "Projects"),
        nav("open_tasks", "the AI Task Runner"), nav("open_settings", "Settings"), nav("open_voice_settings", "Voice & Conversation settings"),
        nav("open_history", "conversation history"), nav("open_memory", "Personal memory"), nav("open_models", "AI models"),
        Spec("open_last_conversation", "Open your last conversation", Kind.NAVIGATION),
        Spec("new_chat", "Start a new chat", Kind.NAVIGATION),
        Spec("search", "Search your chats, documents, knowledge and notes", Kind.NAVIGATION, "query"),
        Spec("discover", "Find courses and books on a topic (Study → Discover)", Kind.NAVIGATION, "topic"),
        Spec("model_status", "Say which AI will answer and the phone model's status", Kind.READ_ONLY),
        Spec("project_status", "Say where your last updated project stands (recorded status only)", Kind.READ_ONLY),
        Spec("recommendations", "Suggest what to do next from your projects, to-dos, study and tasks", Kind.READ_ONLY),
        Spec("study_progress", "Say your study progress", Kind.READ_ONLY),
        Spec("screen_help", "Explain the Abhify screen that is open", Kind.READ_ONLY),
        Spec("plan_day", "Say today's plan from your to-dos and project next actions", Kind.READ_ONLY),
        Spec("briefing", "Give your personal briefing (from your records)", Kind.READ_ONLY),
        Spec("weekly_review", "Say what you completed this week and what is still open", Kind.READ_ONLY),
        Spec("review_project", "Agent: find unfinished work in your last updated project and propose to-dos (you approve)", Kind.READ_ONLY),
        Spec("project_summary", "Agent: summarise your last updated project with blockers (read-only)", Kind.READ_ONLY),
        Spec("ask_knowledge", "Say what your records hold about a topic, with sources and dates", Kind.READ_ONLY, "topic"),
        Spec("create_task", "Create a to-do in a project", Kind.REVERSIBLE, "title"),
        Spec("create_note", "Create a note in a project", Kind.REVERSIBLE, "text"),
        Spec("sensitive", "Delete, clear, export or restore data, or change privacy/online settings", Kind.SENSITIVE),
    )
    fun spec(id: String) = SPECS.first { it.id == id }

    private val LEAD = Regex("^(?:(?:hey|hi|ok|okay)\\s+)?(?:abhify[,.!]?\\s*)?(?:please\\s+|can you\\s+|could you\\s+)?", RegexOption.IGNORE_CASE)
    private const val OPEN = "(?:open|go to|show(?: me)?|take me to|switch to)"
    private fun r(p: String) = Regex("^(?:$p)[.!?]?$", RegexOption.IGNORE_CASE)

    private val PATTERNS: List<Pair<Regex, String>> = listOf(
        r("$OPEN (?:the )?voice settings|voice settings") to "open_voice_settings",
        r("$OPEN (?:the )?knowledge cent(?:er|re)") to "open_knowledge_center",
        r("$OPEN (?:my |the )?(?:last|previous|recent) (?:conversation|chat)|(?:continue|resume) (?:my |the )?(?:last|previous) (?:conversation|chat)") to "open_last_conversation",
        r("(?:start|open|begin|new) (?:a )?new (?:chat|conversation)|new (?:chat|conversation)") to "new_chat",
        r("$OPEN (?:the )?home(?: screen| page)?|go home") to "open_home",
        r("$OPEN (?:the )?(?:assistant|chat)") to "open_assistant",
        r("$OPEN (?:the )?study(?: section| tab)?") to "open_study",
        r("$OPEN (?:the )?news") to "open_news",
        r("$OPEN (?:the )?more(?: tab)?") to "open_more",
        r("$OPEN (?:my |the )?(?:documents|library)") to "open_documents",
        r("$OPEN (?:my |the )?projects?") to "open_projects",
        r("$OPEN (?:my |the )?(?:tasks|ai tasks|task runner|ai task runner)") to "open_tasks",
        r("$OPEN (?:the )?settings") to "open_settings",
        r("$OPEN (?:my |the )?(?:conversation |chat )?history") to "open_history",
        r("$OPEN (?:my |the )?(?:personal )?memor(?:y|ies)") to "open_memory",
        r("$OPEN (?:my |the )?(?:ai )?models?") to "open_models",
        r("(?:show|what(?:'s| is)|check) (?:my |the )?(?:ai )?model(?: status)?|which (?:ai|model) (?:is|will) (?:answer|answering)") to "model_status",
        r("what are we working on|where did (?:i|we) leave off|(?:my |the )?project status|what(?:'s| is) the status of my project") to "project_status",
        r("what should i do(?: today| next| now)?|any suggestions|what do you recommend|suggest something to do") to "recommendations",
        r("(?:show |what(?:'s| is) )?my (?:study|learning) progress|how am i doing in (?:my )?stud(?:y|ies)") to "study_progress",
        r("plan my day|what(?:'s| is) (?:my |the )?plan(?: for)? today|make (?:me )?(?:a )?plan for today|what should i focus on today") to "plan_day",
        r("brief me|(?:give me |read me )?my (?:morning |daily )?briefing|(?:morning|daily) briefing") to "briefing",
        r("(?:my )?weekly review|what did i (?:finish|complete|get done) this week") to "weekly_review",
        r("(?:review my project and prepare tasks|find unfinished work(?: in my project)?|review my (?:last )?project)") to "review_project",
        r("summari[sz]e my (?:last )?project|what(?:'s| is) blocking (?:my |the )?project|(?:my )?project blockers") to "project_summary",
        r("what is this (?:screen|page)|explain this (?:screen|page)|help me with this (?:screen|page)|what does this (?:screen|page) do|explain this") to "screen_help",
        r("(?:delete|clear|erase|remove|wipe) .*(?:conversation|chat|history|memor|document|data|everything).*|export (?:all )?(?:my )?data|restore (?:a |my )?backup|(?:turn|switch) (?:on|off) online(?: ai)?|change (?:my )?privacy(?: settings)?") to "sensitive",
        // Hindi
        r("(?:होम|होम पेज) (?:खोलो|दिखाओ|पर जाओ)") to "open_home",
        r("(?:असिस्टेंट|चैट) (?:खोलो|दिखाओ)") to "open_assistant",
        r("(?:स्टडी|पढ़ाई) (?:खोलो|दिखाओ)") to "open_study",
        r("(?:न्यूज़|न्यूज|समाचार|खबरें) (?:खोलो|दिखाओ|सुनाओ)") to "open_news",
        r("(?:मेरे )?(?:प्रोजेक्ट्स|प्रोजेक्ट) (?:खोलो|दिखाओ)") to "open_projects",
        r("(?:सेटिंग्स|सेटिंग) (?:खोलो|दिखाओ)") to "open_settings",
        r("नई (?:चैट|बातचीत) (?:शुरू करो|खोलो)") to "new_chat",
        r("(?:आज )?(?:मुझे )?(?:आज )?क्या करना चाहिए|कोई सुझाव") to "recommendations",
        r("हम किस पर काम कर रहे (?:हैं|थे)") to "project_status",
        // 5.6: Hindi and Hinglish (romanised) for the read-only intelligence commands; anything that changes data still needs
        // the on-screen Confirm, and unclear speech falls through to an ordinary question
        r("(?:मेरा )?(?:आज का )?(?:दिन|प्लान) (?:प्लान करो|बनाओ|बताओ)|आज (?:का|की) (?:प्लान|योजना) (?:क्या है|बताओ)") to "plan_day",
        r("(?:mera )?(?:aaj ka )?(?:din|plan) (?:plan karo|banao|batao)|aaj ka plan(?: kya hai| batao)?") to "plan_day",
        r("(?:मुझे )?(?:ब्रीफ़|ब्रीफ|ब्रीफिंग) (?:करो|दो|सुनाओ)|आज की (?:ब्रीफिंग|खबर) सुनाओ") to "briefing",
        r("(?:mujhe )?brief karo|(?:meri |aaj ki )?briefing (?:do|sunao|batao)") to "briefing",
        r("(?:मेरे )?प्रोजेक्ट (?:का सारांश|की समरी) (?:बताओ|दो)|प्रोजेक्ट में क्या (?:अटका|रुका) है") to "project_summary",
        r("(?:mere )?project (?:ka summary|ki summary|ka saransh) (?:batao|do)|project (?:mein|me) kya (?:atka|ruka) hai") to "project_summary",
        r("(?:इस हफ्ते|हफ्ते का) (?:रिव्यू|समीक्षा)(?: करो| बताओ)?|(?:is hafte|hafte ka) review(?: karo| batao)?") to "weekly_review",
        r("(?:study|padhai) (?:kholo|dikhao)") to "open_study",
        r("(?:news|khabrein|khabar) (?:kholo|dikhao|sunao)") to "open_news",
    )
    private val ASK = Regex("^(?:what do (?:i|you) (?:have|know) (?:on|about)|what have i saved (?:on|about)) (.{2,80}?)[.!?]?$", RegexOption.IGNORE_CASE)
    private val SEARCH = Regex("^(?:find|search(?: for)?|look for|look up) my (.{2,80}?)[.!?]?$", RegexOption.IGNORE_CASE)
    private val DISCOVER = Regex("^(?:find|show|search(?: for)?) (?:me )?(?:free )?(?:courses?|lessons?|tutorials?|books?) (?:on|for|about) (.{2,60}?)[.!?]?$", RegexOption.IGNORE_CASE)
    private val TASK = Regex("^(?:create|add|make) (?:a )?(?:new )?(?:task|to-?do)(?: called| named| to|:)? (.{2,120}?)[.!]?$", RegexOption.IGNORE_CASE)
    private val NOTE = Regex("^(?:create|add|make|take) (?:a )?(?:new )?note(?: that| saying| about|:)? (.{2,500}?)[.!]?$", RegexOption.IGNORE_CASE)

    /** The registered action the user asked for, or null (then it's an ordinary question for the AI). */
    fun match(utterance: String): Match? {
        val t = utterance.trim().replace(Regex("\\s+"), " ").replace(LEAD, "").trim()
        if (t.isEmpty() || t.length > 600) return null
        TASK.matchEntire(t)?.let { return Match(spec("create_task"), clean(it.groupValues[1])) }
        NOTE.matchEntire(t)?.let { return Match(spec("create_note"), clean(it.groupValues[1])) }
        DISCOVER.matchEntire(t)?.let { return Match(spec("discover"), clean(it.groupValues[1])) }
        SEARCH.matchEntire(t)?.let { return Match(spec("search"), clean(it.groupValues[1])) }
        ASK.matchEntire(t)?.let { return Match(spec("ask_knowledge"), clean(it.groupValues[1])) }
        return PATTERNS.firstOrNull { it.first.matches(t) }?.let { Match(spec(it.second)) }
    }

    /** Parameter validation in the trusted layer: null = valid, else the reason. */
    fun validate(m: Match): String? = when (m.spec.param) {
        null -> null
        else -> when {
            m.arg.isNullOrBlank() -> "I need the ${m.spec.param} for that."
            m.arg.length > (if (m.spec.id == "create_note") 500 else 120) -> "That ${m.spec.param} is too long."
            m.arg.any { it.isISOControl() } -> "That ${m.spec.param} has characters I can't use."
            else -> null
        }
    }

    private fun clean(s: String) = s.trim().trimEnd('.', '!', '?').trim()

    // ------------------------------------------------------------------ screen help (what "this screen" is, from the app itself)
    val SCREEN_HELP = mapOf(
        "HOME" to "Home is your command center: ask anything, continue where you left off, see suggestions, active projects, learning, knowledge and the last saved news.",
        "CHAT" to "Assistant is the chat: ask questions, choose a focus like Coding or Learning, use your documents with Knowledge mode, and talk to me hands-free with the conversation button.",
        "STUDY" to "Study has your overview, Discover for real courses and books, the AI tutor, study plans, flashcards, quizzes, MCQs and interview questions.",
        "NEWS" to "News shows stories from public publishers in English, Hindi or both; you can summarize, translate, listen and bookmark them.",
        "MORE" to "More groups Knowledge, Workspace, Career, AI and System, and Settings and Privacy.",
        "DOCS" to "Documents is your library: import files, search them, ask about them and view PDF pages.",
        "CAREER" to "Career has your dashboard, resumes, question bank, MCQs, jobs and resume-to-job matching.",
        "KNOWLEDGE" to "Knowledge holds your saved items and workspaces with notes and tasks.",
        "SETTINGS" to "Settings has the AI mode, answer style, appearance, voice, performance and diagnostics.",
    )
}
