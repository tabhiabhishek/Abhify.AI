package ai.abhify.android.ui

import ai.abhify.android.R
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 4.5 icon system: one set of Material outlined icons (res/drawable/ic_*, Apache-2.0) for navigation, rows, chips and
 * empty states. Screens still name an icon by the glyph they used before (e.g. NavRow("🔑", …)), so each call site keeps
 * its meaning; this table turns the glyph into the vector icon. Unknown glyphs fall back to the glyph itself.
 */
object AppIcons {
    val byGlyph: Map<String, Int> = mapOf(
        "🔑" to R.drawable.ic_vpn_key, "📦" to R.drawable.ic_inventory_2, "🧠" to R.drawable.ic_psychology, "🕘" to R.drawable.ic_history,
        "🔒" to R.drawable.ic_lock, "🔄" to R.drawable.ic_sync, "💾" to R.drawable.ic_save, "👤" to R.drawable.ic_account_circle,
        "⚙" to R.drawable.ic_settings, "☁" to R.drawable.ic_cloud_queue, "ⓘ" to R.drawable.ic_info, "🧭" to R.drawable.ic_explore,
        "🗄" to R.drawable.ic_storage, "🕶" to R.drawable.ic_visibility_off, "🔎" to R.drawable.ic_search, "🔍" to R.drawable.ic_search,
        "🔌" to R.drawable.ic_power, "📚" to R.drawable.ic_menu_book, "📊" to R.drawable.ic_bar_chart, "📄" to R.drawable.ic_description,
        "＋" to R.drawable.ic_add, "📋" to R.drawable.ic_assignment, "💼" to R.drawable.ic_work_outline, "💬" to R.drawable.ic_chat,
        "🗓" to R.drawable.ic_event, "🃏" to R.drawable.ic_style, "🗂" to R.drawable.ic_folder_open, "📝" to R.drawable.ic_edit_note,
        "🔗" to R.drawable.ic_link, "📱" to R.drawable.ic_smartphone, "🎓" to R.drawable.ic_school, "💡" to R.drawable.ic_lightbulb,
        "✨" to R.drawable.ic_auto_awesome, "📁" to R.drawable.ic_folder, "✅" to R.drawable.ic_task_alt, "📈" to R.drawable.ic_trending_up,
        "🎛" to R.drawable.ic_tune, "🖥" to R.drawable.ic_dns, "🛡" to R.drawable.ic_privacy_tip, "🔖" to R.drawable.ic_bookmark_border,
        "♻" to R.drawable.ic_settings_backup_restore, "🧩" to R.drawable.ic_memory, "🎤" to R.drawable.ic_mic, "📰" to R.drawable.ic_menu_book,
        // 4.5.1 page kit
        "📕" to R.drawable.ic_picture_as_pdf, "🖼" to R.drawable.ic_image, "📘" to R.drawable.ic_description, "✏" to R.drawable.ic_edit,
        "🗑" to R.drawable.ic_delete_outline, "🔀" to R.drawable.ic_compare_arrows, "👁" to R.drawable.ic_visibility, "❔" to R.drawable.ic_help_outline,
        "▶" to R.drawable.ic_play_arrow, "⟳" to R.drawable.ic_refresh, "⏱" to R.drawable.ic_timer, "📤" to R.drawable.ic_upload_file,
        "🚩" to R.drawable.ic_flag, "🏠" to R.drawable.ic_home, "⭐" to R.drawable.ic_bookmark_border,
    )
    fun of(glyph: String): Int? = byGlyph[glyph.removeSuffix("️")]
}

/** A decorative icon for [glyph] (vector when mapped, otherwise the glyph), [size] dp, tinted with [tint]. */
@Composable
fun GlyphIcon(glyph: String, size: Int = 24, tint: Color = LocalContentColor.current, modifier: Modifier = Modifier) {
    val res = AppIcons.of(glyph)
    if (res != null) Icon(painterResource(res), contentDescription = null, tint = tint, modifier = modifier.size(size.dp))
    else Text(glyph, fontSize = (size * 0.75f).sp, modifier = modifier)
}
