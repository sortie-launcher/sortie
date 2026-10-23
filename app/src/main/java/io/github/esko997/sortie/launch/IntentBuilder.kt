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

    fun vars(file: File, systemId: String, contentUri: Uri?): Map<String, String> = mapOf(
        "file" to file.path,
        "uri" to (contentUri?.toString() ?: Uri.fromFile(file).toString()),
        "stem" to file.nameWithoutExtension,
        "name" to file.name,
        "dir" to (file.parent ?: ""),
        "system" to systemId,
        "titleid" to (TITLE_ID.find(file.name)?.value ?: "")
    )

    fun substitute(template: String, vars: Map<String, String>): String = PLACEHOLDER.replace(template) {
        vars[it.groupValues[1]] ?: ""
    }

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

    /** `adb shell am start …` equivalent, for the diagnostics screen. */
    fun describe(intent: Intent): String = buildString {
        append("am start")
        intent.action?.let { append(" -a ").append(it) }
        intent.component?.let {
            append(" -n ").append(it.flattenToShortString())
        } ?: intent.`package`?.let { append(" -p ").append(it) }
        intent.data?.let { append(" -d \"").append(it).append('"') }
        intent.type?.let { append(" -t ").append(it) }
        intent.extras?.let { b ->
            for (k in b.keySet()) {
                when (val v = b.get(k)) {
                    is Array<*> -> append(
                        " --esa "
                    ).append(k).append(' ').append(v.joinToString(",") { it.toString().replace(",", "\\,") })
                    else -> append(" --es ").append(k).append(" \"").append(v).append('"')
                }
            }
        }
    }

    private fun flagValue(name: String): Int? = when (name.uppercase()) {
        "NEW_TASK" -> Intent.FLAG_ACTIVITY_NEW_TASK
        "CLEAR_TOP" -> Intent.FLAG_ACTIVITY_CLEAR_TOP
        "CLEAR_TASK" -> Intent.FLAG_ACTIVITY_CLEAR_TASK
        "SINGLE_TOP" -> Intent.FLAG_ACTIVITY_SINGLE_TOP
        "GRANT_READ_URI_PERMISSION" -> Intent.FLAG_GRANT_READ_URI_PERMISSION
        "GRANT_WRITE_URI_PERMISSION" -> Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        else -> null
    }
}
