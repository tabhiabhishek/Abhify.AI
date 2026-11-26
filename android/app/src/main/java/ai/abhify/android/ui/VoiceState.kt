package ai.abhify.android.ui

import ai.abhify.android.chat.addTasks
import ai.abhify.android.chat.addWorkspace
import ai.abhify.android.chat.conversationPage
import ai.abhify.android.chat.deleteNote
import ai.abhify.android.chat.deleteTask
import ai.abhify.android.chat.deckStats
import ai.abhify.android.chat.planStats
import ai.abhify.android.chat.saveNote
import ai.abhify.android.chat.tasks
import ai.abhify.android.chat.workspaces
import ai.abhify.android.voice.AppActions
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 5.4 Personal Voice Companion: the hands-free conversation loop and the trusted executor of registered app actions.
 *
 * Loop: listen (live transcript) → the words are either a registered action (executed here, real result spoken) or a
 * question (sent as an ordinary chat message: streamed, stored locally like any chat) → the answer is spoken → listen
 * again (if auto-listen is on). Tapping the core while Abhify speaks interrupts it and listens at once. Stop ends it.
 * Nothing runs in the background: the loop exists only while the conversation panel is open.
 */
class VoiceState(private val vm: AppViewModel) {
    private val g = vm.g
    val active = mutableStateOf(false)
    /** The recogniser returned words and Abhify is deciding what to do with them (PROCESSING). */
    val processing = mutableStateOf(false)
    /** Briefly true after the user interrupted speech (INTERRUPTED). */
    val interrupted = mutableStateOf(false)
    /** The last thing said by an app action (shown in the panel; actions aren't chat messages). */
    val actionReply = mutableStateOf<String?>(null)
    /** A reversible action waiting for the user's tap (Confirm / Cancel); never executed without it. */
    val pending = mutableStateOf<AppActions.Match?>(null)
    /** Undo for the last reversible action (removes exactly what was created). */
    val undo = mutableStateOf<Pair<String, () -> Unit>?>(null)
    val level = mutableFloatStateOf(0f)
    /** Set when this loop sent a chat question, so its answer is spoken and the next turn starts after it. */
    @Volatile var awaitingAnswer = false
    /** 5.6: the current turn's sentence stream (spoken while generating); replaced each turn, cancelled on interruption. */
    private var stream: ai.abhify.android.voice.SpeechStream? = null
    private var turnSeq = 0
    /** 5.6: how many sentences of the current answer were handed to speech (shown as "speaking" progress). */
    val spokenParts = androidx.compose.runtime.mutableIntStateOf(0)
    /** 7.1.1: set when the next end of speech must not start listening again. */
    @Volatile private var noRelisten = false
    /** 7.1.1: a failure in any voice task is shown in the panel (and the panel stays usable); it never closes the app. */
    private val guard = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        android.util.Log.w("AbhifyVoice", "voice task failed: ${e.javaClass.simpleName}")
        processing.value = false
        vm.voiceError.value = "That didn't work (${e.javaClass.simpleName}). Nothing was changed; tap the microphone to try again."
    }

    fun start() {
        if (!vm.voiceSettings.value.enabled) { vm.voiceError.value = "The voice assistant is off in Settings → Voice & Conversation."; return }
        active.value = true; actionReply.value = null; listen()
    }

    fun stop() {
        stream?.cancel(); stream = null
        active.value = false; awaitingAnswer = false; pending.value = null
        vm.cancelVoice(); vm.stopSpeaking(); level.floatValue = 0f
        vm.resumeWakeSoon()                                                 // 7.1.1: back to listening for the name, if that's on
    }

    /** 7.1.1: the user called the assistant by name; what followed the name (if anything) is the first turn. */
    fun startFromWake(command: String) {
        if (!vm.voiceSettings.value.enabled) return
        active.value = true; actionReply.value = null
        if (command.isBlank()) listen() else handle(command)
    }

    fun listen() {
        noRelisten = false
        if (!active.value || vm.listening.value) return
        val s = vm.voiceSettings.value
        vm.startVoice(languageTag = s.recognitionTag(java.util.Locale.getDefault().toLanguageTag())) { text -> handle(text) }
    }

    /** Tap on the core: interrupt speech (if allowed or always via the stop button) and listen. */
    fun tapCore() {
        if (vm.speaking.value || (awaitingAnswer && vm.generating.value)) interrupt() else if (vm.listening.value) vm.stopVoice() else listen()
    }

    /**
     * 5.6 interruption: stops speech at once, discards the rest of this turn's sentences (the stream is cancelled, so late
     * text is never spoken), stops the answer still being generated (what was written so far is kept in the chat, as with
     * Stop), and listens for the next turn. Repeated taps are safe: each one only cancels what is current.
     */
    fun interrupt() {
        stream?.cancel(); stream = null
        val wasGenerating = awaitingAnswer && vm.generating.value
        awaitingAnswer = false                                              // its completion must not be spoken again
        vm.stopSpeaking()
        if (wasGenerating) vm.stop()
        interrupted.value = true
        vm.viewModelScope.launch(guard) { delay(1200); interrupted.value = false }
        if (!vm.listening.value) vm.viewModelScope.launch(guard) { delay(250); listen() }
    }

    /** 5.6: called with the answer so far while it streams; completed sentences are spoken in order. */
    fun onPartialAnswer(text: String) {
        val s = stream ?: return
        if (!active.value || !awaitingAnswer || !vm.voiceSettings.value.speaks) return
        val parts = s.feed(text)
        if (parts.isNotEmpty()) { vm.speakAppend(parts); spokenParts.intValue = s.spoken.size }
    }

    /** Called when speech ends: the next turn starts automatically in a conversation (natural turn-taking). */
    fun onSpeechFinished() {
        if (noRelisten) { noRelisten = false; return }                     // 7.1.1: after "I can't answer yet" the microphone stays off
        if (active.value && vm.voiceSettings.value.autoListen && !vm.generating.value && pending.value == null && !vm.speechPaused.value)
            vm.viewModelScope.launch(guard) { delay(350); if (active.value && !vm.speaking.value && !vm.generating.value && !vm.speechPaused.value) listen() }   // a pause isn't the end
    }

    /** Called when an answer this loop asked for has finished (spoken next, or the next turn if voice is off). */
    fun onAnswerDone(text: String, ok: Boolean) {
        if (!awaitingAnswer) return
        awaitingAnswer = false
        val s = stream; stream = null
        if (!active.value) return
        if (!(ok && vm.voiceSettings.value.speaks && text.isNotBlank())) { s?.cancel(); if (!vm.speaking.value) onSpeechFinished(); return }
        if (s == null || s.spoken.isEmpty()) { vm.speak(text); return }       // nothing streamed yet: speak the whole answer
        val tail = s.finish(text)
        if (tail.isNotEmpty()) vm.speakAppend(tail) else if (!vm.speaking.value) onSpeechFinished()
    }

    private fun handle(text: String) = try { handleUnsafe(text) } catch (e: Exception) {
        processing.value = false
        vm.pendingInput.value = text
        vm.voiceError.value = "Something went wrong with \"${text.take(80)}\" (${e.javaClass.simpleName}). Your words are in the message box; nothing was changed."
    }

    private fun handleUnsafe(text: String) {
        processing.value = true
        val m = AppActions.match(text)
        if (m == null) {
            // 7.1.1: no AI set up (no model, no provider, offline…): say so, keep the words for later, don't loop the microphone
            vm.assistantProblem()?.let { why ->
                processing.value = false; awaitingAnswer = false; noRelisten = true
                vm.pendingInput.value = text
                say("I heard: \"${text.take(120)}\". I can't answer yet: $why Your words are in the message box, so you can send them once that's set up.", listenAfter = false)
                return
            }
            actionReply.value = null; awaitingAnswer = true
            stream?.cancel(); stream = ai.abhify.android.voice.SpeechStream(++turnSeq); spokenParts.intValue = 0
            vm.tab.value = AppViewModel.Tab.CHAT; vm.home()
            vm.send(text)                                     // an ordinary question: the chat (routing, consent, memory rules) answers
            processing.value = false
            return
        }
        processing.value = false
        AppActions.validate(m)?.let { say(it); return }
        when (m.spec.kind) {
            AppActions.Kind.REVERSIBLE -> { pending.value = m; say(confirmQuestion(m), listenAfter = false) }
            AppActions.Kind.SENSITIVE -> { vm.go(AppViewModel.Route.PRIVACY)
                say("For your safety, deleting, exporting or restoring data and changing privacy settings need your confirmation on screen. I've opened Privacy and data for you.") }
            else -> execute(m)
        }
    }

    private fun confirmQuestion(m: AppActions.Match) = when (m.spec.id) {
        "create_task" -> "Shall I add the to-do \"${m.arg}\" to ${targetName()}? Tap Confirm or Cancel."
        else -> "Shall I save this note in ${targetName()}? Tap Confirm or Cancel."
    }
    private fun targetName() = (vm.chatWorkspace.value?.name ?: g.db.workspaces().firstOrNull()?.name)?.let { "\"$it\"" } ?: "a new project called Inbox"

    /** The user tapped Confirm on a reversible action. */
    fun confirm() {
        val m = pending.value ?: return
        pending.value = null
        vm.viewModelScope.launch(guard) {
            val r = withContext(Dispatchers.IO) { runCatching {
                val ws = vm.chatWorkspace.value ?: g.db.workspaces().firstOrNull() ?: ai.abhify.android.chat.Workspace(g.db.addWorkspace("Inbox", "project"), "Inbox", "project")
                when (m.spec.id) {
                    "create_task" -> { g.db.addTasks(ws.id, listOf(m.arg!!)); val id = g.db.tasks(ws.id).filter { it.title == m.arg.take(300) }.maxOf { it.id }
                        Triple("Added the to-do \"${m.arg}\" to \"${ws.name}\".", "Undo to-do") { g.db.deleteTask(id) } }
                    else -> { val id = g.db.saveNote(null, ws.id, m.arg!!.lineSequence().first().take(60), m.arg)
                        Triple("Saved the note in \"${ws.name}\".", "Undo note") { g.db.deleteNote(id) } }
                }
            } }
            r.onSuccess { (msg, label, undoIt) ->
                undo.value = label to { vm.viewModelScope.launch(guard) { withContext(Dispatchers.IO) { undoIt() }; undo.value = null; say("Undone.") } }
                vm.studio.loadWork(); say(msg)
            }.onFailure { say("That didn't work: ${it.message ?: "unknown error"}. Nothing was changed.") }
        }
    }
    fun cancel() { pending.value = null; say("Cancelled. Nothing was changed.") }

    private fun go(tab: AppViewModel.Tab) { vm.home(); vm.tab.value = tab }

    /** Runs a navigation or read-only action and says what actually happened. */
    private fun execute(m: AppActions.Match) {
        when (m.spec.id) {
            "open_home" -> { go(AppViewModel.Tab.HOME); say("Here's Home.") }
            "open_assistant" -> { go(AppViewModel.Tab.CHAT); say("Here's the Assistant.") }
            "open_study" -> { go(AppViewModel.Tab.STUDY); say("Opened Study.") }
            "open_news" -> { go(AppViewModel.Tab.NEWS); say("Opened News.") }
            "open_more" -> { go(AppViewModel.Tab.MORE); say("Opened More.") }
            "open_documents" -> { go(AppViewModel.Tab.DOCS); say("Opened Documents.") }
            "open_knowledge_center" -> { vm.go(AppViewModel.Route.SEMANTIC); say("Opened the Knowledge Center.") }
            "open_projects" -> { vm.projects.openList(); say("Here are your projects.") }
            "open_tasks" -> { vm.go(AppViewModel.Route.TASKS); say("Opened the AI Task Runner.") }
            "open_settings" -> { vm.go(AppViewModel.Route.SETTINGS); say("Opened Settings.") }
            "open_voice_settings" -> { vm.go(AppViewModel.Route.SETTINGS); say("Opened Settings. Voice and Conversation is in the list.") }
            "open_history" -> { vm.go(AppViewModel.Route.HISTORY); say("Opened your conversation history.") }
            "open_memory" -> { vm.go(AppViewModel.Route.MEMORIES); say("Opened Personal memory.") }
            "open_models" -> { vm.go(AppViewModel.Route.MODELS); say("Opened AI models.") }
            "new_chat" -> { vm.newChat(); say("Started a new chat. What would you like to talk about?") }
            "open_last_conversation" -> vm.viewModelScope.launch(guard) {
                val c = withContext(Dispatchers.IO) { g.db.conversationPage(false, null, null, 1, 0).firstOrNull() }
                if (c == null) say("You don't have any saved conversations yet.") else { vm.loadConversation(c.id); go(AppViewModel.Tab.CHAT); say("Opened \"${c.title}\".") }
            }
            "search" -> { vm.hub.query.value = m.arg.orEmpty(); vm.hub.search(); vm.go(AppViewModel.Route.SEARCH); say("Searching for ${m.arg}.") }
            "discover" -> { go(AppViewModel.Tab.STUDY); vm.learnMode.value = 7; vm.discover.search(m.arg.orEmpty()); say("Looking for courses and books on ${m.arg}.") }
            "model_status" -> { val r = vm.routeExplanation(); val model = vm.activeModel()?.name
                say("${r.route}. " + (if (model != null) "The phone model is $model." else "No AI model is downloaded on this phone.")) }
            "screen_help" -> say(if (!vm.voiceSettings.value.screenHelp) "Screen help is turned off in Voice and Conversation settings."
                else screenHelp())
            "project_status" -> vm.viewModelScope.launch(guard) { say(withContext(Dispatchers.IO) { projectStatus() }) }
            "recommendations" -> { vm.companion.load(); vm.viewModelScope.launch(guard) { delay(600); say(recommendations()) } }
            "study_progress" -> vm.viewModelScope.launch(guard) { say(withContext(Dispatchers.IO) { studyProgress() }) }
            "plan_day" -> vm.viewModelScope.launch(guard) { val p = vm.brain.planNow()
                say(if (p == null || p.entries.isEmpty()) "There are no open to-dos or recorded next actions, so there's nothing to plan. You could add to-dos to a project."
                    else "Here's a plan for about ${p.minutesPlanned} minutes. " + p.entries.take(4).joinToString(" ") { "${it.title}, because: ${it.reason.lowercase()}." } +
                        (if (p.blocked.isNotEmpty()) " ${p.blocked.size} to-do(s) are marked blocked." else "")) }
            "briefing" -> vm.brain.makeBriefing(ai.abhify.android.intel.Briefing.Kind.ON_DEMAND) { b -> vm.openBriefing(); say(b.spoken()) }
            "weekly_review" -> vm.viewModelScope.launch(guard) { val w = vm.brain.weekNow()
                say(if (w == null) "I couldn't read your to-dos." else (if (w.completed.isEmpty()) "No to-dos were marked done in the last 7 days." else
                    "This week you completed ${w.completed.size}: ${w.completed.take(3).joinToString()}.") + " ${w.stillOpen} are still open" +
                    (if (w.overdue > 0) ", ${w.overdue} past their due date." else ".")) }
            "review_project" -> vm.viewModelScope.launch(guard) {
                val ws = withContext(Dispatchers.IO) { g.db.workspaces().filter { !vm.projects.isArchived(it.id) }.maxByOrNull { vm.projects.meta(it.id).status.updatedAt } }
                if (ws == null) say("You don't have a project to review yet.")
                else { vm.agent.reviewProject(ws.id); say("I'm reviewing \"${ws.name}\". I'll show the unfinished work I find; nothing is created until you approve it.") } }
            "project_summary" -> vm.viewModelScope.launch(guard) {
                val ws = withContext(Dispatchers.IO) { g.db.workspaces().filter { !vm.projects.isArchived(it.id) }.maxByOrNull { vm.projects.meta(it.id).status.updatedAt } }
                if (ws == null) say("You don't have a project yet.")
                else { vm.agent.run(ws.id, AgentState.Workflow.STATUS); say("Here's the summary of \"${ws.name}\" from its saved records, with blockers and possible duplicates on screen.") } }
            "ask_knowledge" -> vm.viewModelScope.launch(guard) { val r = vm.brain.resolve(m.arg.orEmpty())
                say(if (r.items.isEmpty()) "I found nothing about ${m.arg} in your records." else "From your records about ${m.arg}: " +
                    r.items.take(3).joinToString(" ") { "${it.record.source.label} \"${it.record.title}\", ${it.freshness.label}." }) }
            else -> say("I can't do that yet.")
        }
    }

    private fun screenHelp(): String {
        val route = vm.route
        if (route != AppViewModel.Route.HOME) return "This is ${route.name.lowercase().replace('_', ' ')} in Abhify. Say \"go home\" to return, or ask me about it."
        return AppActions.SCREEN_HELP[vm.tab.value.name] ?: "This is part of Abhify."
    }

    /** Only from recorded project status (never invented). */
    private fun projectStatus(): String {
        val ws = g.db.workspaces().filter { !vm.projects.isArchived(it.id) }.maxByOrNull { vm.projects.meta(it.id).status.updatedAt } ?: return "You don't have any projects yet."
        val st = vm.projects.meta(ws.id).status
        if (st.isEmpty) return "\"${ws.name}\" has no recorded status yet. You can add one on the project page."
        return listOfNotNull("Your last saved update for \"${ws.name}\"" + (if (st.updatedAt > 0) ", ${ai.abhify.android.intel.ProjectBrief.age(System.currentTimeMillis() - st.updatedAt)}" else "") + ".",
            st.current.ifBlank { null }?.let { "Status: $it." }, st.next.firstOrNull()?.let { "Next: $it." },
            st.issues.firstOrNull()?.let { "Open issue: $it." }, if (st.next.isNotEmpty()) "Would you like to continue?" else null).joinToString(" ")
    }

    /** From the same records as Home's Continue working and Suggestions (with the reason each appears). */
    private fun recommendations(): String {
        val cont = vm.companion.continueItems.toList(); val sugg = vm.companion.items.toList()
        if (cont.isEmpty() && sugg.isEmpty()) return "I don't see anything pending in your projects, to-dos, study or AI tasks. You could add a project status or start a study plan."
        val lines = (cont.take(2).map { "${it.title}: ${it.detail}" } + sugg.take(2).map { "${it.title}. ${it.reason}" }).distinct().take(3)
        return "Here's what your records suggest. " + lines.joinToString(" ") { "$it." } + " Should I open one of them?"
    }

    private fun studyProgress(): String {
        val plans = g.db.planStats().filter { it.total > 0 }
        val due = g.db.deckStats().sumOf { it.due }
        val p = vm.progress.value
        if (plans.isEmpty() && due == 0 && p.answered == 0) return "There's no study progress recorded yet. Study, Discover can find you a course."
        return listOfNotNull(plans.filter { it.done < it.total }.maxByOrNull { it.lastActivity }?.let { "Your plan \"${it.topic}\" is at ${it.done} of ${it.total} lessons." },
            if (due > 0) "$due flashcards are due." else null,
            if (p.answered > 0) "Quiz score: ${p.correct} of ${p.answered} correct." else null).joinToString(" ")
    }

    /** Shows and (per settings) speaks an action's real result; the conversation continues afterwards. */
    private fun say(text: String, listenAfter: Boolean = true) {
        actionReply.value = text
        val s = vm.voiceSettings.value
        if (active.value && s.speaks) vm.speak(text) else if (listenAfter) onSpeechFinished()
    }
}
