package io.github.esko997.sortie.data.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Installed apps as first-class launchables ("app:<package>" ids), so favourites can hold Chrome or Shizuku next to games. */
data class AppEntry(val pkg: String, val label: String, val component: ComponentName) {
    val id: String get() = "${LibraryItem.APP_PREFIX}$pkg"
}

/** Launcher-visible apps; [apps] is shared by the Apps screen, favourites and smart folders and refreshed on activity start. */
class AppsRepository(private val context: Context) {
    private val pm: PackageManager get() = context.packageManager
    private val iconCache = HashMap<String, Drawable>()

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

    suspend fun refresh() {
        _apps.value = withContext(Dispatchers.IO) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .filter { it.activityInfo.packageName != context.packageName }
                .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString(), ComponentName(it.activityInfo.packageName, it.activityInfo.name)) }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }
    }

    fun icon(pkg: String): Drawable? = iconCache.getOrPut(pkg) { runCatching { pm.getApplicationIcon(pkg) }.getOrNull() ?: return null }

    fun launchIntent(app: AppEntry): Intent =
        (pm.getLaunchIntentForPackage(app.pkg) ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.component))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}
