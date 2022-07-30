package com.zionhuang.music.playback

import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.ResultReceiver
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat.*
import android.util.Pair
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.PlaybackException.ERROR_CODE_REMOTE_ERROR
import com.google.android.exoplayer2.Player.*
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueEditor.*
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.ResolvingDataSource
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.*
import com.zionhuang.innertube.models.QueueAddEndpoint.Companion.INSERT_AFTER_CURRENT_VIDEO
import com.zionhuang.innertube.models.QueueAddEndpoint.Companion.INSERT_AT_END
import com.zionhuang.music.R
import com.zionhuang.music.constants.MediaConstants.EXTRA_SONGS
import com.zionhuang.music.constants.MediaConstants.STATE_DOWNLOADED
import com.zionhuang.music.constants.MediaSessionConstants.ACTION_ADD_TO_LIBRARY
import com.zionhuang.music.constants.MediaSessionConstants.COMMAND_ADD_TO_QUEUE
import com.zionhuang.music.constants.MediaSessionConstants.COMMAND_PLAY_NEXT
import com.zionhuang.music.constants.MediaSessionConstants.COMMAND_SEEK_TO_QUEUE_ITEM
import com.zionhuang.music.constants.MediaSessionConstants.EXTRA_MEDIA_ID
import com.zionhuang.music.db.entities.Song
import com.zionhuang.music.extensions.*
import com.zionhuang.music.models.MediaData
import com.zionhuang.music.models.toMediaDescription
import com.zionhuang.music.playback.queues.EmptyQueue
import com.zionhuang.music.playback.queues.Queue
import com.zionhuang.music.repos.SongRepository
import com.zionhuang.music.repos.base.LocalRepository
import com.zionhuang.music.ui.activities.MainActivity
import com.zionhuang.music.utils.GlideApp
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO

/**
 * A wrapper around [ExoPlayer]
 */
class SongPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    notificationListener: PlayerNotificationManager.NotificationListener,
) : Listener {
    private val localRepository: LocalRepository = SongRepository

    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!

    private var currentQueue: Queue = EmptyQueue()
    private var queueJob: Job? = null

    private val _mediaSession = MediaSessionCompat(context, context.getString(R.string.app_name)).apply {
        isActive = true
    }
    val mediaSession: MediaSessionCompat get() = _mediaSession

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(ResolvingDataSource.Factory(
                DefaultDataSource.Factory(context)
            ) { dataSpec ->
                val mediaId = dataSpec.key ?: error("No media id")
                val localSong = runBlocking(IO) {
                    localRepository.getSongById(mediaId)
                }
                if (localSong?.downloadState == STATE_DOWNLOADED) {
                    return@Factory dataSpec.withUri(localRepository.getSongFile(mediaId).toUri())
                }
                kotlin.runCatching {
                    runBlocking(IO) {
                        YouTube.player(mediaId)
                    }
                }.mapCatching { playerResponse ->
                    if (playerResponse.playabilityStatus.status != "OK") {
                        throw PlaybackException(playerResponse.playabilityStatus.status, null, ERROR_CODE_REMOTE_ERROR)
                    }
                    playerResponse.streamingData?.adaptiveFormats
                        ?.filter { it.isAudio }
                        ?.maxByOrNull { it.bitrate * (if (connectivityManager.isActiveNetworkMetered) -1 else 1) }
                        ?.url
                        ?.toUri()
                        ?: throw PlaybackException("No stream available", null, ERROR_CODE_NO_STREAM)
                }.getOrThrow().let { uri ->
                    dataSpec.withUri(uri)
                }
            })
        )
        .build()
        .apply {
            addListener(this@SongPlayer)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
            setAudioAttributes(audioAttributes, true)
            setHandleAudioBecomingNoisy(true)
        }

    private var autoAddSong by context.preference(R.string.pref_auto_add_song, true)

    fun playQueue(queue: Queue) {
        queueJob?.cancel()
        currentQueue = queue
        player.clearMediaItems()

        scope.launch {
            val initialStatus = queue.getInitialStatus()
            player.setMediaItems(initialStatus.items)
            if (initialStatus.index > 0) player.seekToDefaultPosition(initialStatus.index)
            player.prepare()
            player.playWhenReady = true
        }
    }

    fun handleQueueAddEndpoint(endpoint: QueueAddEndpoint, item: Item) {
        scope.launch {
            val items = when (item) {
                is SongItem -> listOf(item.toMediaItem())
                is AlbumItem, is PlaylistItem -> withContext(IO) {
                    YouTube.getQueue(playlistId = endpoint.queueTarget.playlistId!!).mapNotNull {
                        (it as? SongItem)?.toMediaItem()
                    }
                }
                is ArtistItem -> return@launch
            }
            when (endpoint.queueInsertPosition) {
                INSERT_AFTER_CURRENT_VIDEO -> player.addMediaItems((if (player.mediaItemCount == 0) -1 else player.currentMediaItemIndex) + 1, items)
                INSERT_AT_END -> player.addMediaItems(items)
                else -> {}
            }
            player.prepare()
        }
    }

    private val mediaSessionConnector = MediaSessionConnector(mediaSession).apply {
        setPlayer(player)
        setPlaybackPreparer(object : MediaSessionConnector.PlaybackPreparer {
            override fun onCommand(player: Player, command: String, extras: Bundle?, cb: ResultReceiver?) = false

            override fun getSupportedPrepareActions() =
                ACTION_PREPARE_FROM_MEDIA_ID or ACTION_PREPARE_FROM_SEARCH or ACTION_PREPARE_FROM_URI or
                        ACTION_PLAY_FROM_MEDIA_ID or ACTION_PLAY_FROM_SEARCH or ACTION_PLAY_FROM_URI

            override fun onPrepare(playWhenReady: Boolean) {
                player.playWhenReady = playWhenReady
                player.prepare()
            }

            override fun onPrepareFromMediaId(mediaId: String, playWhenReady: Boolean, extras: Bundle?) {
                // TODO
            }

            override fun onPrepareFromSearch(query: String, playWhenReady: Boolean, extras: Bundle?) {
                // TODO
            }

            override fun onPrepareFromUri(uri: Uri, playWhenReady: Boolean, extras: Bundle?) {
                // TODO
            }
        })
        registerCustomCommandReceiver { player, command, extras, _ ->
            if (extras == null) return@registerCustomCommandReceiver false
            when (command) {
                COMMAND_SEEK_TO_QUEUE_ITEM -> {
                    val mediaId = extras.getString(EXTRA_MEDIA_ID)
                        ?: return@registerCustomCommandReceiver true
                    player.mediaItemIndexOf(mediaId)?.let {
                        player.seekToDefaultPosition(it)
                    }
                    true
                }
                COMMAND_PLAY_NEXT -> {
                    val songs = extras.getParcelableArray(EXTRA_SONGS)!!
                    player.addMediaItems(
                        (if (player.mediaItemCount == 0) -1 else player.currentMediaItemIndex) + 1,
                        songs.mapNotNull { (it as? MediaData)?.toMediaItem() }
                    )
                    player.prepare()
                    true
                }
                COMMAND_ADD_TO_QUEUE -> {
                    val songs = extras.getParcelableArray(EXTRA_SONGS)!!
                    player.addMediaItems(songs.mapNotNull { (it as? MediaData)?.toMediaItem() })
                    player.prepare()
                    true
                }
                else -> false
            }
        }
        setCustomActionProviders(context.createCustomAction(ACTION_ADD_TO_LIBRARY, R.string.custom_action_add_to_library, R.drawable.ic_library_add) { _, _, _ ->
            player.currentMetadata?.let {
                addToLibrary(it)
            }
        })
        setQueueNavigator { player, windowIndex -> player.getMediaItemAt(windowIndex).metadata.toMediaDescription() }
        setErrorMessageProvider { e ->
            return@setErrorMessageProvider Pair(ERROR_CODE_UNKNOWN_ERROR, e.localizedMessage)
        }
        setQueueEditor(object : MediaSessionConnector.QueueEditor {
            override fun onCommand(player: Player, command: String, extras: Bundle?, cb: ResultReceiver?): Boolean {
                if (COMMAND_MOVE_QUEUE_ITEM != command || extras == null) return false
                val from = extras.getInt(EXTRA_FROM_INDEX, C.INDEX_UNSET)
                val to = extras.getInt(EXTRA_TO_INDEX, C.INDEX_UNSET)
                if (from != C.INDEX_UNSET && to != C.INDEX_UNSET) {
                    player.moveMediaItem(from, to)
                }
                return true
            }

            override fun onAddQueueItem(player: Player, description: MediaDescriptionCompat) =
                player.addMediaItem(description.toMediaItem())

            override fun onAddQueueItem(player: Player, description: MediaDescriptionCompat, index: Int) =
                player.addMediaItem(index, description.toMediaItem())

            override fun onRemoveQueueItem(player: Player, description: MediaDescriptionCompat) {
                player.mediaItemIndexOf(description.mediaId)?.let { i ->
                    player.removeMediaItem(i)
                }
            }
        })
    }

    private val playerNotificationManager = PlayerNotificationManager.Builder(context, NOTIFICATION_ID, CHANNEL_ID)
        .setMediaDescriptionAdapter(object : PlayerNotificationManager.MediaDescriptionAdapter {
            override fun getCurrentContentTitle(player: Player): CharSequence =
                player.currentMetadata?.title.orEmpty()

            override fun getCurrentContentText(player: Player): CharSequence? =
                player.currentMetadata?.artist

            override fun getCurrentLargeIcon(player: Player, callback: PlayerNotificationManager.BitmapCallback): Bitmap? {
                val url = player.currentMetadata?.artwork
                val bitmap = GlideApp.with(context)
                    .asBitmap()
                    .load(url)
                    .onlyRetrieveFromCache(true)
                    .getBlocking()
                if (bitmap == null) {
                    GlideApp.with(context)
                        .asBitmap()
                        .load(url)
                        .onlyRetrieveFromCache(false)
                        .into(object : CustomTarget<Bitmap>() {
                            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) = callback.onBitmap(resource)
                            override fun onLoadCleared(placeholder: Drawable?) = Unit
                        })
                }
                return bitmap
            }

            override fun createCurrentContentIntent(player: Player): PendingIntent? =
                PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), FLAG_IMMUTABLE)
        })
        .setChannelNameResourceId(R.string.channel_name_playback)
        .setNotificationListener(notificationListener)
        .build()
        .apply {
            setPlayer(player)
            setMediaSessionToken(mediaSession.sessionToken)
            setSmallIcon(R.drawable.ic_notification)
        }

    /**
     * Auto load more
     */
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == MEDIA_ITEM_TRANSITION_REASON_REPEAT ||
            player.playbackState == STATE_IDLE ||
            player.mediaItemCount - player.currentMediaItemIndex > 5 ||
            !currentQueue.hasNextPage()
        ) return
        scope.launch {
            player.addMediaItems(currentQueue.nextPage())
        }
    }

    override fun onPositionDiscontinuity(oldPosition: PositionInfo, newPosition: PositionInfo, @Player.DiscontinuityReason reason: Int) {
        if (reason == DISCONTINUITY_REASON_AUTO_TRANSITION && autoAddSong) {
            oldPosition.mediaItem?.metadata?.let {
                addToLibrary(it)
            }
        }
    }

    override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
        if (playbackState == STATE_ENDED && autoAddSong) {
            player.currentMetadata?.let {
                addToLibrary(it)
            }
        }
    }

    private fun addToLibrary(mediaData: MediaData) {
        scope.launch {
            localRepository.addSong(Song(
                id = mediaData.id,
                title = mediaData.title,
                artistName = mediaData.artist,
                duration = if (player.duration != C.TIME_UNSET) (player.duration / 1000).toInt() else -1,
                artworkType = mediaData.artworkType
            ))
        }
    }

    fun release() {
        mediaSession.apply {
            isActive = false
            release()
        }
        mediaSessionConnector.setPlayer(null)
        playerNotificationManager.setPlayer(null)
        player.release()
    }

    fun setPlayerView(playerView: StyledPlayerView) {
        playerView.player = player
    }

    companion object {
        const val TAG = "SongPlayer"
        const val CHANNEL_ID = "music_channel_01"
        const val NOTIFICATION_ID = 888

        const val ERROR_CODE_NO_STREAM = 1000001
    }
}