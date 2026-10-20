package com.stegpony.android.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.stegpony.android.MainActivity
import com.stegpony.android.R

/**
 * How StegPony shows in the launcher and in share sheets. Each choice is an
 * activity-alias in the manifest; exactly one is enabled. Plain names and
 * plain Material symbols, never a real app's name or icon.
 */
enum class Disguise(
    private val alias: String,
    @StringRes val label: Int,
    @DrawableRes val foreground: Int,
    @ColorRes val background: Int,
) {
    None("LauncherDefault", R.string.app_name, R.drawable.ic_launcher_foreground, R.color.ic_launcher_background),
    PhotoTools(
        "LauncherPhotoTools",
        R.string.disguise_photo_tools,
        R.drawable.ic_disguise_photo_tools_foreground,
        R.color.disguise_photo_tools_background,
    ),
    Notes("LauncherNotes", R.string.disguise_notes, R.drawable.ic_disguise_notes_foreground, R.color.disguise_notes_background),
    Utilities(
        "LauncherUtilities",
        R.string.disguise_utilities,
        R.drawable.ic_disguise_utilities_foreground,
        R.color.disguise_utilities_background,
    ),
    ;

    fun component(context: Context): ComponentName =
        ComponentName(context.packageName, "${MainActivity::class.java.`package`!!.name}.$alias")

    companion object {
        /** The enabled alias; the manifest default (None) when nothing was changed. */
        fun current(context: Context): Disguise {
            val pm = context.packageManager
            return entries.firstOrNull { d ->
                when (pm.getComponentEnabledSetting(d.component(context))) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> d == None
                    else -> false
                }
            } ?: None
        }

        /**
         * Switches to [target]: enables it first, then disables the rest, so
         * there is never a moment with no way into the app. DONT_KILL_APP keeps
         * StegPony running; the launcher picks the change up on its own, which
         * on some launchers removes the home-screen shortcut.
         */
        fun apply(context: Context, target: Disguise) {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(
                target.component(context),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            entries.filter { it != target }.forEach { other ->
                pm.setComponentEnabledSetting(
                    other.component(context),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }
}
