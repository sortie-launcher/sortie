package io.github.esko997.sortie.data.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A launcher shortcut another app published (pinned through Sortie, dynamic, or declared in its manifest), e.g. one DroidDeck
 * game. [pkg] is the publishing package; [manifestOnly] marks static manifest shortcuts the user never pinned (Chrome's "New tab").
 */
data class ShortcutEntry(val pkg: String, val shortcutId: String, val label: String, val manifestOnly: Boolean) {
    val id: String get() = "${LibraryItem.SHORTCUT_PREFIX}$pkg/$shortcutId"
}

/** An activity that answers `ACTION_CREATE_SHORTCUT`, i.e. an app that can hand Sortie a shortcut on request. */
data class ShortcutCreator(val label: String, val component: ComponentName)

/**
 * Launcher-shortcut host. Works only while Sortie is the default HOME app (Android grants the shortcut-host permission to the
 * default launcher alone); otherwise every query degrades to an empty list. Pins arrive through [accept] from ShortcutPinActivity
 * (`requestPinShortcut`) or from an `ACTION_CREATE_SHORTCUT` result; a [LauncherApps.Callback] keeps the list fresh when apps add,
 * update or remove shortcuts.
 */
class ShortcutsRepository(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val user: UserHandle = Process.myUserHandle()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val infos = ConcurrentHashMap<String, ShortcutInfo>()
    private val iconCache = ConcurrentHashMap<String, Drawable>()

    private val _shortcuts = MutableStateFlow<List<ShortcutEntry>>(emptyList())
    val shortcuts: StateFlow<List<ShortcutEntry>> = _shortcuts

    private val callback = object : LauncherApps.Callback() {
        override fun onShortcutsChanged(packageName: String, shortcuts: List<ShortcutInfo>, user: UserHandle) = refreshAsync()
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) = refreshAsync()
        override fun onPackageAdded(packageName: String?, user: UserHandle?) = refreshAsync()
        override fun onPackageChanged(packageName: String?, user: UserHandle?) = refreshAsync()
        override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = refreshAsync()
        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = refreshAsync()
    }

    init {
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        refreshAsync()
    }

    fun refreshAsync() {
        scope.launch { refresh() }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val found = runCatching { query() }.onFailure { Log.w(TAG, "shortcut query failed", it) }.getOrDefault(emptyList())
            .filter { it.isEnabled && it.`package` != context.packageName }
        val byId = found.associateBy { "${LibraryItem.SHORTCUT_PREFIX}${it.`package`}/${it.id}" }
        infos.keys.retainAll(byId.keys)
        infos.putAll(byId)
        iconCache.clear() // icons can change with an update; they are re-read lazily
        _shortcuts.value = byId.map { (_, s) ->
            ShortcutEntry(
                pkg = s.`package`,
                shortcutId = s.id,
                label = (s.shortLabel ?: s.longLabel)?.toString()?.takeIf { it.isNotBlank() } ?: s.id,
                manifestOnly = s.isDeclaredInManifest && !s.isPinned && !s.isDynamic
            )
        }.sortedBy { it.label.lowercase() }
    }

    private fun query(): List<ShortcutInfo> {
        if (!launcherApps.hasShortcutHostPermission()) return emptyList()
        val q = LauncherApps.ShortcutQuery().setQueryFlags(
            LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
        )
        return launcherApps.getShortcuts(q, user).orEmpty()
    }

    fun icon(entry: ShortcutEntry): Drawable? {
        iconCache[entry.id]?.let { return it }
        val info = infos[entry.id] ?: return null
        val d = runCatching { launcherApps.getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi) }.getOrNull() ?: return null
        iconCache[entry.id] = d
        return d
    }

    /** Starts the shortcut; [options] carries ActivityOptions (launch display). Throws like startActivity on failure. */
    fun launch(entry: ShortcutEntry, options: Bundle?) = launcherApps.startShortcut(entry.pkg, entry.shortcutId, null, options, user)

    /**
     * Accepts a pin request carried by [intent] (CONFIRM_PIN_SHORTCUT, or an `ACTION_CREATE_SHORTCUT` result built with
     * `ShortcutManager.createShortcutResultIntent`). Returns the shortcut label, or null when there is no valid shortcut request
     * (widgets and legacy `EXTRA_SHORTCUT_*` results are not supported).
     */
    fun accept(intent: Intent?): String? {
        val request = intent?.let { runCatching { launcherApps.getPinItemRequest(it) }.getOrNull() } ?: return null
        if (request.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT || !request.isValid) return null
        val info = request.shortcutInfo ?: return null
        val ok = runCatching { request.accept() }.onFailure { Log.w(TAG, "pin accept failed", it) }.getOrDefault(false)
        if (!ok) return null
        refreshAsync()
        return (info.shortLabel ?: info.longLabel)?.toString() ?: info.id
    }

    /** Apps that answer `ACTION_CREATE_SHORTCUT` (DroidDeck does), for the Apps screen's "Add shortcut from…" list. */
    suspend fun creators(): List<ShortcutCreator> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_CREATE_SHORTCUT), 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { ShortcutCreator(it.loadLabel(pm).toString(), ComponentName(it.activityInfo.packageName, it.activityInfo.name)) }
            .sortedBy { it.label.lowercase() }
    }

    companion object {
        private const val TAG = "Shortcuts"
    }
}
