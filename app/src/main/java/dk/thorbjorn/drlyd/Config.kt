package dk.thorbjorn.drlyd

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Channel(
    val id: String,
    val title: String,
    val short: String,
    val subtitle: String,
    val color: String,
    val urls: List<String>,
)

data class Category(val id: String, val title: String, val podcasts: List<String>, val channels: List<String>)

data class Featured(val slug: String, val label: String)

/** Alt det, der kan ændres uden en ny app: kanaler, stream-adresser, kategorier og fremhævet. */
data class AppConfig(
    val version: Int,
    val feedPattern: String,
    val channels: List<Channel>,
    val defaultLive: List<String>,
    val featured: List<Featured>,
    val categories: List<Category>,
    val popular: List<String>,
    val defaultPodcasts: List<String>,
) {
    fun channel(id: String): Channel? = channels.firstOrNull { it.id == id }
    fun category(id: String): Category? = categories.firstOrNull { it.id == id }
    fun feedUrl(slug: String): String = feedPattern.replace("{slug}", slug)

    companion object {
        fun parse(text: String): AppConfig {
            val o = JSONObject(text)
            val channels = o.getJSONArray("channels").objects().map { c ->
                Channel(
                    id = c.getString("id"),
                    title = c.getString("title"),
                    short = c.optString("short", c.getString("title")),
                    subtitle = c.optString("subtitle", ""),
                    color = c.optString("color", "#A32D2D"),
                    urls = c.getJSONArray("urls").strings(),
                )
            }.filter { it.urls.isNotEmpty() }
            require(channels.isNotEmpty()) { "Ingen kanaler i opsætningen" }
            return AppConfig(
                version = o.optInt("version", 0),
                feedPattern = o.optString("feedPattern", "https://api.dr.dk/podcasts/v1/feeds/{slug}"),
                channels = channels,
                defaultLive = o.optJSONArray("defaultLive")?.strings() ?: channels.take(6).map { it.id },
                featured = o.optJSONArray("featured")?.objects()
                    ?.map { Featured(it.getString("slug"), it.optString("label", "")) } ?: emptyList(),
                categories = o.optJSONArray("categories")?.objects()?.map {
                    Category(
                        id = it.getString("id"),
                        title = it.getString("title"),
                        podcasts = it.optJSONArray("podcasts")?.strings() ?: emptyList(),
                        channels = it.optJSONArray("channels")?.strings() ?: emptyList(),
                    )
                } ?: emptyList(),
                popular = o.optJSONArray("popular")?.strings() ?: emptyList(),
                defaultPodcasts = o.optJSONArray("defaultPodcasts")?.strings() ?: emptyList(),
            )
        }

        private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    }
}

/**
 * Henter opsætningen fra en fil på nettet (som standard config.json i GitHub-repoet).
 * Rækkefølge: senest hentede kopi → kopien indbygget i appen.
 */
object ConfigRepo {
    const val DEFAULT_URL = "https://raw.githubusercontent.com/jacobtbj/dr-lyd-auto/main/config/config.json"
    private const val MAX_AGE_MS = 6 * 60 * 60 * 1000L

    @Volatile
    private var current: AppConfig? = null

    fun get(ctx: Context): AppConfig =
        current ?: synchronized(this) { current ?: loadLocal(ctx.applicationContext).also { current = it } }

    private fun cacheFile(ctx: Context) = File(ctx.applicationContext.filesDir, "config.json")

    private fun loadLocal(ctx: Context): AppConfig {
        val f = cacheFile(ctx)
        if (f.exists()) {
            runCatching { return AppConfig.parse(f.readText()) }
        }
        return AppConfig.parse(ctx.assets.open("config.json").bufferedReader().use { it.readText() })
    }

    /** Henter en ny opsætning. Returnerer true hvis den er ændret. */
    fun refresh(ctx: Context, force: Boolean): Boolean {
        val app = ctx.applicationContext
        val prefs = Settings.prefs(app)
        val last = prefs.getLong("configFetchedAt", 0L)
        if (!force && System.currentTimeMillis() - last < MAX_AGE_MS) return false
        return try {
            val text = Http.text(Settings.configUrl(app))
            val cfg = AppConfig.parse(text) // afviser en ødelagt fil, så den gamle bevares
            val file = cacheFile(app)
            val changed = !file.exists() || file.readText() != text
            val tmp = File(file.parentFile, "config.json.tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
            current = cfg
            prefs.edit()
                .putLong("configFetchedAt", System.currentTimeMillis())
                .putString("configStatus", "Hentet ${now()} · version ${cfg.version} · ${cfg.channels.size} kanaler")
                .apply()
            changed
        } catch (e: Exception) {
            prefs.edit().putString("configStatus", "Kunne ikke hentes ${now()}: ${e.message}").apply()
            false
        }
    }

    private fun now(): String = SimpleDateFormat("d. MMM HH:mm", Locale("da", "DK")).format(Date())
}

/** Brugerens egne valg. */
object Settings {
    fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun configUrl(ctx: Context): String =
        prefs(ctx).getString("configUrl", null)?.takeIf { it.isNotBlank() } ?: ConfigRepo.DEFAULT_URL

    fun setConfigUrl(ctx: Context, url: String) {
        prefs(ctx).edit().putString("configUrl", url.trim()).putLong("configFetchedAt", 0L).apply()
    }

    fun configStatus(ctx: Context): String = prefs(ctx).getString("configStatus", null) ?: "Bruger den indbyggede opsætning"

    fun liveIds(ctx: Context): List<String> {
        val cfg = ConfigRepo.get(ctx)
        val saved = prefs(ctx).getString("live", null)?.split(",")?.filter { it.isNotBlank() }
        return (saved ?: cfg.defaultLive).filter { cfg.channel(it) != null }
    }

    fun setLiveIds(ctx: Context, ids: List<String>) {
        prefs(ctx).edit().putString("live", ids.joinToString(",")).apply()
    }

    fun podcasts(ctx: Context): List<String> =
        prefs(ctx).getString("podcasts", null)?.split(",")?.filter { it.isNotBlank() }
            ?: ConfigRepo.get(ctx).defaultPodcasts

    fun setPodcasts(ctx: Context, slugs: List<String>) {
        prefs(ctx).edit().putString("podcasts", slugs.distinct().joinToString(",")).apply()
    }
}

/** Giver tjenesten besked, når indstillinger eller opsætning ændres. */
object LibraryEvents {
    @Volatile
    var listener: (() -> Unit)? = null

    fun changed() {
        listener?.invoke()
    }
}

object Slugs {
    /** Tager et dr.dk-link, et feed-link eller et programnavn og laver det til et feed-navn. */
    fun fromInput(input: String): String {
        val s = input.trim()
        Regex("feeds/([A-Za-z0-9-]+)").find(s)?.let { return it.groupValues[1].lowercase() }
        if (s.contains("dr.dk")) {
            val url = if (s.startsWith("http")) s else "https://$s"
            val last = android.net.Uri.parse(url).pathSegments.lastOrNull { it.isNotBlank() }
            if (last != null) return clean(last)
        }
        return clean(s)
    }

    fun clean(s: String): String = s.lowercase()
        .replace("æ", "ae").replace("ø", "oe").replace("å", "aa")
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

    fun pretty(slug: String): String =
        slug.replace(Regex("-\\d+$"), "").replace('-', ' ').replaceFirstChar { it.uppercase() }
}
