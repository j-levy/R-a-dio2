package io.r_a_d.radio2

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.*
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.lifecycle.Observer
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import androidx.preference.PreferenceManager
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.MediaMetadata
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.metadata.Metadata
import com.google.android.exoplayer2.metadata.icy.IcyHeaders
import com.google.android.exoplayer2.metadata.icy.IcyInfo
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.TrackGroup
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.Util.getUserAgent
import io.r_a_d.radio2.alarm.RadioAlarm
import io.r_a_d.radio2.alarm.RadioSleeper
import io.r_a_d.radio2.playerstore.PlayerStore
import java.lang.Boolean.FALSE
import java.util.*
import kotlin.math.exp
import kotlin.math.ln
import kotlin.system.exitProcess


class RadioService : MediaBrowserServiceCompat() {

    private val radioTag = "======RadioService====="
    private lateinit var nowPlayingNotification: NowPlayingNotification
    private val radioServiceId = 1
    private var numberOfSongs = 0
    private val apiTicker: Timer = Timer()
    private var isAlarmStopped: Boolean = false

    // Define the broadcast receiver to handle any broadcasts
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action != null && action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                val i = Intent(context, RadioService::class.java)
                i.putExtra("action", Actions.STOP.name)
                context.startService(i)
            }
            if (action != null && action == Intent.ACTION_HEADSET_PLUG)
            {
                var headsetPluggedIn = false

                // In the Intent state there's the value whether the headphones are plugged or not.
                // This *should* work in any case...
                when (intent.getIntExtra("state", -1)) {
                0 -> {
                    Log.d(tag, radioTag + "Headset is unplugged")
                }
                1 -> {
                    Log.d(tag, radioTag + "Headset is plugged")
                    headsetPluggedIn = true && PreferenceManager.getDefaultSharedPreferences(context).getBoolean("autoStartOnPlug", false)
                }
                else -> {
                    Log.d(tag, radioTag + "I have no idea what the headset state is")
                }
                }
                /*
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                {
                    val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    for (d in devices)
                    {
                        if (d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
                            headsetPluggedIn = true
                    }
                }
                else
                {
                    Log.d(tag, radioTag + "Can't get state?")
                }

                 */
                if((mediaSession.controller.playbackState.state == PlaybackStateCompat.STATE_STOPPED
                    || mediaSession.controller.playbackState.state == PlaybackStateCompat.STATE_PAUSED)
                    && headsetPluggedIn)
                    beginPlaying()
            }
        }
    }

    // ##################################################
    // ################### OBSERVERS ####################
    // ##################################################

    private val titleObserver: Observer<String> = Observer {
        if (PlayerStore.instance.playbackState.value == PlaybackStateCompat.STATE_PLAYING)
        {
            Log.d(tag, radioTag + "SONG CHANGED AND PLAYING")
            // we activate latency compensation only if it's been at least 2 songs...
            when {
                PlayerStore.instance.isStreamDown -> {
                    // if we reach here, it means that the observer has been called by a new song and that the stream was down previously.
                    // so the stream is now back to normal.
                    PlayerStore.instance.isStreamDown = false
                    PlayerStore.instance.initApi()
                }
                PlayerStore.instance.currentSong.title.value == getString(R.string.ed) -> {
                    PlayerStore.instance.isStreamDown = true
                }
                else -> {
                    PlayerStore.instance.fetchApi(numberOfSongs >= 2)
                }
            }
        }  else {
            if (PlayerStore.instance.currentSong != PlayerStore.instance.currentSongBackup
                && it != noConnectionValue)
            {
                PlayerStore.instance.updateLp()
                PlayerStore.instance.fetchApi()
                Log.d(tag, "updated queue/lp while player not playing\ncurrent=${PlayerStore.instance.currentSong}\nbackup=${PlayerStore.instance.currentSongBackup}")
            }

        }

        val d = (PlayerStore.instance.currentSong.stopTime.value ?: 0) - (PlayerStore.instance.currentSong.startTime.value ?: 0)
        val duration = d

        Log.d(radioTag, "picture observer, duration: $duration, playbackpos = ${mediaSession.controller.playbackState?.position}")
        metadataBuilder
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, PlayerStore.instance.currentSong.title.value)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, PlayerStore.instance.currentSong.artist.value)
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, "id" + Random().nextInt(999))
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, PlayerStore.instance.streamerPicture.value)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, PlayerStore.instance.streamerPicture.value)
        // .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)

        mediaSession.setMetadata(metadataBuilder.build())

        nowPlayingNotification.update(this, mediaSession = mediaSession)
    }

    private val volumeObserver: Observer<Int> = Observer {
        setVolume(it)
    }

    private val isMutedObserver: Observer<Boolean> = Observer {
        setVolume(if (it) null else -1)
    }

    private val isPlayingObserver: Observer<Boolean> = Observer {
        if (it)
            beginPlaying()
        else
            stopPlaying()
    }

    private val startTimeObserver = Observer<Long> {
        // We're listening to startTime to determine if we have to update Queue and Lp.
        // this is because startTime is set by the API and never by the ICY, so both cases are covered (playing and stopped)
        // should be OK even when a new streamer comes in.
        if (it != PlayerStore.instance.currentSongBackup.startTime.value) // we have a new song
        {
            PlayerStore.instance.updateLp()
            PlayerStore.instance.updateQueue()
        }
    }

    private val streamerObserver = Observer<String> {
        PlayerStore.instance.initApi()
        nowPlayingNotification.update(this, mediaSession = mediaSession) // should update the streamer icon
    }

    private val streamerPictureObserver = Observer<Bitmap> {
        // Bug from Android 13: the notification needs another update from the mediaSession with different metadata content
        // to update the notification picture. At the same time, we update the duration.

        metadataBuilder
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, PlayerStore.instance.currentSong.title.value)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, PlayerStore.instance.currentSong.artist.value + " ")
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, "id" + Random().nextInt(999))
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, PlayerStore.instance.streamerPicture.value)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, PlayerStore.instance.streamerPicture.value)


        mediaSession.setMetadata(metadataBuilder.build())
        nowPlayingNotification.update(this, mediaSession = mediaSession)
    }

    // ##################################################
    // ############## LIFECYCLE CALLBACKS ###############
    // ##################################################

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.sendResult(null)
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        // Clients can connect, but you can't browse internet radio
        // so onLoadChildren returns nothing. This disables the ability to browse for content.
        return BrowserRoot(getString(R.string.MEDIA_ROOT_ID), null)
    }

    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()

        preferenceStore = PreferenceManager.getDefaultSharedPreferences(this)

        // Define managers
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        // telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        //define the audioFocusRequest
        val audioFocusRequestBuilder = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
        audioFocusRequestBuilder.setOnAudioFocusChangeListener(focusChangeListener)
        val audioAttributes = AudioAttributesCompat.Builder()
        audioAttributes.setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
        audioAttributes.setUsage(AudioAttributesCompat.USAGE_MEDIA)
        audioFocusRequestBuilder.setAudioAttributes(audioAttributes.build())
        audioFocusRequest = audioFocusRequestBuilder.build()

        // This stuff is for the broadcast receiver
        val filter = IntentFilter()
        filter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        filter.addAction(Intent.ACTION_HEADSET_PLUG)
        registerReceiver(receiver, filter)

        // setup media player
        setupMediaPlayer()
        createMediaSession()

        nowPlayingNotification = NowPlayingNotification(
            notificationChannelId = this.getString(R.string.nowPlayingChannelId),
            notificationChannel = R.string.nowPlayingNotificationChannel,
            notificationId = 1,
            notificationImportance = NotificationCompat.PRIORITY_LOW
        )
        nowPlayingNotification.create(this, mediaSession)


        PlayerStore.instance.streamerName.observeForever(streamerObserver)
        PlayerStore.instance.currentSong.title.observeForever(titleObserver)
        PlayerStore.instance.currentSong.startTime.observeForever(startTimeObserver)
        PlayerStore.instance.volume.observeForever(volumeObserver)
        PlayerStore.instance.isPlaying.observeForever(isPlayingObserver)
        PlayerStore.instance.isMuted.observeForever(isMutedObserver)
        PlayerStore.instance.streamerPicture.observeForever(streamerPictureObserver)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                radioServiceId,
                nowPlayingNotification.notification,
                FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(
                radioServiceId,
                nowPlayingNotification.notification
            )
        }

        // start ticker for when the player is stopped
        val periodString = PreferenceManager.getDefaultSharedPreferences(this).getString("fetchPeriod", "10") ?: "10"
        val period: Long = Integer.parseInt(periodString).toLong()
        if (period > 0)
            apiTicker.schedule(ApiFetchTick(), 0, period * 1000)

        PlayerStore.instance.isServiceStarted.value = true
        Log.d(tag, radioTag + "created")
    }

    private val handler = Handler()
    class LowerVolumeRunnable : Runnable {
        override fun run() {
            PlayerStore.instance.volume.postValue(
                (PlayerStore.instance.volume.value!!.toFloat() * (9f / 10f)).toInt()
            ) // the setVolume is called by the volumeObserver in RadioService (on main thread for ExoPlayer!)
        }
    }
    private val lowerVolumeRunnable = LowerVolumeRunnable()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getStringExtra("action") == null)
            return super.onStartCommand(intent, flags, startId)

        if (MediaButtonReceiver.handleIntent(mediaSession, intent) != null)
            return super.onStartCommand(intent, flags, startId)

        when (intent.getStringExtra("action")) {
            Actions.PLAY.name -> beginPlaying()
            Actions.STOP.name -> { setVolume(PlayerStore.instance.volume.value); stopPlaying() } // setVolume is here to reset the volume to the user's preference when the alarm (that sets volume to 100) is dismissed
            Actions.PAUSE.name -> { setVolume(PlayerStore.instance.volume.value); pausePlaying() }
            Actions.VOLUME.name -> setVolume(intent.getIntExtra("value", 100))
            Actions.KILL.name -> {stopForeground(true); stopSelf(); return Service.START_NOT_STICKY}
            Actions.NOTIFY.name -> nowPlayingNotification.update(this, mediaSession = mediaSession)
            Actions.PLAY_OR_FALLBACK.name -> beginPlayingOrFallback()
            Actions.FADE_OUT.name -> {
                for (i in 1 until 30) // we schedule 30 "LowerVolumeRunnable" every 2 seconds (i * 2)
                {
                    // I couldn't find how to send multiple times the same PendingIntent using AlarmManager, so I relied on Handler instead.
                    // I think there's no guarantee of exact time with the Handler, especially when the device is in deep sleep,
                    // But when I force-set the Deep Sleep mode with ADB, it worked fine, so I'll leave it as this.
                    // BUT! SOMETIMES IT DIDN'T WORK AND I DON'T KNOW WHY.
                    // I hope that moving the handler in the RadioService would solve the issue and trigger it correctly.
                    handler.postDelayed(lowerVolumeRunnable, (i * 2 * 1000).toLong())
                }
            }
            Actions.CANCEL_FADE_OUT.name -> { handler.removeCallbacks(lowerVolumeRunnable) }
            Actions.SNOOZE.name -> { RadioAlarm.instance.snooze(this) }
        }
        Log.d(tag, radioTag + "intent received : " + intent.getStringExtra("action"))
        super.onStartCommand(intent, flags, startId)
        // The service must be re-created if it is destroyed by the system. This allows the user to keep actions like Bluetooth and headphones plug available.
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent) {
        if (mediaSession.controller.playbackState.state != PlaybackStateCompat.STATE_PLAYING) {
            nowPlayingNotification.clear()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
        Log.d(tag, radioTag + "task removed")
    }

    override fun onDestroy() {
        super.onDestroy()
        player.stop()
        player.release()
        unregisterReceiver(receiver)
        PlayerStore.instance.currentSong.title.removeObserver(titleObserver)
        PlayerStore.instance.currentSong.startTime.removeObserver(startTimeObserver)
        PlayerStore.instance.volume.removeObserver(volumeObserver)
        PlayerStore.instance.isPlaying.removeObserver(isPlayingObserver)
        PlayerStore.instance.isMuted.removeObserver(isMutedObserver)
        PlayerStore.instance.streamerPicture.removeObserver(streamerPictureObserver)


        mediaSession.isActive = false
        mediaSession.setMediaButtonReceiver(null)

        mediaSession.release()

        PlayerStore.instance.isServiceStarted.value = false
        PlayerStore.instance.isInitialized = false

        PreferenceManager.getDefaultSharedPreferences(this).edit {
            this.putBoolean("isSleeping", false)
            this.commit()
        }
        RadioSleeper.instance.cancelSleep(this, isClosing = true)

        apiTicker.cancel() // stops the timer.
        Log.d(tag, radioTag + "destroyed")
        // if the service is destroyed, the application had become useless.
        exitProcess(0)
    }

    // ########################################
    // ######## AUDIO FOCUS MANAGEMENT ########
    //#########################################

    // Define the managers
    private var telephonyManager: TelephonyManager? = null
    private lateinit var audioManager: AudioManager
    private lateinit var audioFocusRequest: AudioFocusRequestCompat

    private val phoneStateListener = object : PhoneStateListener() {
        @Deprecated("Deprecated in Java")
        override fun onCallStateChanged(state: Int, incomingNumber: String) {
            super.onCallStateChanged(state, incomingNumber)

            if (state != TelephonyManager.CALL_STATE_IDLE) {
                setVolume(0)
            } else {
                setVolume(PlayerStore.instance.volume.value!!)
            }
        }
    }

    // Define the listener that will control what happens when focus is changed
    private val focusChangeListener =
        AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setVolume((0.20f * PlayerStore.instance.volume.value!!).toInt()) //20% of current volume.
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> setVolume(0)
                AudioManager.AUDIOFOCUS_LOSS -> stopPlaying()
                AudioManager.AUDIOFOCUS_GAIN -> setVolume(PlayerStore.instance.volume.value!!)
                else -> {}
            }
        }

    // ########################################
    // ######## MEDIA PLAYER / SESSION ########
    // ########################################

    private lateinit var mediaSession : MediaSessionCompat
    private lateinit var playbackStateBuilder: PlaybackStateCompat.Builder
    private lateinit var metadataBuilder: MediaMetadataCompat.Builder
    private lateinit var player: ExoPlayer
    private lateinit var streamMediaItem: MediaItem
    private lateinit var fallbackMediaItem: MediaItem
    private lateinit var radioMediaSource: ProgressiveMediaSource
    private lateinit var fallbackMediaSource: ProgressiveMediaSource

    private fun setupMediaPlayer(){

        val minBufferMillis = 15 * 1000 // Default value
        val maxBufferMillis = 50 * 1000 // Default value
        val bufferForPlayback = 4 * 1000 // Default is 2.5s.
        // Increasing it makes it more robust to short connection loss, at the expense of latency when we press Play. 4s seems reasonable to me.
        val bufferForPlaybackAfterRebuffer = 7 * 1000 // Default is 5s.

        val loadControl = DefaultLoadControl.Builder().apply {
            setBufferDurationsMs(minBufferMillis, maxBufferMillis, bufferForPlayback, bufferForPlaybackAfterRebuffer)
        }.build()

        val playerBuilder = ExoPlayer.Builder(this)
        playerBuilder.setLoadControl(loadControl)
        player = playerBuilder.build()

        // this listener allows to reset numberOfSongs if the connection is lost.
        player.addListener(exoPlayerEventListener)

        // Produces DataSource instances through which media data is loaded.
        val dataSourceFactory = DefaultDataSourceFactory(
            this,
            getUserAgent(this, getString(R.string.app_name))
        )
        // This is the MediaSource representing the media to be played.
        radioMediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(getString(R.string.STREAM_URL_RADIO)))

        fallbackMediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri("file:///android_asset/tear_rain_no_internet_connection.ogg"))
    }

    private fun createMediaSession() {
        mediaSession = MediaSessionCompat(this, "RadioMediaSession")
        // Deprecated flags
        // mediaSession.setFlags(MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS and MediaSession.FLAG_HANDLES_MEDIA_BUTTONS)
        mediaSession.isActive = true
        mediaSession.setCallback(mediaSessionCallback)
        playbackStateBuilder = PlaybackStateCompat.Builder()
        playbackStateBuilder.setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE)
            .setState(PlaybackStateCompat.STATE_STOPPED, 0, 1.0f, SystemClock.elapsedRealtime())

        metadataBuilder = MediaMetadataCompat.Builder()
        mediaSession.setPlaybackState(playbackStateBuilder.build())
    }

    // ########################################
    // ######### SERVICE START/STOP ###########
    // ########################################

    // this function is playing the stream if available, or a default sound if there's a problem.
    private fun beginPlayingOrFallback()
    {
        PlayerStore.instance.volume.value = PreferenceManager.getDefaultSharedPreferences(this).getInt("alarmVolume", 100)
        beginPlaying(isRinging = true, isFallback = false)
        val wait: (Any?) -> Any = {
            /*
            Here we lower the isAlarmStopped flag and we wait for 17s. (seems like 12 could be a bit too short since I increased the buffer!!)
            If the user stops the alarm (by calling an intent), the isAlarmStopped flag will be raised.
             */
            isAlarmStopped = false // reset the flag
            var i = 0
            while (i < 17)
            {
                Thread.sleep(1000)
                i++
            }
        }
        val post: (Any?) -> Unit = {
            // we verify : if the player is not playing, and if the user didn't stop it, it means that there's a network issue.
            // So we use the fallback sound to wake up the user!!
            // (note: player.isPlaying is only accessible on main thread, so we can't check in the wait() lambda)
            if (!player.isPlaying && !isAlarmStopped)
                beginPlaying(isRinging = true, isFallback = true)
        }
        Async(wait, post)
    }

    fun beginPlaying(isRinging: Boolean = false, isFallback: Boolean = false)
    {
        //define the audioFocusRequest
        val audioFocusRequestBuilder = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
        audioFocusRequestBuilder.setOnAudioFocusChangeListener(focusChangeListener)
        val audioAttributes = AudioAttributesCompat.Builder()
        audioAttributes.setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
        if (isRinging)
        {
            audioAttributes.setUsage(AudioAttributesCompat.USAGE_ALARM)
            audioFocusRequestBuilder.setAudioAttributes(audioAttributes.build())
            audioFocusRequest = audioFocusRequestBuilder.build()
            val audioAttributes2 = AudioAttributes
                .Builder()
                .setContentType(C.CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_ALARM)
                .build()
            player.setAudioAttributes(audioAttributes2, FALSE)
        } else {
            isAlarmStopped = true // if we're not ringing and it tries playing, it means the user opened the app somehow
            audioAttributes.setUsage(AudioAttributesCompat.USAGE_MEDIA)
            audioFocusRequestBuilder.setAudioAttributes(audioAttributes.build())
            audioFocusRequest = audioFocusRequestBuilder.build()
            val audioAttributes2 = AudioAttributes
                .Builder()
                .setContentType(C.CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build()
            player.setAudioAttributes(audioAttributes2, FALSE)
        }
        // the old requestAudioFocus is deprecated on API26+. Using AudioManagerCompat library for consistent code across versions
        val result = AudioManagerCompat.requestAudioFocus(audioManager, audioFocusRequest)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return
        }
        if (mediaSession.controller.playbackState.state == PlaybackStateCompat.STATE_PLAYING && !isRinging && isAlarmStopped)
        {
            return //nothing to do here
        }

        PlayerStore.instance.playbackState.value = PlaybackStateCompat.STATE_PLAYING

        // Reinitialize media player. Otherwise the playback doesn't resume when beginPlaying. Dunno why.
        // Prepare the player with the source.
        if (isFallback)
        {
            player.prepare(fallbackMediaSource)
            player.repeatMode = ExoPlayer.REPEAT_MODE_ALL
        }
        else {
            player.prepare(radioMediaSource)
            player.repeatMode = ExoPlayer.REPEAT_MODE_OFF
        }

        // START PLAYBACK, LET'S ROCK
        player.playWhenReady = true
        nowPlayingNotification.update(this, isUpdatingNotificationButton =  true, isRinging = isRinging, mediaSession = mediaSession)

        playbackStateBuilder.setState(
            PlaybackStateCompat.STATE_PLAYING,
            0,
            1.0f,
            SystemClock.elapsedRealtime()
        )
        mediaSession.setPlaybackState(playbackStateBuilder.build())


        Log.d(tag, radioTag + "begin playing")
    }

    private fun pausePlaying()
    {
        stopPlaying()
    }

    // stop playing but keep the notification.
    fun stopPlaying()
    {
        if (mediaSession.controller.playbackState.state == PlaybackStateCompat.STATE_STOPPED)
            return // nothing to do here

        if (PlayerStore.instance.playbackState.value == PlaybackStateCompat.STATE_PLAYING)
            isAlarmStopped = true

        PlayerStore.instance.playbackState.value = PlaybackStateCompat.STATE_STOPPED

        // STOP THE PLAYBACK
        player.stop()

        nowPlayingNotification.update(this, true, mediaSession = mediaSession)
        playbackStateBuilder.setState(
            PlaybackStateCompat.STATE_STOPPED,
            0,
            1.0f,
            SystemClock.elapsedRealtime()
        )
        Log.d(tag, radioTag + "stopped")

        mediaSession.setPlaybackState(playbackStateBuilder.build())
    }

    fun setVolume(vol: Int?) {
        var v = vol
        when(v)
        {
            null -> { player.volume = 0f ; return } // null means "mute"
            -1 -> v = PlayerStore.instance.volume.value // -1 means "restore previous volume"
        }

        // re-shaped volume setter with a logarithmic (ln) function.
        // I think it sounds more natural this way. Adjust coefficient to change the function shape.
        // visualize it on any graphic calculator if you're unsure.
        val c : Float = 2.toFloat()
        val x : Float = v!!.toFloat()/100
        player.volume = -(1/c)* ln(1-(1- exp(-c))*x)
    }

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            beginPlaying()
        }

        override fun onPause() {
            pausePlaying()
        }

        override fun onStop() {
            stopPlaying()
        }

        override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
            // explicit handling of Media Buttons (for example bluetooth commands)
            // The hardware key on a corded headphones are handled in the MainActivity (for <API21)
            if (PlayerStore.instance.isServiceStarted.value!!) {
                val keyEvent =
                    mediaButtonEvent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (keyEvent == null || ((keyEvent.action) != KeyEvent.ACTION_DOWN)) {
                    return false
                }

                when (keyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                        //// Is this some kind of debouncing ? I'm not sure.
                        //if (keyEvent.repeatCount > 0) {
                        //    return false
                        //} else {
                        if (PlayerStore.instance.playbackState.value == PlaybackStateCompat.STATE_PLAYING)
                            pausePlaying()
                        else
                            beginPlaying()
                        //}
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_STOP -> stopPlaying()
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> pausePlaying()
                    KeyEvent.KEYCODE_MEDIA_PLAY -> beginPlaying()
                    else -> return false // these actions are the only ones we acknowledge.
                }

            }
            return false
        }
    }

    private val exoPlayerEventListener = object : Player.Listener {
        // player.addMetadataOutput
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            super.onMediaMetadataChanged(mediaMetadata)

            // for (i in 0 until it.length()) {
                // val entry  = it.get(i)
                val entry =  mediaMetadata
                numberOfSongs++
                val data = entry.title
            if (data != null)
            {
                PlayerStore.instance.currentSong.setTitleArtist(data.toString())
                val d : Long = ((PlayerStore.instance.currentSong.stopTime.value?.minus(PlayerStore.instance.currentSong.startTime.value!!) ?: 0) / 1000)
                val duration = if (d > 0) d - (PlayerStore.instance.latencyCompensator) else 0
                metadataBuilder.putString(
                    MediaMetadataCompat.METADATA_KEY_TITLE,
                    PlayerStore.instance.currentSong.title.value
                )
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, PlayerStore.instance.currentSong.artist.value)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, PlayerStore.instance.currentSong.title.value)
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, "id" + Random().nextInt(999))
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, PlayerStore.instance.streamerPicture.value)

                mediaSession.setMetadata(metadataBuilder.build())

                val intent = Intent("com.android.music.metachanged")
                intent.putExtra("artist", PlayerStore.instance.currentSong.artist.value)
                intent.putExtra("track", PlayerStore.instance.currentSong.title.value)
                intent.putExtra("duration", duration)
                intent.putExtra("position", 0)
                sendBroadcast(intent)
            }

        }

        override fun onMetadata(metadata: Metadata) {
            super.onMetadata(metadata)
        }

        override fun onTracksChanged(tracks: Tracks) {
            super.onTracksChanged(tracks)
        }

        @Deprecated("Deprecated in Java")
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
            super.onPlayerStateChanged(playWhenReady, playbackState)
            numberOfSongs = 0
            var state = ""
            when(playbackState)
            {
                Player.STATE_BUFFERING -> state = "Player.STATE_BUFFERING"
                Player.STATE_IDLE -> {
                    state = "Player.STATE_IDLE"
                    // inform the PlayerStore that the playback has stopped. This enables the ticker, triggers API fetch, and updates UI in no-network state.
                    if (PlayerStore.instance.playbackState.value != PlaybackStateCompat.STATE_STOPPED)
                    {
                        PlayerStore.instance.playbackState.postValue(PlaybackStateCompat.STATE_STOPPED)
                        PlayerStore.instance.isPlaying.postValue(false)
                    }
                }
                Player.STATE_ENDED -> state = "Player.STATE_ENDED"
                Player.STATE_READY -> state = "Player.STATE_READY"
            }
            Log.d(tag, radioTag + "Player changed state: ${state}. numberOfSongs reset. PlayWhenReady=${playWhenReady}")
        }
    }



}
