package ai.abhify.android.settings

/**
 * 7.2.1 privacy defaults (owner decision D5). Pure, so the upgrade rule is unit-tested.
 *
 * online_history: whether recent chat turns go to an online provider (only when online AI is used at all).
 * New installs: off. Existing installs keep their behaviour: an install that never changed it was effectively ON (the old
 * default), so "on" is written once for it; a stored choice is never touched.
 */
object PrivacyDefaults {
    const val ONLINE_HISTORY = "online_history"
    const val ONLINE_HISTORY_NEW_INSTALL = false

    /** The value to write once at startup, or null to leave the preferences alone. */
    fun onlineHistoryToWrite(hasStoredValue: Boolean, existingInstall: Boolean): Boolean? =
        if (hasStoredValue || !existingInstall) null else true
}
