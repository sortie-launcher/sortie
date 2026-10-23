package io.github.esko997.sortie.launch

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import io.github.esko997.sortie.data.manifest.LaunchProfile
import java.io.File

/**
 * Turns a profile plus a file into an Intent. Placeholders: ${file} ${uri} ${stem} ${name} ${dir} ${system} ${titleid}.
 * Nothing here knows any emulator by name; the manifest does.
 */
object IntentBuilder {
    private val PLACEHOLDER = Regex("\\$\\{(\\w+)\\}")
    private val TITLE_ID = Regex("[A-Z]{4}\\d{5}")

    fun vars(file: File, systemId: String, contentUri: Uri?, titleId: String? = null): Map<String, String> = mapOf(
        "file" to file.path,
        "uri" to (contentUri?.toString() ?: Uri.fromFile(file).toString()),
        "stem" to file.nameWithoutExtension,
        "name" to file.name,
        "dir" to (file.parent ?: ""),
        "system" to systemId,
        "titleid" to (titleId ?: TITLE_ID.find(file.name)?.value ?: "")
    )

    fun substitute(template: String, vars: Map<String, String>): String = PLACEHOLDER.replace(template) { vars[it.groupValues[1]] ?: "" }

    /** True when any field of the profile references `${uri}`, i.e. the receiver needs a URI permission grant. */
    fun usesContentUri(profile: LaunchProfile): Boolean = (profile.data?.contains("\${uri}") == true) || profile.extras.values.any { it.contains("\${uri}") } ||
        profile.extrasArray.values.any { l -> l.any { it.contains("\${uri}") } }

    fun build(profile: LaunchProfile, vars: Map<String, String>): Intent {
        val intent = Intent(profile.action ?: Intent.ACTION_MAIN)
        profile.`package`?.let { pkg ->
            val activity = profile.activity
            if (activity != null) {
                intent.component = ComponentName(pkg, if (activity.startsWith(".")) pkg + activity else activity)
            } else {
                intent.setPackage(pkg)
            }
        }
        val data = profile.data?.let { Uri.parse(substitute(it, vars)) }
        when {
            data != null && profile.mimeType != null -> intent.setDataAndType(data, profile.mimeType)
            data != null -> intent.data = data
            profile.mimeType != null -> intent.type = profile.mimeType
        }
        profile.extras.forEach { (k, v) -> intent.putExtra(k, substitute(v, vars)) }
        profile.extrasArray.forEach { (k, v) -> intent.putExtra(k, v.map { substitute(it, vars) }.toTypedArray()) }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        profile.flags.forEach { f -> flagValue(f)?.let { intent.addFlags(it) } }
        return intent
    }

    /** `adb shell am start …` equivalent for the diagnostics screen; values are single-quoted for a POSIX shell. */
    fun describe(intent: Intent, displayId: Int? = null): String = buildString {
        append("am start")
        intent.action?.let { append(" -a ").append(it) }
        intent.component?.let { append(" -n ").append(sq(it.flattenToShortString())) } ?: intent.`package`?.let { append(" -p ").append(it) }
        intent.data?.let { append(" -d ").append(sq(it.toString())) }
        intent.type?.let { append(" -t ").append(it) }
        intent.extras?.let { b ->
            for (k in b.keySet()) {
                when (val v = b.get(k)) {
                    is Array<*> -> append(" --esa ").append(k).append(' ').append(sq(v.joinToString(",") { it.toString().replace(",", "\\,") }))
                    is Boolean -> append(" --ez ").append(k).append(' ').append(v)
                    is Int -> append(" --ei ").append(k).append(' ').append(v)
                    else -> append(" --es ").append(k).append(' ').append(sq(v.toString()))
                }
            }
        }
        FLAG_NAMES.forEach { (flag, name) -> if (intent.flags and flag != 0) append(" --activity-").append(name) }
        displayId?.let { append(" --display ").append(it) }
    }

    private fun sq(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private val FLAG_NAMES = listOf(
        Intent.FLAG_ACTIVITY_NEW_TASK to "new-task",
        Intent.FLAG_ACTIVITY_CLEAR_TASK to "clear-task",
        Intent.FLAG_ACTIVITY_CLEAR_TOP to "clear-top",
        Intent.FLAG_ACTIVITY_SINGLE_TOP to "single-top",
        Intent.FLAG_ACTIVITY_NO_HISTORY to "no-history",
        Intent.FLAG_GRANT_READ_URI_PERMISSION to "grant-read-uri-permission"
    )

    private fun flagValue(name: String): Int? = when (name.uppercase()) {
        "NEW_TASK" -> Intent.FLAG_ACTIVITY_NEW_TASK
        "CLEAR_TOP" -> Intent.FLAG_ACTIVITY_CLEAR_TOP
        "CLEAR_TASK" -> Intent.FLAG_ACTIVITY_CLEAR_TASK
        "SINGLE_TOP" -> Intent.FLAG_ACTIVITY_SINGLE_TOP
        "NO_HISTORY" -> Intent.FLAG_ACTIVITY_NO_HISTORY
        "GRANT_READ_URI_PERMISSION" -> Intent.FLAG_GRANT_READ_URI_PERMISSION
        "GRANT_WRITE_URI_PERMISSION" -> Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        else -> null
    }
}
