package dk.thorbjorn.drlyd

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Hjertet i appen: bilens medieafspiller forbinder sig hertil,
 * henter fanerne og beder om at få afspillet ting.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DrLydService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private lateinit var catalog: Catalog
    private val io: ListeningExecutorService = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(3))
    private val main = Handler(Looper.getMainLooper())

    // Hvilken reserve-adresse vi er nået til for hver kanal, når en stream fejler
    private val liveAttempts = HashMap<String, Int>()
    private var healedOnce = false
    private var internalSwitch = false

    private val periodicSave = object : Runnable {
        override fun run() {
            if (player.isPlaying) saveProgress()
            main.postDelayed(this, 10_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        catalog = Catalog(this)
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        player.addListener(playerListener)
        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()

        main.postDelayed(periodicSave, 10_000)
        LibraryEvents.listener = { main.post { notifyAllTabs() } }

        // Hent den nyeste opsætning i baggrunden (højst hver 6. time)
        io.execute {
            if (ConfigRepo.refresh(this, force = false)) main.post { notifyAllTabs() }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        saveProgress()
        main.removeCallbacksAndMessages(null)
        LibraryEvents.listener = null
        session?.run {
            player.release()
            release()
        }
        session = null
        io.shutdown()
        super.onDestroy()
    }

    // ---------- Afspiller-hændelser ----------

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) {
                saveProgress()
                notifyChanged(Catalog.DIN)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                saveProgress(ended = true)
                notifyChanged(Catalog.DIN)
            }
            if (playbackState == Player.STATE_READY) {
                player.currentMediaItem?.let { liveAttempts.remove(it.mediaId) }
                healedOnce = false
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!internalSwitch) {
                liveAttempts.clear()
                healedOnce = false
            }
            internalSwitch = false
            mediaItem?.let { Progress.setLast(this@DrLydService, it.mediaId) }
        }

        override fun onPlayerError(error: PlaybackException) {
            tryNextStream()
        }
    }

    /**
     * En live-stream fejlede. Prøv næste adresse fra opsætningen.
     * Er der ikke flere, hentes en frisk opsætning fra nettet, og vi prøver igen.
     */
    private fun tryNextStream() {
        val item = player.currentMediaItem ?: return
        val id = item.mediaId
        if (!id.startsWith("live:")) return
        val channelId = id.removePrefix("live:")
        val ch = ConfigRepo.get(this).channel(channelId) ?: return
        val next = (liveAttempts[id] ?: 0) + 1
        if (next < ch.urls.size) {
            liveAttempts[id] = next
            playNow(catalog.liveItem(ch, next))
            return
        }
        if (healedOnce) return
        healedOnce = true
        io.execute {
            ConfigRepo.refresh(this, force = true)
            val fresh = ConfigRepo.get(this).channel(channelId)
            main.post {
                if (session == null) return@post
                if (fresh != null && fresh.urls != ch.urls) {
                    liveAttempts[id] = 0
                    playNow(catalog.liveItem(fresh, 0))
                    notifyAllTabs()
                }
            }
        }
    }

    private fun playNow(item: MediaItem) {
        internalSwitch = true
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }

    private fun saveProgress(ended: Boolean = false) {
        val item = player.currentMediaItem ?: return
        if (!item.mediaId.startsWith("ep:")) return
        val extras = item.mediaMetadata.extras ?: return
        val url = extras.getString("url") ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val position = player.currentPosition
        val done = ended || (duration > 0 && duration - position < 60_000)
        Progress.save(
            this, url,
            slug = extras.getString("slug") ?: "",
            title = item.mediaMetadata.title?.toString() ?: "",
            showTitle = item.mediaMetadata.artist?.toString() ?: "",
            image = extras.getString("image"),
            positionMs = if (done) 0L else position,
            durationMs = duration,
            done = done,
        )
    }

    private fun notifyChanged(parentId: String) {
        session?.notifyChildrenChanged(parentId, 1, null)
    }

    private fun notifyAllTabs() {
        (listOf(Catalog.ROOT, Catalog.LIVE_ALL) + Catalog.TABS).forEach { notifyChanged(it) }
    }

    private fun resumePosition(item: MediaItem): Long {
        val url = item.mediaMetadata.extras?.getString("url") ?: return C.TIME_UNSET
        val saved = Progress.get(this, url) ?: return C.TIME_UNSET
        return if (saved.started && !saved.done) saved.positionMs else C.TIME_UNSET
    }

    /** Bilen sender kun et id (eller en søgning fra stemmestyring). Her laves det om til noget, der kan spilles. */
    private fun resolve(item: MediaItem): MediaItem? {
        if (item.localConfiguration != null) return item
        if (item.mediaId.isNotBlank()) return catalog.playable(item.mediaId)
        val query = item.requestMetadata.searchQuery
        if (query.isNullOrBlank()) return Progress.last(this)?.let { catalog.playable(it) }
        return catalog.search(query).firstOrNull()
    }

    private fun <T> slice(all: List<T>, page: Int, pageSize: Int): List<T> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return all
        val from = page.toLong() * pageSize
        if (from >= all.size) return emptyList()
        val to = minOf(all.size.toLong(), from + pageSize)
        return all.subList(from.toInt(), to.toInt())
    }

    // ---------- Det bilen kalder ----------

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val extras = Bundle().apply {
                putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
                putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
                putInt(Catalog.STYLE_BROWSABLE, Catalog.GRID)
                putInt(Catalog.STYLE_PLAYABLE, Catalog.LIST)
            }
            val rootParams = LibraryParams.Builder().setExtras(extras).build()
            return Futures.immediateFuture(LibraryResult.ofItem(catalog.rootItem(), rootParams))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            io.submit(Callable {
                try {
                    LibraryResult.ofItemList(slice(catalog.children(parentId), page, pageSize), params)
                } catch (e: Exception) {
                    LibraryResult.ofError<ImmutableList<MediaItem>>(SessionError.ERROR_IO)
                }
            })

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            io.submit(Callable {
                val item = runCatching { catalog.item(mediaId) }.getOrNull()
                if (item != null) LibraryResult.ofItem(item, null)
                else LibraryResult.ofError<MediaItem>(SessionError.ERROR_BAD_VALUE)
            })

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            io.execute {
                val count = runCatching { catalog.search(query).size }.getOrDefault(0)
                main.post {
                    if (this@DrLydService.session != null) session.notifySearchResultChanged(browser, query, count, params)
                }
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            io.submit(Callable {
                LibraryResult.ofItemList(slice(catalog.search(query), page, pageSize), params)
            })

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            io.submit(Callable { mediaItems.mapNotNull { resolve(it) }.toMutableList() })

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            io.submit(Callable {
                val resolved = mediaItems.mapNotNull { resolve(it) }
                if (resolved.isEmpty()) throw UnsupportedOperationException("Kunne ikke finde noget at spille")
                val index = if (startIndex in resolved.indices) startIndex else 0
                var position = startPositionMs
                if (position == C.TIME_UNSET && resolved.isNotEmpty()) position = resumePosition(resolved[index])
                MediaSession.MediaItemsWithStartPosition(resolved, index, position)
            })

        /** Når bilen startes og vil fortsætte, hvor man slap. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            io.submit(Callable {
                val item = Progress.last(this@DrLydService)?.let { catalog.playable(it) }
                    ?: throw UnsupportedOperationException("Intet at genoptage")
                MediaSession.MediaItemsWithStartPosition(listOf(item), 0, resumePosition(item))
            })
    }
}
