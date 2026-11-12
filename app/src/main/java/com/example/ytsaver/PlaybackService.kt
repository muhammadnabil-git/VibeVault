package com.example.ytsaver

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Keeps audio/video playing in the background and shows the media notification (rewind 10 s, previous / next,
 * play / pause, forward 10 s). It is also a media library: Android Auto and other media browsers can list
 * Downloads, Playlists and Favorites (files on the phone) and start them.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null

    private companion object {
        const val ROOT = "root"
        const val DOWNLOADS = "downloads"
        const val PLAYLISTS = "playlists"
        const val FAVORITES = "favorites"
        const val PL_PREFIX = "pl:"
        const val CMD_BACK = "ms_seek_back_10"
        const val CMD_FWD = "ms_seek_fwd_10"
    }

    private val backCmd = SessionCommand(CMD_BACK, Bundle.EMPTY)
    private val fwdCmd = SessionCommand(CMD_FWD, Bundle.EMPTY)

    override fun onCreate() {
        super.onCreate()
        Library.load(this)
        HistoryStore.load(this)

        // tapping the notification opens the app
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val s = MediaLibrarySession.Builder(this, PlayerHolder.get(this), LibraryCallback()).setSessionActivity(open).build()
        // the two seek buttons of the notification / lock screen / car
        s.setCustomLayout(
            ImmutableList.of(
                CommandButton.Builder().setDisplayName(L.t("Back 10 s", "رجوع 10 ث")).setIconResId(R.drawable.ic_replay_10).setSessionCommand(backCmd).build(),
                CommandButton.Builder().setDisplayName(L.t("Forward 10 s", "تقدّم 10 ث")).setIconResId(R.drawable.ic_forward_10).setSessionCommand(fwdCmd).build(),
            ),
        )
        session = s

        // The app drives the player directly, so the session must be registered here,
        // otherwise Media3 never creates the play/pause notification.
        addSession(s)

        // the notification shows the app's own icon instead of the default Media3 one
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().also { it.setSmallIcon(R.drawable.ic_stat_app) },
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().add(backCmd).add(fwdCmd).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(cmds).build()
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_BACK -> session.player.seekBack()
                CMD_FWD -> session.player.seekForward()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        // a controller (the car) may send items that carry only an id: the id of a file is its uri
        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val fixed = mediaItems.map { if (it.localConfiguration == null && it.mediaId.isNotBlank()) it.buildUpon().setUri(it.mediaId).build() else it }
            // This service is exported (the car and the system must reach it), so another app could ask it to open ANY
            // address, e.g. a file:// path inside this app's private storage. Items from other apps are limited to
            // web and media-library addresses; the app's own screens are not restricted.
            val safe = if (controller.packageName == packageName) fixed else fixed.filter {
                val scheme = it.localConfiguration?.uri?.scheme?.lowercase()
                scheme == "http" || scheme == "https" || scheme == "content"
            }
            return Futures.immediateFuture(safe.toMutableList())
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "VibeVault"), params))

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = when {
                mediaId == ROOT -> folder(ROOT, "VibeVault")
                mediaId == DOWNLOADS -> folder(DOWNLOADS, L.t("Downloads", "التنزيلات"))
                mediaId == PLAYLISTS -> folder(PLAYLISTS, L.t("Playlists", "قوائم التشغيل"))
                mediaId == FAVORITES -> folder(FAVORITES, L.t("Favorites", "المفضلة"))
                mediaId.startsWith(PL_PREFIX) ->
                    Library.playlists.value.firstOrNull { it.id == mediaId.removePrefix(PL_PREFIX) }?.let { folder(mediaId, it.name) }
                else -> HistoryStore.items.value.firstOrNull { it.uri == mediaId }?.let { fileItem(it) }
                    ?: Library.favorites.value.firstOrNull { it.entry.localUri == mediaId }?.entry?.let { entryItem(it) }
            } ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val all: List<MediaItem> = when {
                parentId == ROOT -> listOf(
                    folder(DOWNLOADS, L.t("Downloads", "التنزيلات")),
                    folder(PLAYLISTS, L.t("Playlists", "قوائم التشغيل")),
                    folder(FAVORITES, L.t("Favorites", "المفضلة")),
                )
                parentId == DOWNLOADS -> HistoryStore.items.value.filter { !it.uri.startsWith("http") }.map { fileItem(it) }
                parentId == PLAYLISTS -> Library.playlists.value.map { folder(PL_PREFIX + it.id, it.name) }
                parentId == FAVORITES -> Library.favorites.value.mapNotNull { it.entry.takeIf { e -> e.localUri != null }?.let { entryItem(it) } }
                parentId.startsWith(PL_PREFIX) ->
                    Library.playlists.value.firstOrNull { it.id == parentId.removePrefix(PL_PREFIX) }
                        ?.entries?.filter { it.localUri != null }?.map { entryItem(it) } ?: emptyList()
                else -> emptyList()
            }
            val from = (page * pageSize).coerceAtMost(all.size)
            val to = (from + pageSize).coerceAtMost(all.size)
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params))
        }
    }

    private fun folder(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build(),
        ).build()

    private fun fileItem(h: HistoryItem): MediaItem = MediaItem.Builder()
        .setMediaId(h.uri).setUri(h.uri)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(prettyName(h.name)).setArtist(siteOfItem(h)).setIsBrowsable(false).setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build(),
        ).build()

    private fun entryItem(e: PlEntry): MediaItem = MediaItem.Builder()
        .setMediaId(e.localUri ?: e.pageUrl).setUri(e.localUri ?: e.pageUrl)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(e.title).setArtist(e.uploader.ifBlank { e.site }).setIsBrowsable(false).setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build(),
        ).build()

    /**
     * The app was swiped away from the recent apps list. Music that is playing (a scheduled or Bluetooth playlist, or
     * anything else) keeps playing until its end; only a paused / finished player is shut down with its notification.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = PlayerHolder.peek()
        val playing = p != null && p.playWhenReady && p.mediaItemCount > 0 &&
            p.playbackState != androidx.media3.common.Player.STATE_ENDED
        if (!playing) {
            PlayerHolder.clear(this)
            stopSelf()
        }
    }

    override fun onDestroy() {
        // never leave sound playing without a notification to control it
        PlayerHolder.peek()?.pause()
        session?.release()   // the player itself stays alive in PlayerHolder
        session = null
        super.onDestroy()
    }
}
