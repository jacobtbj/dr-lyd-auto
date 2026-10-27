package dk.thorbjorn.drlyd

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bygger det træ, bilen viser:
 *   Hjem · Udforsk · Live · Din lyd  (fanerne øverst)
 *     → podcasts → afsnit
 */
class Catalog(private val ctx: Context) {

    companion object {
        const val ROOT = "root"
        const val HJEM = "tab:hjem"
        const val UDFORSK = "tab:udforsk"
        const val LIVE = "tab:live"
        const val DIN = "tab:din"
        const val LIVE_ALL = "live:all"
        val TABS = listOf(HJEM, UDFORSK, LIVE, DIN)

        // Layout-hints som bilens medieafspiller forstår
        const val STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        const val STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        const val STYLE_SINGLE = "android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT"
        const val STYLE_GROUP = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"
        const val LIST = 1
        const val GRID = 2
        const val CATEGORY_LIST = 3
        const val PLAYBACK_STATUS = "android.media.extra.PLAYBACK_STATUS"
        const val COMPLETION_PCT = "androidx.media.MediaItem.Extras.COMPLETION_PERCENTAGE"

        private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
        private val DANISH = Locale("da", "DK")
    }

    // ---------- Træet ----------

    fun rootItem(): MediaItem = folder(ROOT, "DR Lyd", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    fun children(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> listOf(
            folder(HJEM, "Hjem", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, browsableStyle = GRID, playableStyle = GRID),
            folder(UDFORSK, "Udforsk", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, browsableStyle = GRID, playableStyle = GRID),
            folder(LIVE, "Live", MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS, browsableStyle = LIST, playableStyle = GRID),
            folder(DIN, "Din lyd", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, browsableStyle = GRID, playableStyle = LIST),
        )
        parentId == HJEM -> hjem()
        parentId == UDFORSK -> udforsk()
        parentId == LIVE -> live()
        parentId == LIVE_ALL -> ConfigRepo.get(ctx).channels.map { liveItem(it) }
        parentId == DIN -> dinLyd()
        parentId.startsWith("cat:") -> category(parentId.removePrefix("cat:"))
        parentId.startsWith("show:") -> episodes(parentId.removePrefix("show:").substringBefore('#'))
        else -> emptyList()
    }

    private fun hjem(): List<MediaItem> {
        val cfg = ConfigRepo.get(ctx)
        val mine = Settings.podcasts(ctx)
        val shows = Feeds.shows(ctx, cfg.featured.map { it.slug } + mine).associateBy { it.slug }
        val out = ArrayList<MediaItem>()
        cfg.featured.forEach { f ->
            out += showFolder(f.slug, shows[f.slug], subtitle = f.label.ifBlank { null }, group = "Fremhævet", idSuffix = "#f")
        }
        mine.forEach { s -> out += showFolder(s, shows[s], subtitle = status(shows[s]), group = "Dine genveje") }
        return out
    }

    private fun udforsk(): List<MediaItem> {
        val cfg = ConfigRepo.get(ctx)
        val out = ArrayList<MediaItem>()
        cfg.categories.forEach { c ->
            out += folder("cat:${c.id}", c.title, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                browsableStyle = GRID, playableStyle = GRID, group = "Kategorier", singleStyle = CATEGORY_LIST)
        }
        val shows = Feeds.shows(ctx, cfg.popular).associateBy { it.slug }
        cfg.popular.forEach { s -> out += showFolder(s, shows[s], subtitle = null, group = "Populære podcasts") }
        return out
    }

    private fun category(id: String): List<MediaItem> {
        val cfg = ConfigRepo.get(ctx)
        val cat = cfg.category(id) ?: return emptyList()
        val out = ArrayList<MediaItem>()
        cat.channels.mapNotNull { cfg.channel(it) }.forEach { out += liveItem(it, group = "Kanaler") }
        val shows = Feeds.shows(ctx, cat.podcasts).associateBy { it.slug }
        cat.podcasts.forEach { s -> out += showFolder(s, shows[s], subtitle = status(shows[s]), group = "Podcasts") }
        return out
    }

    private fun live(): List<MediaItem> {
        val cfg = ConfigRepo.get(ctx)
        val out = Settings.liveIds(ctx).mapNotNull { cfg.channel(it) }.map { liveItem(it) }.toMutableList()
        out += folder(LIVE_ALL, "Alle kanaler", MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS,
            browsableStyle = GRID, playableStyle = GRID, singleStyle = CATEGORY_LIST)
        return out
    }

    private fun dinLyd(): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        Progress.inProgress(ctx).forEach { e ->
            val ep = Episode(e.url, e.title, 0L, e.durationMs, e.image)
            out += episodeItem(e.slug, e.showTitle, e.image, ep, group = "Fortsæt med at lytte")
        }
        val mine = Settings.podcasts(ctx)
        val shows = Feeds.shows(ctx, mine).associateBy { it.slug }
        mine.forEach { s -> out += showFolder(s, shows[s], subtitle = status(shows[s]), group = "Mine podcasts", singleStyle = GRID) }
        return out
    }

    private fun episodes(slug: String): List<MediaItem> {
        val show = Feeds.show(ctx, slug) ?: return emptyList()
        return show.episodes.map { episodeItem(show.slug, show.title, show.image, it) }
    }

    // ---------- Enkelte elementer ----------

    /** Bruges når bilen spørger efter ét element (fx ved genoptagelse). */
    fun item(mediaId: String): MediaItem? = when {
        mediaId == ROOT -> rootItem()
        mediaId in TABS -> children(ROOT).firstOrNull { it.mediaId == mediaId }
        mediaId.startsWith("show:") -> {
            val slug = mediaId.removePrefix("show:").substringBefore('#')
            showFolder(slug, Feeds.show(ctx, slug), subtitle = null, group = null)
        }
        else -> playable(mediaId)
    }

    /** Laver et afspilbart element ud fra et id. Bilen sender kun id'et, ikke adressen. */
    fun playable(mediaId: String): MediaItem? {
        if (mediaId.startsWith("live:")) {
            val ch = ConfigRepo.get(ctx).channel(mediaId.removePrefix("live:")) ?: return null
            return liveItem(ch)
        }
        if (mediaId.startsWith("ep:")) {
            val rest = mediaId.removePrefix("ep:")
            val slug = rest.substringBefore('|')
            val url = rest.substringAfter('|', "")
            if (url.isEmpty()) return null
            val show = Feeds.cached(slug) ?: Feeds.show(ctx, slug)
            val ep = show?.episodes?.firstOrNull { it.url == url }
            if (show != null && ep != null) return episodeItem(slug, show.title, show.image, ep)
            val saved = Progress.get(ctx, url)
            return episodeItem(
                slug, saved?.showTitle ?: Slugs.pretty(slug), saved?.image,
                Episode(url, saved?.title ?: "Afsnit", 0L, saved?.durationMs ?: 0L, saved?.image),
            )
        }
        return null
    }

    /** Stemmestyring og søgning: "spil P3", "spil Genstart". */
    fun search(query: String): List<MediaItem> {
        val q = norm(query)
        if (q.isBlank()) return emptyList()
        val cfg = ConfigRepo.get(ctx)
        val compact = q.replace(" ", "")
        val out = ArrayList<MediaItem>()
        cfg.channels.filter {
            norm(it.title).contains(q) || norm(it.short.replace("\n", " ")).contains(q) || it.id.contains(compact)
        }.forEach { out += liveItem(it) }
        val slugs = Settings.podcasts(ctx) + cfg.popular + cfg.featured.map { it.slug } + cfg.categories.flatMap { it.podcasts }
        Feeds.shows(ctx, slugs).forEach { s ->
            if (norm(s.title).contains(q)) s.episodes.firstOrNull()?.let { out += episodeItem(s.slug, s.title, s.image, it) }
            s.episodes.filter { norm(it.title).contains(q) }.take(5).forEach { out += episodeItem(s.slug, s.title, s.image, it) }
        }
        return out.distinctBy { it.mediaId }.take(40)
    }

    fun liveItem(ch: Channel, urlIndex: Int = 0, group: String? = null): MediaItem {
        val url = ch.urls[urlIndex.coerceIn(0, ch.urls.lastIndex)]
        val extras = Bundle().apply { group?.let { putString(STYLE_GROUP, it) } }
        return MediaItem.Builder()
            .setMediaId("live:${ch.id}")
            .setUri(url)
            .setMimeType(if (url.contains(".m3u8")) MimeTypes.APPLICATION_M3U8 else null)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(ch.title)
                    .setSubtitle(ch.subtitle)
                    .setArtist("Direkte")
                    .setArtworkUri(ArtProvider.channelUri(ch))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    fun episodeItem(slug: String, showTitle: String, showImage: String?, ep: Episode, group: String? = null): MediaItem {
        val saved = Progress.get(ctx, ep.url)
        val image = ep.image ?: showImage
        val extras = Bundle().apply {
            putString("slug", slug)
            putString("url", ep.url)
            putString("image", image)
            putInt(PLAYBACK_STATUS, when {
                saved?.done == true -> 2
                saved?.started == true -> 1
                else -> 0
            })
            if (saved != null && saved.started && !saved.done) putDouble(COMPLETION_PCT, saved.fraction)
            group?.let { putString(STYLE_GROUP, it) }
        }
        return MediaItem.Builder()
            .setMediaId("ep:$slug|${ep.url}")
            .setUri(ep.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(ep.title)
                    .setArtist(showTitle)
                    .setAlbumTitle(showTitle)
                    .setSubtitle(episodeSubtitle(ep, saved, showTitle, group != null))
                    .setArtworkUri(ArtProvider.imageUri(image))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    private fun showFolder(
        slug: String, show: Show?, subtitle: String?, group: String?,
        idSuffix: String = "", singleStyle: Int? = null,
    ): MediaItem {
        val extras = Bundle().apply {
            putInt(STYLE_PLAYABLE, LIST)
            group?.let { putString(STYLE_GROUP, it) }
            singleStyle?.let { putInt(STYLE_SINGLE, it) }
        }
        return MediaItem.Builder()
            .setMediaId("show:$slug$idSuffix")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(show?.title ?: Slugs.pretty(slug))
                    .setSubtitle(subtitle)
                    .setArtworkUri(ArtProvider.imageUri(show?.image))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    private fun folder(
        id: String, title: String, type: Int,
        browsableStyle: Int? = null, playableStyle: Int? = null, group: String? = null, singleStyle: Int? = null,
    ): MediaItem {
        val extras = Bundle().apply {
            browsableStyle?.let { putInt(STYLE_BROWSABLE, it) }
            playableStyle?.let { putInt(STYLE_PLAYABLE, it) }
            group?.let { putString(STYLE_GROUP, it) }
            singleStyle?.let { putInt(STYLE_SINGLE, it) }
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(type)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    // ---------- Tekster ----------

    /** Undertekst under en podcast: "Ny episode", "Fortsæt", "Hørt" eller dato. */
    private fun status(show: Show?): String? {
        val latest = show?.episodes?.firstOrNull() ?: return null
        val saved = Progress.get(ctx, latest.url)
        return when {
            saved?.done == true -> "Hørt"
            saved?.started == true -> "Fortsæt"
            latest.pubDateMs > 0 && System.currentTimeMillis() - latest.pubDateMs < WEEK_MS -> "Ny episode"
            latest.pubDateMs > 0 -> date(latest.pubDateMs)
            else -> null
        }
    }

    private fun episodeSubtitle(ep: Episode, saved: Progress.Entry?, showTitle: String, withShow: Boolean): String {
        val parts = ArrayList<String>()
        if (withShow) parts += showTitle
        when {
            saved?.done == true -> parts += "Hørt"
            saved != null && saved.started && saved.durationMs > 0 -> parts += "${minutes(saved.remainingMs)} tilbage"
            else -> {
                if (ep.pubDateMs > 0) parts += date(ep.pubDateMs)
                if (ep.durationMs > 0) parts += minutes(ep.durationMs)
            }
        }
        return parts.joinToString(" · ")
    }

    private fun date(ms: Long): String = SimpleDateFormat("d. MMM", DANISH).format(Date(ms))

    private fun minutes(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(1)
        return if (m >= 60) "${m / 60} t ${m % 60} m" else "$m min"
    }

    private fun norm(s: String): String = s.lowercase(DANISH).replace("dr ", "").trim()
}
