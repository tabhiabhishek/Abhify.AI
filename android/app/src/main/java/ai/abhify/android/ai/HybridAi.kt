package ai.abhify.android.ai

import ai.abhify.android.chat.PromptBuilder
import ai.abhify.android.cloud.ApiException
import ai.abhify.android.cloud.CloudClient
import ai.abhify.android.cloud.NetworkException
import ai.abhify.android.llm.LlmEngine
import ai.abhify.android.providers.Provider
import ai.abhify.android.providers.ProviderClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class AiMode(val label: String) { AUTO("Auto"), ONLINE("Online AI"), DEVICE("On this phone") }

/** Who actually wrote an answer. Shown under every reply and stored with it. */
data class Source(val kind: Kind, val model: String) {
    enum class Kind(val label: String) { DEVICE("On this phone"), SERVER("Abhify server"), GEMINI("Gemini"), GROQ("Groq"), OPENROUTER("OpenRouter") }
    val cloud get() = kind != Kind.DEVICE
    val label get() = (if (cloud) "☁ " else "📱 ") + kind.label + " · " + model
    fun encode() = kind.name.lowercase() + ":" + model
    companion object {
        fun of(p: Provider, model: String) = Source(Kind.valueOf(p.name), model)
        fun decode(s: String?): Source? {
            if (s == null || ':' !in s) return null
            val k = s.substringBefore(':'); val m = s.substringAfter(':')
            return when (k) { "cloud" -> Source(Kind.SERVER, m); "device" -> Source(Kind.DEVICE, m)      // rc.2 labels
                else -> Kind.entries.firstOrNull { it.name.lowercase() == k }?.let { Source(it, m) } }
        }
    }
}

/** One place an answer can come from. */
sealed class Route {
    data object Device : Route()
    data object Server : Route()                                 // your Abhify server: its own model or admin-shared providers
    data class Personal(val provider: Provider) : Route()        // this phone's own API key
    data class Unavailable(val reason: String) : Route()
}

/** What the user picked as the single target for Online mode. */
sealed class OnlineTarget { data class Personal(val provider: Provider) : OnlineTarget(); data object Shared : OnlineTarget() }

data class RouteInputs(
    val mode: AiMode, val online: Boolean, val onlineAllowed: Boolean, val signedIn: Boolean, val modelInstalled: Boolean,
    /** Personal providers that are ready (key, enabled, free tier confirmed, allowed model), in the user's priority order. */
    val usableProviders: List<Provider>,
    val onlineTarget: OnlineTarget?,
    /** Auto: may the phone's model answer when no online route works? (user setting) */
    val fallbackToDevice: Boolean,
    /** Auto: may another configured provider be tried when the first fails before answering? (user setting, off by default) */
    val fallbackProviders: Boolean = false,
    /** Why each personal provider isn't ready (no key, off, model may be charged), for honest messages. */
    val problems: Map<Provider, String> = emptyMap(),
)

/** Pure routing rules (unit-tested): an ordered list of attempts, or one Unavailable with the reason. */
object RoutePolicy {
    fun plan(i: RouteInputs): List<Route> = when (i.mode) {
        AiMode.DEVICE -> listOf(if (i.modelInstalled) Route.Device else Route.Unavailable("No AI model on this phone yet. Download one in AI models."))
        // Online: exactly the provider the user selected. Personal keys never need an Abhify account or server.
        AiMode.ONLINE -> listOf(when (val t = i.onlineTarget) {
            null -> Route.Unavailable(NO_PROVIDER)
            is OnlineTarget.Personal -> when {
                t.provider !in i.usableProviders -> Route.Unavailable("${t.provider.title} isn't ready: ${i.problems[t.provider] ?: "check it in AI Providers"}. Open AI Providers.")
                !i.onlineAllowed -> Route.Unavailable("Online AI is turned off in Privacy. Allow it there to use ${t.provider.title}, or switch to On this phone.")
                !i.online -> Route.Unavailable("You're offline. ${t.provider.title} needs internet; switch to On this phone.")
                else -> Route.Personal(t.provider)
            }
            OnlineTarget.Shared -> when {
                !i.signedIn -> Route.Unavailable("Shared access needs an Abhify account. Select one of your own providers instead, or sign in.")
                !i.onlineAllowed -> Route.Unavailable("Online AI is turned off in Privacy.")
                !i.online -> Route.Unavailable("You're offline. Switch to On this phone.")
                else -> Route.Server
            }
        })
        AiMode.AUTO -> buildList {
            if (i.online && i.onlineAllowed) {
                val providers = i.usableProviders.let { if (i.fallbackProviders) it else it.take(1) }
                providers.forEach { add(Route.Personal(it)) }
                if (i.signedIn && (providers.isEmpty() || i.fallbackProviders)) add(Route.Server)
            }
            if (i.modelInstalled && (i.fallbackToDevice || isEmpty())) add(Route.Device)
            if (isEmpty()) add(Route.Unavailable(when {
                !i.modelInstalled && !i.online -> "You're offline and no AI model is on this phone yet."
                !i.onlineAllowed -> "Download an AI model (AI models), or allow online AI in Privacy and add an API key."
                else -> "Add an API key in AI providers, or download an AI model for this phone."
            }))
        }
    }

    /** Move to the next attempt only if nothing has been written yet (never mix two models in one answer). */
    fun mayTryNext(tokensSoFar: Int) = tokensSoFar == 0

    const val NO_PROVIDER = "No online provider configured. Open AI Providers to add or select a provider."
}

sealed class ChatEvent {
    /** A route is being tried (so even a stopped or failed answer knows who was writing it). */
    data class Attempt(val source: Source) : ChatEvent()
    data class Token(val text: String) : ChatEvent()
    data class Notice(val text: String) : ChatEvent()
    /** [finish]: "stop" complete · "length" cut off by the output limit · "interrupted" stream ended early · null unknown. */
    data class Done(val source: Source, val tokensPerSecond: Double?, val firstTokenMs: Double?, val finish: String? = "stop") : ChatEvent()
    data class Failed(val message: String, val source: Source?) : ChatEvent()
}

/** One NDJSON line from the Abhify server's gateway. */
sealed class CloudLine {
    data class Token(val text: String) : CloudLine()
    data class Done(val model: String, val provider: String = "") : CloudLine()
    data class Error(val message: String) : CloudLine()
    companion object {
        fun parse(line: String): CloudLine? = runCatching {
            val o = JSONObject(line)
            when (o.getString("type")) {
                "token" -> Token(o.getString("text"))
                "done" -> Done(o.optString("model", "online model"), o.optString("provider"))
                "error" -> Error(o.optString("message", "The online AI failed."))
                else -> null
            }
        }.getOrNull()
    }
}

/**
 * Generates an answer where the routing rules say, and labels it truthfully. [local] is the full prompt for
 * the on-phone model; [online] is what may leave the phone (memories only if the user allowed it).
 */
class HybridAi(
    private val cloud: CloudClient,
    private val providers: ProviderClient,
    private val engine: LlmEngine,
    private val ensureDevice: suspend () -> String?,
    private val deviceModelName: () -> String?,
    private val providerModel: (Provider) -> String,
    private val providerMaxTokens: (Provider) -> Int = { it.defaultMaxTokens },
    /** Output limit for the phone's model (also capped by its context window in native code). */
    private val deviceMaxTokens: Int = DEVICE_MAX_TOKENS,
    /** v4.4 "Creativity" for the phone's model (0.3 = the behaviour before 4.4). */
    private val deviceTemperature: () -> Float = { 0.3f },
) {
    companion object { const val DEVICE_MAX_TOKENS = 1024 }

    /** 6.0: per-request settings from the interaction mode (Brain.profile) for the phone's model; null = the user's settings. */
    data class Tuning(val maxTokens: Int? = null, val temperature: Float? = null)

    private val nextTuning = java.util.concurrent.atomic.AtomicReference<Tuning?>(null)
    /** Settings for the next request only (the chat sets them right before calling [generate]; routing is not affected). */
    fun tuneNext(t: Tuning?) = nextTuning.set(t)

    fun generate(local: List<PromptBuilder.Msg>, online: List<PromptBuilder.Msg>, inputs: RouteInputs, tuning: Tuning? = null): Flow<ChatEvent> =
        run(RoutePolicy.plan(inputs), local, online, tuning ?: nextTuning.getAndSet(null))

    /** Exactly one route, no fallback: used by Continue, which must use the same source as the answer it extends. */
    fun generateOn(route: Route, local: List<PromptBuilder.Msg>, online: List<PromptBuilder.Msg>, tuning: Tuning? = null): Flow<ChatEvent> =
        run(listOf(route), local, online, tuning ?: nextTuning.getAndSet(null))

    private fun run(plan: List<Route>, local: List<PromptBuilder.Msg>, online: List<PromptBuilder.Msg>, tuning: Tuning? = null): Flow<ChatEvent> = flow {
        (plan.singleOrNull() as? Route.Unavailable)?.let { emit(ChatEvent.Failed(it.reason, null)); return@flow }
        val failures = mutableListOf<String>()
        for ((n, route) in plan.withIndex()) {
            var tokens = 0
            var failure: String? = null
            var source: Source? = null
            val t0 = System.currentTimeMillis(); var firstAt: Long? = null       // 5.6 RouteStats: timings and counts only
            when (route) {
                Route.Device -> Source(Source.Kind.DEVICE, deviceModelName() ?: "on-device model")
                Route.Server -> Source(Source.Kind.SERVER, "server")
                is Route.Personal -> Source.of(route.provider, providerModel(route.provider))
                is Route.Unavailable -> null
            }?.let { emit(ChatEvent.Attempt(it)) }
            val events = when (route) {
                Route.Device -> device(local, tuning)
                Route.Server -> server(online)
                is Route.Personal -> personal(route.provider, online)
                is Route.Unavailable -> continue
            }
            events.collect { e ->
                when (e) {
                    is ChatEvent.Token -> { if (tokens == 0) firstAt = System.currentTimeMillis() - t0; tokens++; emit(e) }
                    is ChatEvent.Done -> { source = e.source; emit(e) }
                    is ChatEvent.Failed -> { failure = e.message; source = e.source }
                    is ChatEvent.Notice, is ChatEvent.Attempt -> emit(e)
                }
            }
            source?.let { RouteStats.record(it.kind, failure == null, firstAt, System.currentTimeMillis() - t0, tokens, failures.isNotEmpty()) }
            if (failure == null) return@flow
            val label = source?.let { it.kind.label } ?: "online AI"
            failures += "$label: $failure"
            val next = plan.drop(n + 1).firstOrNull { it !is Route.Unavailable }
            if (next == null || !RoutePolicy.mayTryNext(tokens)) { emit(ChatEvent.Failed(failure!!, source)); return@flow }
            emit(ChatEvent.Notice("$label unavailable ($failure). Trying ${describe(next)}…"))
        }
    }

    private fun describe(r: Route) = when (r) { Route.Device -> "this phone's AI"; Route.Server -> "your Abhify server"; is Route.Personal -> r.provider.title; is Route.Unavailable -> "" }

    private fun device(messages: List<PromptBuilder.Msg>, tuning: Tuning? = null): Flow<ChatEvent> = flow {
        val name = deviceModelName() ?: "on-device model"
        ensureDevice()?.let { emit(ChatEvent.Failed(it, Source(Source.Kind.DEVICE, name))); return@flow }
        // 6.0: a mode may shorten the answer (Quick) or set its creativity; never above the phone's output limit
        val temperature = deviceTemperature()                                  // the user's Creativity setting (4.4), unless the mode sets one
        engine.generate(messages, maxTokens = tuning?.maxTokens?.coerceIn(64, deviceMaxTokens) ?: deviceMaxTokens,
            temperature = tuning?.temperature ?: temperature).collect { e ->
            when (e) {
                is LlmEngine.Event.Token -> emit(ChatEvent.Token(e.text))
                is LlmEngine.Event.Finished -> emit(ChatEvent.Done(Source(Source.Kind.DEVICE, name), e.stats.tokensPerSecond, e.stats.firstTokenMs,
                    when (e.stats.stopped) { "end" -> "stop"; "length" -> "length"; "cancelled" -> "cancelled"; else -> "interrupted" }))
                is LlmEngine.Event.Failed -> emit(ChatEvent.Failed(e.message, Source(Source.Kind.DEVICE, name)))
            }
        }
    }

    /** The user's own key, straight to the provider (the key never goes to the Abhify server). */
    // trySendBlocking: a fast stream (Groq) must wait for the UI, not fail. RC.4's trySend failed when the buffer was full,
    // which the client took as "user stopped" and the answer ended early, labelled complete.
    private fun personal(p: Provider, messages: List<PromptBuilder.Msg>): Flow<ChatEvent> = callbackFlow {
        val model = providerModel(p)
        val job = launch(Dispatchers.IO) {
            val r = providers.chatFull(p, messages.map { it.role to it.content }, providerMaxTokens(p), 0.3) { t -> trySendBlocking(ChatEvent.Token(t)).isSuccess }
            if (r.error == null) trySendBlocking(ChatEvent.Done(Source.of(p, model), null, null, r.finish)) else trySendBlocking(ChatEvent.Failed(r.error, Source.of(p, model)))
            close()
        }
        awaitClose { job.cancel() }
    }.flowOn(Dispatchers.IO)

    /** The Abhify server's gateway (its own model or administrator-shared providers; keys stay on the server). */
    private fun server(messages: List<PromptBuilder.Msg>): Flow<ChatEvent> = callbackFlow {
        val body = JSONObject().put("max_tokens", 4096).put("temperature", 0.3)
            .put("messages", JSONArray(messages.map { JSONObject().put("role", it.role).put("content", it.content) }))
        val job = launch(Dispatchers.IO) {
            var ended = false
            try {
                val code = cloud.stream("/api/v1/ai/chat", body) { line ->
                    when (val ev = CloudLine.parse(line)) {
                        is CloudLine.Token -> trySendBlocking(ChatEvent.Token(ev.text))
                        is CloudLine.Done -> { ended = true; trySendBlocking(ChatEvent.Done(Source(Source.Kind.SERVER, ev.model), null, null)) }
                        is CloudLine.Error -> { ended = true; trySendBlocking(ChatEvent.Failed(ev.message, Source(Source.Kind.SERVER, "server"))) }
                        null -> {}
                    }
                    !isClosedForSend
                }
                if (code != 200) trySendBlocking(ChatEvent.Failed(if (code == 429) "too many requests, try again in a minute" else "server answered HTTP $code", Source(Source.Kind.SERVER, "server")))
                else if (!ended) trySendBlocking(ChatEvent.Failed("the connection ended early", Source(Source.Kind.SERVER, "server")))
            } catch (e: ApiException) { trySendBlocking(ChatEvent.Failed(e.message ?: "not signed in", Source(Source.Kind.SERVER, "server"))) }
            catch (e: NetworkException) { trySendBlocking(ChatEvent.Failed("no connection to your Abhify server", Source(Source.Kind.SERVER, "server"))) }
            close()
        }
        awaitClose { job.cancel() }
    }.flowOn(Dispatchers.IO)
}
