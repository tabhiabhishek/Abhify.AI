package ai.abhify.android.chat

/**
 * Database v16 (Abhify 4.2, Google Drive accounts). Additive only: each Drive upload/queue row remembers which Google
 * account it belongs to (Drive permission id), so a waiting upload is never sent to a different account after a switch.
 * Old rows keep NULL (= the account that was connected when they were made; adopted by the first account that connects).
 */
object SchemaV16 {
    val STATEMENTS = listOf(
        "ALTER TABLE drive_files ADD COLUMN account TEXT",
    )

    fun apply(exec: (String) -> Unit) = STATEMENTS.forEach(exec)
}
