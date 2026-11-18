package ai.abhify.android.drive

/**
 * Google Drive account rules (v4.2). Each account has its own Abhify.AI folder ids and its own waiting uploads; nothing
 * of one account is ever used for another. Pure, so the rules are tested on the JVM.
 */
object DriveAccounts {
    /** A queued upload may go to [current] only if it belongs to it (or predates account tracking: null = adopted). */
    fun mayUpload(rowAccount: String?, current: String?): Boolean = current != null && (rowAccount == null || rowAccount == current)

    /** The preference key holding [account]'s folder ids (never shared between accounts). */
    fun folderKey(account: String) = "drive_folders:$account"

    /** Uploads waiting for an account other than [current] (shown, kept, never sent elsewhere). */
    fun waitingForOthers(rows: List<Pair<String?, String>>, current: String?): Int = rows.count { (acct, status) ->
        status == DriveStatus.PENDING_UPLOAD && acct != null && acct != current }

    /** What the user sees for the connected account: email when Google provides it, else the display name. */
    fun label(email: String?, name: String?): String = email ?: name ?: "your Google account"
}

/** Errors from Google sign-in, by status code (com.google.android.gms ApiException), in plain words with what to do. */
object DriveErrors {
    enum class Kind { CANCELLED, NOT_CONFIGURED, NETWORK, DENIED, PLAY_SERVICES, OTHER }
    fun kind(code: Int?): Kind = when (code) {
        12501, 16 -> Kind.CANCELLED                       // user closed the account picker / consent (SIGN_IN_CANCELLED, CANCELED)
        10 -> Kind.NOT_CONFIGURED                         // DEVELOPER_ERROR: no OAuth client for this package + signing key
        7, 8 -> Kind.NETWORK                              // NETWORK_ERROR, INTERNAL_ERROR (usually connectivity)
        4, 13 -> Kind.DENIED                              // SIGN_IN_REQUIRED, ERROR (access not granted)
        1, 2, 3, 9, 18 -> Kind.PLAY_SERVICES             // service missing / update required / disabled / invalid / updating
        else -> Kind.OTHER
    }
    fun message(code: Int?): String = when (kind(code)) {
        Kind.CANCELLED -> "Google sign-in was cancelled. Nothing changed; tap Connect to try again."
        Kind.NOT_CONFIGURED -> "Google Drive isn't set up for this app build (developer configuration: the OAuth client must match this package and signing key). " +
            "This isn't something you need to fix on your phone. Diagnostic: DEVELOPER_ERROR (10)."
        Kind.NETWORK -> "No connection to Google. Check the internet and try again."
        Kind.DENIED -> "Google Drive access wasn't granted. Tap Connect and choose Allow to use Drive (it stays optional)."
        Kind.PLAY_SERVICES -> "Google Play services need attention on this phone (missing, disabled or updating). Update them in the Play Store, then retry."
        Kind.OTHER -> "Google sign-in failed" + (code?.let { " (code $it)" } ?: "") + ". Try again; if it keeps failing, see Troubleshooting."
    }
}

/**
 * Optional move of files from the 3.4–4.1 sub-folders into the v4.2 layout. Only files Abhify created (the only ones
 * the drive.file scope can see) are proposed; the user picks which ones; moving keeps file ids; nothing is deleted.
 */
object DriveMigration {
    data class Move(val item: DriveApi.Item, val fromFolder: String, val fromId: String, val toFolder: String)

    /** [legacy]: legacy folder name → (its id, its files). Folders are never proposed; unknown folders are left alone. */
    fun plan(legacy: Map<String, Pair<String, List<DriveApi.Item>>>): List<Move> = legacy.flatMap { (name, v) ->
        val to = DriveApi.LEGACY[name] ?: return@flatMap emptyList()
        v.second.filter { !it.mime.endsWith(".folder") }.map { Move(it, name, v.first, to) }
    }

    data class Result(val moved: List<Move>, val failed: List<Pair<Move, String>>) {
        val summary get() = "${moved.size} moved" + if (failed.isEmpty()) "" else ", ${failed.size} not moved (left where they were)"
    }
}
