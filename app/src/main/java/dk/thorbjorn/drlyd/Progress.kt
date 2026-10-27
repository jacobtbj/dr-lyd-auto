package dk.thorbjorn.drlyd

import android.content.Context
import org.json.JSONObject

/** Husker hvor langt man er nået i hvert afsnit, så "Fortsæt" og fremdriftsbjælker virker. */
object Progress {
    private const val LAST_KEY = "__last"

    data class Entry(
        val url: String,
        val slug: String,
        val title: String,
        val showTitle: String,
        val image: String?,
        val positionMs: Long,
        val durationMs: Long,
        val done: Boolean,
        val updated: Long,
    ) {
        val started: Boolean get() = positionMs > 15_000
        val fraction: Double
            get() = if (durationMs > 0) (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0) else 0.0
        val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("progress", Context.MODE_PRIVATE)

    fun get(ctx: Context, url: String): Entry? =
        prefs(ctx).getString(url, null)?.let { runCatching { fromJson(url, JSONObject(it)) }.getOrNull() }

    fun save(
        ctx: Context, url: String, slug: String, title: String, showTitle: String, image: String?,
        positionMs: Long, durationMs: Long, done: Boolean,
    ) {
        val o = JSONObject()
            .put("slug", slug).put("title", title).put("show", showTitle).put("image", image ?: "")
            .put("pos", positionMs).put("dur", durationMs).put("done", done)
            .put("updated", System.currentTimeMillis())
        prefs(ctx).edit().putString(url, o.toString()).apply()
    }

    fun inProgress(ctx: Context): List<Entry> =
        prefs(ctx).all.mapNotNull { (key, value) ->
            if (key.startsWith("http") && value is String) runCatching { fromJson(key, JSONObject(value)) }.getOrNull() else null
        }.filter { !it.done && it.started }.sortedByDescending { it.updated }.take(20)

    fun setLast(ctx: Context, mediaId: String) {
        prefs(ctx).edit().putString(LAST_KEY, mediaId).apply()
    }

    fun last(ctx: Context): String? = prefs(ctx).getString(LAST_KEY, null)

    private fun fromJson(url: String, o: JSONObject) = Entry(
        url = url,
        slug = o.optString("slug"),
        title = o.optString("title"),
        showTitle = o.optString("show"),
        image = o.optString("image").ifBlank { null },
        positionMs = o.optLong("pos"),
        durationMs = o.optLong("dur"),
        done = o.optBoolean("done"),
        updated = o.optLong("updated"),
    )
}
