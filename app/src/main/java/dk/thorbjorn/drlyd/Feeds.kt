package dk.thorbjorn.drlyd

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class Episode(
    val url: String,
    val title: String,
    val pubDateMs: Long,
    val durationMs: Long,
    val image: String?,
)

data class Show(val slug: String, val title: String, val image: String?, val episodes: List<Episode>)

/** Henter og cacher DR's podcast-feeds (RSS). */
object Feeds {
    private const val MAX_AGE_MS = 30 * 60 * 1000L
    private const val MAX_EPISODES = 100
    private const val ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"

    private val memory = ConcurrentHashMap<String, Pair<Long, Show>>()
    private val pool = Executors.newFixedThreadPool(4)

    fun cached(slug: String): Show? = memory[slug]?.second

    fun show(ctx: Context, slug: String, force: Boolean = false): Show? {
        val now = System.currentTimeMillis()
        val mem = memory[slug]
        if (!force && mem != null && now - mem.first < MAX_AGE_MS) return mem.second

        val dir = File(ctx.applicationContext.cacheDir, "feeds").apply { mkdirs() }
        val file = File(dir, Slugs.clean(slug) + ".xml")
        if (!force && mem == null && file.exists() && now - file.lastModified() < MAX_AGE_MS) {
            runCatching { parse(slug, file.readBytes()) }.getOrNull()?.let {
                memory[slug] = file.lastModified() to it
                return it
            }
        }
        return try {
            val bytes = Http.get(ConfigRepo.get(ctx).feedUrl(slug))
            val show = parse(slug, bytes)
            file.writeBytes(bytes)
            memory[slug] = now to show
            show
        } catch (e: Exception) {
            // Uden net: brug den gamle kopi, hvis vi har en
            mem?.second ?: runCatching { parse(slug, file.readBytes()) }.getOrNull()
        }
    }

    /** Henter flere feeds samtidig. Feeds der fejler, udelades. */
    fun shows(ctx: Context, slugs: List<String>): List<Show> {
        val futures = slugs.distinct().map { s -> pool.submit(Callable { show(ctx, s) }) }
        return futures.mapNotNull { runCatching { it.get(20, TimeUnit.SECONDS) }.getOrNull() }
    }

    fun parse(slug: String, bytes: ByteArray): Show {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(ByteArrayInputStream(bytes), null)

        var showTitle: String? = null
        var rssImage: String? = null
        var itunesImage: String? = null
        val episodes = ArrayList<Episode>()

        var inItem = false
        var inImage = false
        var title: String? = null
        var url: String? = null
        var date = 0L
        var duration = 0L
        var image: String? = null

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val ns = p.namespace ?: ""
                when {
                    p.name == "item" && ns.isEmpty() -> {
                        inItem = true; title = null; url = null; date = 0L; duration = 0L; image = null
                    }
                    p.name == "image" && ns.isEmpty() && !inItem -> inImage = true
                    p.name == "url" && inImage -> {
                        val v = p.nextText().trim()
                        if (rssImage == null) rssImage = v
                    }
                    p.name == "title" && ns.isEmpty() -> {
                        val v = p.nextText().trim()
                        if (inItem) title = v else if (!inImage && showTitle == null) showTitle = v
                    }
                    p.name == "enclosure" && inItem -> url = p.getAttributeValue(null, "url")
                    p.name == "pubDate" && inItem -> date = parseDate(p.nextText())
                    p.name == "duration" && ns == ITUNES && inItem -> duration = parseDuration(p.nextText())
                    p.name == "image" && ns == ITUNES -> {
                        val href = p.getAttributeValue(null, "href")
                        if (inItem) image = href else if (itunesImage == null) itunesImage = href
                    }
                }
            } else if (event == XmlPullParser.END_TAG && (p.namespace ?: "").isEmpty()) {
                if (p.name == "item") {
                    inItem = false
                    val u = url
                    if (u != null && episodes.size < MAX_EPISODES) {
                        episodes += Episode(u, title ?: "Afsnit", date, duration, image)
                    }
                } else if (p.name == "image") {
                    inImage = false
                }
            }
            event = p.next()
        }
        return Show(
            slug = slug,
            title = showTitle ?: Slugs.pretty(slug),
            image = itunesImage ?: rssImage,
            episodes = episodes.sortedByDescending { it.pubDateMs },
        )
    }

    private fun parseDate(s: String): Long {
        val text = s.trim()
        for (pattern in listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "dd MMM yyyy HH:mm:ss Z")) {
            runCatching { return SimpleDateFormat(pattern, Locale.ENGLISH).parse(text)!!.time }
        }
        return 0L
    }

    private fun parseDuration(s: String): Long {
        val parts = s.trim().split(":").mapNotNull { it.trim().toLongOrNull() }
        if (parts.isEmpty()) return 0L
        return parts.fold(0L) { acc, v -> acc * 60 + v } * 1000
    }
}
