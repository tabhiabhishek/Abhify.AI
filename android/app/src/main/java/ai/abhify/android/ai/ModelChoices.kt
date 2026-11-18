package ai.abhify.android.ai

import ai.abhify.android.model.CatalogModel
import ai.abhify.android.providers.Provider

/**
 * 4.5 chat model picker: the list of models a chat can use, built only from what really exists on this phone:
 * the model catalog (assets/model_catalog.json), the models installed in ModelStore, and the online providers the user
 * configured with their own key (plus the Abhify server when signed in). Nothing is invented: a catalog model that is not
 * installed is listed as "not installed" and is never downloaded from here; online entries appear only when online AI is
 * allowed in Privacy.
 *
 * Keys: "auto", "device:<catalog id>", "online:<provider id>", "online:shared".
 */
object ModelChoices {
    enum class Kind { AUTO, DEVICE, ONLINE }
    enum class State { ACTIVE, AVAILABLE, NOT_INSTALLED, UNAVAILABLE }

    data class Choice(val key: String, val kind: Kind, val title: String, val detail: String, val state: State, val reason: String? = null) {
        val selectable get() = state == State.ACTIVE || state == State.AVAILABLE
    }

    /** One configured provider as the picker needs it: its chosen model and why it cannot be used right now (null = usable). */
    data class ProviderInfo(val provider: Provider, val model: String, val configured: Boolean, val problem: String?)

    data class Inputs(
        val mode: AiMode,
        val catalog: List<CatalogModel>,
        val installedIds: Set<String>,
        val activeDeviceId: String?,
        val onlineAllowed: Boolean,
        val providers: List<ProviderInfo>,
        val onlineTarget: String?,          // "shared", a provider id, or null
        val signedIn: Boolean,
    )

    fun deviceKey(id: String) = "device:$id"
    fun onlineKey(target: String) = "online:$target"

    /** The key of the entry that answers the next message (what the chat chip shows as selected). */
    fun selectedKey(i: Inputs): String? = when (i.mode) {
        AiMode.AUTO -> "auto"
        AiMode.DEVICE -> i.activeDeviceId?.let(::deviceKey)
        AiMode.ONLINE -> i.onlineTarget?.let(::onlineKey)
    }

    fun build(i: Inputs): List<Choice> {
        val sel = selectedKey(i)
        fun st(key: String, ok: Boolean) = if (!ok) State.UNAVAILABLE else if (key == sel) State.ACTIVE else State.AVAILABLE
        val out = mutableListOf<Choice>()
        out += Choice("auto", Kind.AUTO, "Auto",
            if (i.onlineAllowed) "Your selected online provider when available, otherwise this phone" else "This phone (online AI is off in Privacy)",
            if (sel == "auto") State.ACTIVE else State.AVAILABLE)
        i.catalog.forEach { m ->
            val key = deviceKey(m.id)
            val installed = m.id in i.installedIds
            out += if (installed) Choice(key, Kind.DEVICE, m.name, "On this phone · ${m.parameters} · ${m.quantization} · offline", st(key, true))
            else Choice(key, Kind.DEVICE, m.name, "On this phone · ${m.sizeMb} MB download", State.NOT_INSTALLED, "Not installed. Download it in AI models.")
        }
        if (i.onlineAllowed) {
            i.providers.filter { it.configured }.forEach { p ->
                val key = onlineKey(p.provider.id)
                out += Choice(key, Kind.ONLINE, "${p.provider.title} · ${p.model}", "Online · your own key · needs internet", st(key, p.problem == null), p.problem)
            }
            if (i.signedIn) out += Choice(onlineKey("shared"), Kind.ONLINE, "Abhify server", "Online · shared access · needs internet", st(onlineKey("shared"), true))
        }
        return out
    }

    /** Short label for the chat chip: the model that answers next, or why none is ready. */
    fun chipLabel(i: Inputs, choices: List<Choice> = build(i)): String {
        val device = i.activeDeviceId?.let { id -> i.catalog.firstOrNull { it.id == id }?.name ?: id }
        return when (i.mode) {
            AiMode.DEVICE -> device ?: "No phone model"
            AiMode.ONLINE -> choices.firstOrNull { it.key == selectedKey(i) }?.title ?: "No online provider"
            AiMode.AUTO -> "Auto"
        }
    }
}
