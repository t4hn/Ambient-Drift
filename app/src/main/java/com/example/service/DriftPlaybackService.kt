package com.example.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.DriftApplication
import com.example.MainActivity
import com.example.R
import com.example.model.PlaybackStatus
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream

@UnstableApi
class DriftPlaybackService : MediaSessionService() {

    companion object {
        const val CHANNEL_ID = "drift_ambient_playback"
        const val ACTION_STOP_DRIFT = "com.example.action.STOP_DRIFT"
    }

    private lateinit var controller: DriftPlaybackController
    private lateinit var driftPlayer: DriftMediaPlayer
    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var noisyReceiverRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val artworkBytesCache = HashMap<Int, ByteArray>()

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                controller.onBecomingNoisy()
            }
        }
    }

    private val stopCommand = SessionCommand(ACTION_STOP_DRIFT, Bundle.EMPTY)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        controller = (application as DriftApplication).playbackController
        driftPlayer = DriftMediaPlayer(Looper.getMainLooper())

        val stopButton = CommandButton.Builder(CommandButton.ICON_STOP)
            .setDisplayName(getString(R.string.notification_action_stop))
            .setSessionCommand(stopCommand)
            .setCustomIconResId(R.drawable.ic_notification_stop)
            .build()

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.notification_channel_name)
            .build()
        notificationProvider.setSmallIcon(R.drawable.ic_notification_wave)
        setMediaNotificationProvider(notificationProvider)

        mediaSession = MediaSession.Builder(this, driftPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .setMediaButtonPreferences(listOf(stopButton))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controllerInfo: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                        .buildUpon()
                        .add(stopCommand)
                        .build()
                    val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                        .buildUpon()
                        .remove(Player.COMMAND_SEEK_TO_NEXT)
                        .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                        .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                        .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controllerInfo)
                        .setAvailableSessionCommands(sessionCommands)
                        .setAvailablePlayerCommands(playerCommands)
                        .setMediaButtonPreferences(listOf(stopButton))
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controllerInfo: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == ACTION_STOP_DRIFT) {
                        controller.stopAll()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return super.onCustomCommand(session, controllerInfo, customCommand, args)
                }
            })
            .build()

        controller.setMediaSessionListener {
            mainHandler.post {
                syncSystemResources()
                driftPlayer.notifyStateUpdated()
            }
        }
        syncSystemResources()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val state = controller.uiState.value
        if (!state.isPlayingOrFading) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        controller.setMediaSessionListener(null)
        unregisterNoisyReceiver()
        releaseWakeLock()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    private fun syncSystemResources() {
        val state = controller.uiState.value
        if (state.isPlayingOrFading) {
            acquireWakeLock()
            registerNoisyReceiver()
        } else {
            unregisterNoisyReceiver()
            releaseWakeLock()
            if (state.playbackStatus == PlaybackStatus.STOPPED) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DRIFT::AmbientPlaybackLock").apply {
                setReferenceCounted(false)
            }
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { wl ->
            if (wl.isHeld) {
                runCatching { wl.release() }
            }
        }
    }

    private fun registerNoisyReceiver() {
        if (!noisyReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                becomingNoisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            noisyReceiverRegistered = true
        }
    }

    private fun unregisterNoisyReceiver() {
        if (noisyReceiverRegistered) {
            runCatching { unregisterReceiver(becomingNoisyReceiver) }
            noisyReceiverRegistered = false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.notification_channel_desc)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun getArtworkBytes(drawableResId: Int): ByteArray? {
        return artworkBytesCache[drawableResId] ?: runCatching {
            val bmp = BitmapFactory.decodeResource(resources, drawableResId) ?: return null
            val scaled = Bitmap.createScaledBitmap(bmp, 320, 180, true)
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, stream)
            val bytes = stream.toByteArray()
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            artworkBytesCache[drawableResId] = bytes
            bytes
        }.getOrNull()
    }

    private inner class DriftMediaPlayer(looper: Looper) : SimpleBasePlayer(looper) {

        private val supportedCommands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                Player.COMMAND_GET_VOLUME
            )
            .build()

        fun notifyStateUpdated() {
            invalidateState()
        }

        override fun getState(): State {
            val ui = controller.uiState.value
            val scene = ui.activeScene
            val sceneTitle = scene?.let { getString(it.titleRes) } ?: getString(R.string.app_name)

            val statusLine = buildString {
                when (ui.playbackStatus) {
                    PlaybackStatus.PLAYING -> append(getString(R.string.mixer_status_playing))
                    PlaybackStatus.PAUSED -> append(getString(R.string.mixer_status_paused))
                    PlaybackStatus.FADING_OUT -> append(getString(R.string.mixer_status_fading_out))
                    PlaybackStatus.STOPPED -> append(getString(R.string.mixer_status_stopped))
                }
                if (ui.sleepTimer.isActive && !ui.sleepTimer.isFadingOut) {
                    val mins = ui.sleepTimer.remainingSeconds / 60
                    val secs = ui.sleepTimer.remainingSeconds % 60
                    append(" • ")
                    append(String.format("%02d:%02d", mins, secs))
                }
            }

            val artworkBytes = scene?.let { getArtworkBytes(it.artworkResId) }

            val metadata = MediaMetadata.Builder()
                .setTitle(sceneTitle)
                .setArtist(statusLine)
                .setAlbumTitle(getString(R.string.app_name))
                .apply {
                    if (artworkBytes != null) {
                        setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    }
                }
                .build()

            val mediaItem = MediaItem.Builder()
                .setMediaId(scene?.id ?: "drift_scene")
                .setMediaMetadata(metadata)
                .build()

            val itemData = MediaItemData.Builder(scene?.id ?: "drift_scene")
                .setMediaItem(mediaItem)
                .setMediaMetadata(metadata)
                .setIsSeekable(false)
                .build()

            val playbackState = when (ui.playbackStatus) {
                PlaybackStatus.PLAYING, PlaybackStatus.FADING_OUT, PlaybackStatus.PAUSED -> Player.STATE_READY
                PlaybackStatus.STOPPED -> Player.STATE_IDLE
            }

            val playWhenReady = ui.playbackStatus == PlaybackStatus.PLAYING ||
                ui.playbackStatus == PlaybackStatus.FADING_OUT

            val audioAttrs = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()

            return State.Builder()
                .setAvailableCommands(supportedCommands)
                .setAudioAttributes(audioAttrs)
                .setPlaylist(listOf(itemData))
                .setPlaylistMetadata(metadata)
                .setCurrentMediaItemIndex(0)
                .setPlaybackState(playbackState)
                .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setVolume(ui.masterVolume)
                .build()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            if (playWhenReady) {
                controller.playAll()
            } else {
                controller.pauseAll()
            }
            return Futures.immediateVoidFuture()
        }

        override fun handleStop(): ListenableFuture<*> {
            controller.stopAll()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return Futures.immediateVoidFuture()
        }
    }
}
