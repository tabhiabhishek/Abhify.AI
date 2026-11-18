package ai.abhify.android.providers

import ai.abhify.android.cloud.Http
import ai.abhify.android.cloud.HttpResult
import ai.abhify.android.cloud.KeyValue
import ai.abhify.android.cloud.NetworkException
import ai.abhify.android.cloud.SecretBox
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Hosted AI providers the phone calls directly with the user's own (personal) API key.
 * Endpoints, free-model rules and privacy notes are from each provider's official docs (read 2026-11-02);
 * see docs/archive/android-rc/RC3_AUDIT_AND_PLAN.md. All three expose an OpenAI-compatible chat-completions API.
 */
enum class Provider(
    val title: String,
    val chatUrl: String,
    val modelsUrl: String,
    val keyHint: String,
    val getKeyUrl: String,
    val defaultModel: String,
    /** The provider's own billing rule, shown before a key is saved. */
    val costNote: String,
    /** What happens to the text sent, per the provider's terms. Shown before the first request. */
    val privacyNote: String,
    /** Shown when saving: billing depends on the provider account, and no API can prove a key is free. */
    val needsFreeTierConfirmation: Boolean,
    /** Default model choices in order; the first one the key's own model list offers is used. */
    val preferredModels: List<String>,
    /**
     * Default answer length limit (output tokens). Reasoning models (Gemini 3.x Flash, Groq gpt-oss, many OpenRouter
     * free models) spend part of it on hidden thinking, so RC.4's 768 cut answers short. Groq counts this value
     * against the per-minute token limit, so it stays lower there.
     */
    val defaultMaxTokens: Int,
) {
    GEMINI("Google Gemini",
        "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        "https://generativelanguage.googleapis.com/v1beta/openai/models",
        "AIza…", "https://aistudio.google.com/apikey", "gemini-3.8-flash",
        "Free only while the key's Google project has NO billing linked. With billing linked, every request is charged.",
        "Google's free tier states that content you send is used to improve Google's products.",
        needsFreeTierConfirmation = true,
        preferredModels = listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.6-flash", "gemini-3.1-flash-lite"), defaultMaxTokens = 8192),
    GROQ("Groq",
        "https://api.groq.com/openai/v1/chat/completions",
        "https://api.groq.com/openai/v1/models",
        "gsk_…", "https://console.groq.com/keys", "llama-3.3-70b-versatile",
        "Free on Groq's Free plan (rate-limited). Charges start only if the account is upgraded to a paid tier.",
        "Your messages are processed by Groq to answer them.",
        needsFreeTierConfirmation = true,
        preferredModels = listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b", "openai/gpt-oss-20b", "llama-3.1-8b-instant"), defaultMaxTokens = 4096),
    OPENROUTER("OpenRouter",
        "https://openrouter.ai/api/v1/chat/completions",
        "https://openrouter.ai/api/v1/models",
        "sk-or-…", "https://openrouter.ai/keys", "openrouter/free",
        "Only free models (\":free\", zero price) are offered. Free models are limited per day (50, or 1000 after buying credits).",
        "OpenRouter forwards your messages to the provider of the chosen free model, whose terms apply.",
        needsFreeTierConfirmation = false,
        preferredModels = listOf("openrouter/free"), defaultMaxTokens = 4096);

    /** The request field for the answer length limit (Groq's OpenAI-compatible API uses the newer name). */
    val maxTokensField get() = if (this == GROQ) "max_completion_tokens" else "max_tokens"

    val id get() = name.lowercase()
}

data class ModelInfo(val id: String, val free: Boolean, val note: String = "")

/** Pure rules, unit-tested. */
object ProviderRules {
    /** Gemini models listed as "Free of charge" on the free tier (pricing page, 2026-11-02). Others may be charged. */
    val GEMINI_FREE = setOf("gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite")
    private val NOT_CHAT = Regex("whisper|guard|orpheus|tts|embed|imagen|veo|image|audio|live|safeguard|moderation", RegexOption.IGNORE_CASE)

    fun mask(key: String): String {
        val k = key.trim()
        if (k.length <= 8) return "••••"
        val prefix = k.substringBefore('_', "").takeIf { it.isNotEmpty() && it.length <= 6 }?.plus("_")
            ?: k.substringBefore('-', "").takeIf { it.isNotEmpty() && it.length <= 6 }?.plus("-") ?: k.take(4)
        return prefix + "••••••••" + k.takeLast(4)
    }

    /** Parses a provider's model list into chat models, each marked free or "may be charged". */
    fun parseModels(p: Provider, body: String): List<ModelInfo> {
        val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).map { data.getJSONObject(it) }.mapNotNull { m ->
            val id = m.optString("id").removePrefix("models/")
            if (id.isBlank() || NOT_CHAT.containsMatchIn(id)) return@mapNotNull null
            when (p) {
                Provider.GEMINI -> if (!id.startsWith("gemini")) null
                    else ModelInfo(id, id in GEMINI_FREE, if (id in GEMINI_FREE) "free tier" else "not on the free tier: may be charged")
                Provider.GROQ -> if (m.has("active") && !m.optBoolean("active", true)) null
                    else ModelInfo(id, true, "free while your Groq account is on the Free plan")
                Provider.OPENROUTER -> {
                    val pr = m.optJSONObject("pricing")
                    val zero = pr != null && listOf("prompt", "completion").all { k -> pr.optString(k, "x").toDoubleOrNull() == 0.0 }
                    ModelInfo(id, id.endsWith(":free") && zero, if (id.endsWith(":free") && zero) "free" else "paid")
                }
            }
        }.sortedWith(compareByDescending<ModelInfo> { it.free }.thenBy { it.id })
            .let { if (p == Provider.OPENROUTER) listOf(ModelInfo("openrouter/free", true, "Free Models Router")) + it.filter { m -> m.id != "openrouter/free" } else it }
    }

    /** May this model be used without the user's explicit "allow charged models" choice? */
    fun allowed(p: Provider, model: String, allowCharged: Boolean): Boolean = allowCharged || when (p) {
        Provider.GEMINI -> model in GEMINI_FREE
        Provider.GROQ -> true                                 // free plan; billing needs an explicit upgrade on Groq's side
        Provider.OPENROUTER -> model == "openrouter/free" || model.endsWith(":free")
    }

    private val KEYLIKE = Regex("(AIza[0-9A-Za-z_\\-]{10,}|gsk_[A-Za-z0-9]{8,}|sk-[A-Za-z0-9_\\-]{8,}|Bearer\\s+\\S+)")

    /** Removes anything that looks like a credential from provider text before it is shown or stored. */
    fun sanitize(text: String): String = KEYLIKE.replace(text, "[redacted]").take(200)

    /**
     * A provider error → one clear sentence naming the provider. Formats seen live (2026-11-03): Gemini answers an
     * invalid key with HTTP 400 and [{"error":{"message":"Please pass a valid API key"}}]; Groq 401 {"error":{..}};
     * OpenRouter 401 {"error":{"message":..,"code":401}}.
     */
    /** 5.6: how long to rest a provider after HTTP 429 — its Retry-After seconds when given, else 60 s; 5 s to 15 min. */
    fun cooldownMs(retryAfter: String?): Long = ((retryAfter?.trim()?.toLongOrNull() ?: 60L).coerceIn(5L, 900L)) * 1000L

    fun errorMessage(p: Provider, code: Int, body: String, retryAfter: String?): String {
        val msg = runCatching {
            val t = body.trim()
            val o = if (t.startsWith("[")) JSONArray(t).getJSONObject(0) else JSONObject(t)
            o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: o.optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let(::sanitize)
        val badKey = code == 401 || (code == 400 && msg != null && Regex("api.?key", RegexOption.IGNORE_CASE).containsMatchIn(msg))
        val modelProblem = code == 404 || (msg != null && code in 400..404 && Regex("model.*(not|unknown|unsupported|invalid|exist|found)|no endpoints", RegexOption.IGNORE_CASE).containsMatchIn(msg))
        return when {
            badKey -> "${p.title} rejected the API key. Check the key in AI Providers."
            modelProblem -> "The selected ${p.title} model is unavailable. Choose another model in AI Providers."
            code == 402 -> "${p.title}: no credits left on this account (nothing was charged)."
            code == 403 -> "${p.title} refused access (key blocked, restricted or over budget)." + (msg?.let { " $it" } ?: "")
            code == 408 || code == 504 -> "${p.title} timed out. Retry in a moment."
            code == 429 -> "${p.title} rate limit reached" + (retryAfter?.let { " (try again in ${it}s)" } ?: "") + ". Retry later or select another configured provider."
            code in 500..599 -> "${p.title} is unavailable right now (HTTP $code). Retry later."
            code == 400 -> "${p.title} rejected the request" + (msg?.let { ": $it" } ?: ".")
            else -> "${p.title} answered HTTP $code" + (msg?.let { ": $it" } ?: ".")
        }
    }

    /** A transport failure (no HTTP answer) → what the user can do about it. */
    fun networkMessage(p: Provider, e: Throwable): String {
        var c: Throwable? = e
        while (c != null) {
            when (c) {
                is java.net.UnknownHostException -> return "${p.title} connection failed: no internet or the name couldn't be resolved. Check your connection and retry."
                is java.net.SocketTimeoutException -> return "${p.title} connection timed out. Check your internet connection and retry."
                is javax.net.ssl.SSLException -> return "Secure connection to ${p.title} failed. Check the phone's date and time, and the network (some Wi-Fi blocks it)."
                is java.net.ConnectException -> return "${p.title} connection failed. Check your internet connection and retry."
            }
            c = c.cause.takeIf { it !== c }
        }
        return "${p.title} connection failed. Check your internet connection and retry."
    }

    /** One SSE line from a chat-completions stream. */
    sealed class Line {
        /** Answer text; [finish] is set when the same chunk also ends the answer. */
        data class Token(val text: String, val finish: String? = null) : Line()
        /** The provider ended the answer: "stop" (complete), "length" (hit the output limit), "content_filter", … */
        data class Finish(val reason: String) : Line()
        data class Error(val message: String) : Line()
        data object Done : Line()
        data object Skip : Line()
    }

    fun parseLine(line: String): Line {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith(":") || t.startsWith("event:")) return Line.Skip      // OpenRouter keep-alives, event names
        if (!t.startsWith("data:")) return Line.Skip
        val data = t.removePrefix("data:").trim()
        if (data == "[DONE]") return Line.Done
        return runCatching {
            val o = JSONObject(data)
            o.optJSONObject("error")?.let { e -> return Line.Error(sanitize(e.optString("message", "The provider reported an error."))) }   // mid-stream error, HTTP 200
            val c = o.optJSONArray("choices")?.optJSONObject(0) ?: return Line.Skip
            val finish = c.optString("finish_reason").takeIf { it.isNotEmpty() && it != "null" }
            if (finish == "error") return Line.Error("The provider stopped with an error.")
            val delta = c.optJSONObject("delta")
            val text = if (delta == null || delta.isNull("content")) "" else delta.optString("content", "")
            when {
                text.isNotEmpty() -> Line.Token(text, finish)
                finish != null -> Line.Finish(finish)
                else -> Line.Skip
            }
        }.getOrDefault(Line.Skip)
    }

    /** Usage the provider actually reports. Never invented: null = "Usage information unavailable". */
    fun usageFromHeaders(p: Provider, h: Map<String, String>): String? = when (p) {
        Provider.GROQ -> {
            val rem = h["x-ratelimit-remaining-requests"]; val lim = h["x-ratelimit-limit-requests"]
            val tok = h["x-ratelimit-remaining-tokens"]; val tlim = h["x-ratelimit-limit-tokens"]
            if (rem != null && lim != null) "Requests left today: $rem of $lim" + (if (tok != null && tlim != null) " · tokens left this minute: $tok of $tlim" else "") else null
        }
        else -> null
    }

    fun usageFromOpenRouterKey(body: String): String? = runCatching {
        val d = JSONObject(body).getJSONObject("data")
        val f = d.optJSONObject("free_model_daily_requests")
        listOfNotNull(
            f?.let { "Free-model requests today: ${it.optInt("used")} of ${it.optInt("limit")} (${it.optInt("remaining")} left)" },
            "Credits used (all time): " + "%.4f".format(d.optDouble("usage", 0.0)),
            if (d.optBoolean("is_free_tier")) "Free tier account" else null,
        ).joinToString(" · ")
    }.getOrNull()
}

/** Per-provider settings and status (no secrets). */
data class ProviderConfig(
    val provider: Provider, val configured: Boolean, val masked: String?, val enabled: Boolean, val model: String,
    val allowCharged: Boolean, val freeTierConfirmed: Boolean, val lastOkAt: Long?, val lastError: String?, val usage: String?,
    /** Answer length limit in output tokens (provider default unless the user changed it). */
    val maxTokens: Int = provider.defaultMaxTokens,
)

/**
 * Personal provider keys, per device. Each key is sealed with its own Android Keystore AES-256-GCM key
 * (alias abhify_provider_keys_v1, separate from the account token key) and stored in app-private storage
 * that is excluded from backups. Keys are never synced, exported, logged or sent to the Abhify server.
 */
class ProviderStore(private val kv: KeyValue, private val box: SecretBox) {
    private val enc = Base64.getEncoder(); private val dec = Base64.getDecoder()

    fun key(p: Provider): String? = runCatching { kv.get("key_${p.id}")?.let { String(box.open(dec.decode(it)), Charsets.UTF_8) } }.getOrNull()

    fun saveKey(p: Provider, key: String) {
        val k = key.trim()
        require(k.length in 16..512 && k.none { it.isWhitespace() }) { "That doesn't look like a ${p.title} API key." }
        kv.put("key_${p.id}", enc.encodeToString(box.seal(k.toByteArray(Charsets.UTF_8))))
        // saving happens after the cost note was shown: that is the acknowledgement (no separate hidden gate)
        cfg(p) { it.put("masked", ProviderRules.mask(k)).put("enabled", true).put("freeTierConfirmed", true).remove("lastError") }
    }

    fun removeKey(p: Provider) { kv.put("key_${p.id}", null); kv.put("cfg_${p.id}", null) }

    fun resetAll() = Provider.entries.forEach(::removeKey).also { kv.put("priority", null) }

    private fun raw(p: Provider): JSONObject = runCatching { JSONObject(kv.get("cfg_${p.id}") ?: "{}") }.getOrDefault(JSONObject())
    private fun cfg(p: Provider, change: (JSONObject) -> Unit) { val o = raw(p); change(o); kv.put("cfg_${p.id}", o.toString()) }

    fun config(p: Provider): ProviderConfig {
        val o = raw(p)
        return ProviderConfig(p, kv.get("key_${p.id}") != null, o.optString("masked").ifEmpty { null }, o.optBoolean("enabled", true),
            o.optString("model").ifEmpty { p.defaultModel }, o.optBoolean("allowCharged"), o.optBoolean("freeTierConfirmed"),
            o.optLong("lastOk").takeIf { it > 0 }, o.optString("lastError").ifEmpty { null }, o.optString("usage").ifEmpty { null },
            o.optInt("maxTokens", p.defaultMaxTokens).coerceIn(256, 32768))
    }

    fun setMaxTokens(p: Provider, n: Int) = cfg(p) { it.put("maxTokens", n.coerceIn(256, 32768)) }

    fun setEnabled(p: Provider, on: Boolean) = cfg(p) { it.put("enabled", on) }
    fun setModel(p: Provider, model: String) = cfg(p) { it.put("model", model) }
    fun setAllowCharged(p: Provider, on: Boolean) = cfg(p) { it.put("allowCharged", on) }
    fun setFreeTierConfirmed(p: Provider, on: Boolean) = cfg(p) { it.put("freeTierConfirmed", on) }
    fun recordOk(p: Provider, usage: String?, at: Long = System.currentTimeMillis()) = cfg(p) { o -> o.put("lastOk", at).remove("lastError"); usage?.let { o.put("usage", it) } }
    fun recordError(p: Provider, message: String) = cfg(p) { it.put("lastError", message.take(300)) }
    fun cacheModels(p: Provider, models: List<ModelInfo>) = cfg(p) { o ->
        o.put("models", JSONArray(models.map { JSONObject().put("id", it.id).put("free", it.free).put("note", it.note) }))
    }
    fun cachedModels(p: Provider): List<ModelInfo> = raw(p).optJSONArray("models")?.let { a ->
        (0 until a.length()).map { a.getJSONObject(it).let { m -> ModelInfo(m.getString("id"), m.getBoolean("free"), m.optString("note")) } }
    } ?: emptyList()

    /** Auto-mode order. Default: Gemini, Groq, OpenRouter (then shared access and this phone, decided by the router). */
    var priority: List<Provider>
        get() = kv.get("priority")?.split(',')?.mapNotNull { n -> Provider.entries.firstOrNull { it.id == n } }?.takeIf { it.size == Provider.entries.size }
            ?: listOf(Provider.GEMINI, Provider.GROQ, Provider.OPENROUTER)
        set(v) { kv.put("priority", v.joinToString(",") { it.id }) }

    /** Why a provider can't be used right now, or null when it is ready (key saved, enabled, model allowed). */
    fun problem(p: Provider): String? {
        val c = config(p)
        val rest = cooldownLeft(p)
        return when {
            !c.configured -> "no API key saved"
            !c.enabled -> "turned off"
            !ProviderRules.allowed(p, c.model, c.allowCharged) -> "the model ${c.model} may be charged (allow charged models or pick a free one)"
            rest > 0 -> "rate limited, try again in ${(rest + 999) / 1000} s"
            else -> null
        }
    }

    // 5.6 rate-limit cooldown (memory only): after HTTP 429 Auto mode skips the provider until it may be asked again,
    // instead of sending more requests that fail; the existing fallback rules decide what answers meanwhile.
    private val cooldownUntil = java.util.concurrent.ConcurrentHashMap<Provider, Long>()
    fun coolDown(p: Provider, retryAfter: String?, now: Long = System.currentTimeMillis()) { cooldownUntil[p] = now + ProviderRules.cooldownMs(retryAfter) }
    fun cooldownLeft(p: Provider, now: Long = System.currentTimeMillis()): Long = ((cooldownUntil[p] ?: 0L) - now).coerceAtLeast(0L)

    /** Ready to send requests. (RC.3 also required a separate free-tier tick here; that hidden gate made saved keys unusable.) */
    fun usable(p: Provider): Boolean = problem(p) == null

    /** After a successful check: remember the models, keep the current model if still offered, else use the first preferred free one. */
    fun adoptModels(p: Provider, models: List<ModelInfo>) {
        cacheModels(p, models)
        val current = config(p).model
        if (models.any { it.id == current }) return
        val free = models.filter { it.free }.map { it.id }
        (p.preferredModels.firstOrNull { it in free } ?: free.firstOrNull())?.let { setModel(p, it) }
    }

    fun anyConfigured() = Provider.entries.any { config(it).configured }
}

/** Talks to one provider with the user's key. Blocking: call from Dispatchers.IO. */
class ProviderClient(private val http: Http, private val store: ProviderStore) {
    private fun auth(p: Provider, key: String) = buildMap {
        put("Authorization", "Bearer $key")
        if (p == Provider.OPENROUTER) { put("HTTP-Referer", "https://abhify.ai"); put("X-Title", "Abhify") }
    }

    data class Check(val ok: Boolean, val message: String, val models: List<ModelInfo> = emptyList(), val usage: String? = null)

    /** Validates a key without generating anything (no cost): lists models; for OpenRouter also reads key info. */
    fun validate(p: Provider, key: String): Check = try {
        val r = http.request("GET", p.modelsUrl, auth(p, key), null)
        if (r.code != 200) Check(false, ProviderRules.errorMessage(p, r.code, r.body, r.headers["retry-after"]))
        else {
            val models = ProviderRules.parseModels(p, r.body)
            val usage = if (p == Provider.OPENROUTER) {
                val k = http.request("GET", "https://openrouter.ai/api/v1/key", auth(p, key), null)
                if (k.code == 401) return Check(false, ProviderRules.errorMessage(p, 401, k.body, null))
                ProviderRules.usageFromOpenRouterKey(k.body)
            } else null
            if (models.isEmpty()) Check(false, "${p.title} accepted the key but listed no usable chat models.")
            else Check(true, "Key works · ${models.count { it.free }} free models available", models, usage)
        }
    } catch (e: NetworkException) { Check(false, ProviderRules.networkMessage(p, e)) }

    /** Refreshes the model list and usage for a saved key. */
    fun refresh(p: Provider): Check {
        val key = store.key(p) ?: return Check(false, "No key saved for ${p.title}.")
        val c = validate(p, key)
        if (c.ok) { store.adoptModels(p, c.models); store.recordOk(p, c.usage) } else store.recordError(p, c.message)   // failure keeps the last good model
        return c
    }

    /**
     * Streams an answer. [onToken] returns false to stop (cancellation). Returns null on success, or the error.
     * Never sends a "models" fallback list (OpenRouter would bill fallbacks to paid models).
     */
    fun chat(p: Provider, messages: List<Pair<String, String>>, maxTokens: Int, temperature: Double, onToken: (String) -> Boolean): String? =
        chatFull(p, messages, maxTokens, temperature, onToken).error

    /**
     * How an answer ended. [finish]: "stop" = complete, "length" = cut off by the output limit (Continue is offered),
     * "cancelled" = the user stopped it, "interrupted" = the connection dropped after some text, null = unknown
     * (the stream ended without a finish reason; treated as complete when text arrived and [DONE] was seen).
     */
    data class Result(val error: String?, val finish: String?, val tokens: Int)

    fun chatFull(p: Provider, messages: List<Pair<String, String>>, maxTokens: Int, temperature: Double, onToken: (String) -> Boolean): Result {
        fun fail(m: String) = Result(m, null, 0)
        val key = store.key(p) ?: return fail("No API key saved for ${p.title}.")
        val c = store.config(p)
        if (!c.enabled) return fail("${p.title} is turned off in AI Providers.")
        if (!ProviderRules.allowed(p, c.model, c.allowCharged)) return fail("${c.model} may be charged. Pick a free model, or allow charged models in ${p.title} settings.")
        val body = JSONObject().put("model", c.model).put("stream", true).put("temperature", temperature)
            .put(p.maxTokensField, maxTokens)
            .put("messages", JSONArray(messages.map { (role, content) -> JSONObject().put("role", role).put("content", content) }))
        var error: String? = null
        var done = false
        var tokens = 0
        var cancelled = false
        var finish: String? = null
        val r = try {
            http.streamFull(p.chatUrl, auth(p, key), body.toString()) { line ->
                when (val l = ProviderRules.parseLine(line)) {
                    is ProviderRules.Line.Token -> { tokens++; l.finish?.let { finish = it }; onToken(l.text).also { if (!it) cancelled = true } }
                    is ProviderRules.Line.Finish -> { finish = l.reason; true }               // [DONE] (or the stream end) follows
                    is ProviderRules.Line.Error -> { error = "${p.title}: ${l.message}"; false }
                    ProviderRules.Line.Done -> { done = true; false }
                    ProviderRules.Line.Skip -> true
                }
            }
        } catch (e: NetworkException) {
            if (cancelled) return Result(null, "cancelled", tokens)
            val m = if (tokens > 0) "The connection to ${p.title} was interrupted during the answer." else ProviderRules.networkMessage(p, e)
            store.recordError(p, m); return Result(m, if (tokens > 0) "interrupted" else null, tokens)
        }
        if (r.code != 200) {
            val m = ProviderRules.errorMessage(p, r.code, r.errorBody, r.headers["retry-after"])
            if (r.code == 429) store.coolDown(p, r.headers["retry-after"])
            store.recordError(p, m); return Result(m, null, 0)
        }
        error?.let { store.recordError(p, it); return Result(it, if (tokens > 0) "interrupted" else null, tokens) }
        if (cancelled) return Result(null, "cancelled", tokens)
        if (tokens == 0) {
            val m = if (finish == "length") "${p.title} used the whole answer length limit before writing anything (the model's hidden reasoning). Raise the limit in AI Providers or choose another model."
                else "${p.title} returned an empty answer. Retry, or choose another model."
            store.recordError(p, m); return Result(m, finish, 0)
        }
        store.recordOk(p, ProviderRules.usageFromHeaders(p, r.headers))
        // a stream that stopped without [DONE] and without a finish reason was cut off
        return Result(null, finish ?: if (done) "stop" else "interrupted", tokens)
    }
}
