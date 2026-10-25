package io.github.esko997.sortie.launch

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.IntentCompat
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.manifest.LaunchProfile
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Android invokes this private, one-shot sender only after the user chooses an app; cancelling is not a play session. */
class ChooserLaunchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val chosen = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java) ?: return
        val game = intent.getStringExtra(GAME) ?: return
        val profile = intent.getStringExtra(PROFILE) ?: return
        val display = intent.getStringExtra(DISPLAY) ?: return
        val target = intent.getIntExtra(TARGET, -1).takeIf { it >= 0 }
        val origin = intent.getStringArrayExtra(ORIGIN)?.toList()
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                (context.applicationContext as SortieApp).graph.launcher.chooserSelected(game, profile, display, target, chosen, origin)
            } catch (e: Exception) {
                Log.w("SortieLaunch", "record chosen app", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION = "io.github.esko997.sortie.CHOOSER_SELECTED"
        private const val GAME = "game"
        private const val PROFILE = "profile"
        private const val DISPLAY = "display"
        private const val TARGET = "target"
        private const val ORIGIN = "origin"
        private val requests = AtomicInteger(SystemClock.uptimeMillis().toInt())

        /**
         * [origin] is the launch order that reached [profile] as a system fallback ([chooserOrigin]); only then may the pick
         * be remembered for the system. Null for a one-off, per-game or deliberate Open with….
         */
        internal fun sender(context: Context, game: String, profile: LaunchProfile, target: Int?, origin: List<String>?): IntentSender {
            val callback = Intent(context, ChooserLaunchReceiver::class.java)
                .setAction(ACTION)
                .putExtra(GAME, game)
                .putExtra(PROFILE, profile.id)
                .putExtra(DISPLAY, profile.display)
                .putExtra(TARGET, target ?: -1)
            origin?.let { callback.putExtra(ORIGIN, it.toTypedArray()) }
            // The system fills EXTRA_CHOSEN_COMPONENT. The receiver itself is not exported, and the sender is single-use.
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            return PendingIntent.getBroadcast(
                context,
                requests.getAndIncrement(),
                callback,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_CANCEL_CURRENT or mutable
            ).intentSender
        }
    }
}
