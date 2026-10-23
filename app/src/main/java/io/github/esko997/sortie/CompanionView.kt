package io.github.esko997.sortie

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.target
import io.github.esko997.sortie.data.db.GameEntity
import java.io.File

/** Art plus a one-line hook for the selected game. Plain views: shared by the Presentation and the companion Activity. */
class CompanionView(context: Context) {
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = true
    }
    private val title = TextView(context).apply {
        textSize = 26f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }
    private val hook = TextView(context).apply {
        textSize = 17f
        setTextColor(context.getColor(R.color.amber))
        gravity = Gravity.CENTER
    }
    private val ctx = context

    val root: View = LinearLayout(context).apply {
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(context.getColor(R.color.ink))
        setPadding(pad, pad, pad, pad)
        title.setPadding(pad, pad, pad, 0)
        hook.setPadding(pad, pad / 2, pad, pad)
        addView(image, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(title)
        addView(hook)
    }

    fun show(game: GameEntity?) {
        if (game == null) {
            image.setImageDrawable(null)
            title.text = ctx.getString(R.string.app_name)
            hook.text = ""
            return
        }
        title.text = game.title
        hook.text = game.hook ?: listOfNotNull(game.year?.toString(), game.developer, game.genre).joinToString(" · ")
        val art = game.screenshot ?: game.cover
        if (art != null) ctx.imageLoader.enqueue(ImageRequest.Builder(ctx).data(File(art)).target(image).build()) else image.setImageDrawable(null)
    }
}
