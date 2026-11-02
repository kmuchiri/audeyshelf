package com.audeyshelf.audiobook.media;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media.session.MediaButtonReceiver;

import com.audeyshelf.audiobook.R;
import com.audeyshelf.audiobook.data.ResumeManager;
import com.audeyshelf.audiobook.data.SettingsManager;
import com.audeyshelf.audiobook.model.Audiobook;
import com.audeyshelf.audiobook.model.Chapter;
import com.audeyshelf.audiobook.net.AudiobookshelfClient;
import com.audeyshelf.audiobook.ui.PlayerActivity;
import com.audeyshelf.audiobook.util.AudioMetadataHelper;
import com.audeyshelf.audiobook.util.AudioSanitizer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Foreground service managing MediaPlayer lifecycle, audio focus, lock-screen controls,
 * chapter tracking, and notification.
 * Follows spec.md Sections 6, 7, and 9.
 */
public class PlaybackService extends Service implements
        MediaPlayer.OnPreparedListener,
        MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener,
        MediaPlayer.OnSeekCompleteListener,
        AudioManager.OnAudioFocusChangeListener {

    private static final String TAG = "PlaybackService";

    public static final String ACTION_PLAY = "com.audeyshelf.audiobook.ACTION_PLAY";
    public static final String ACTION_PAUSE = "com.audeyshelf.audiobook.ACTION_PAUSE";
    public static final String ACTION_TOGGLE = "com.audeyshelf.audiobook.ACTION_TOGGLE";
    public static final String ACTION_REWIND = "com.audeyshelf.audiobook.ACTION_REWIND";
    public static final String ACTION_FORWARD = "com.audeyshelf.audiobook.ACTION_FORWARD";
    public static final String ACTION_PREV_CHAPTER = "com.audeyshelf.audiobook.ACTION_PREV_CHAPTER";
    public static final String ACTION_NEXT_CHAPTER = "com.audeyshelf.audiobook.ACTION_NEXT_CHAPTER";
    public static final String ACTION_STOP = "com.audeyshelf.audiobook.ACTION_STOP";
    public static final String ACTION_REFRESH_NOTIFICATION = "com.audeyshelf.audiobook.ACTION_REFRESH_NOTIFICATION";

    private static final String CHANNEL_ID = "ember_playback_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final long PROGRESS_INTERVAL_MS = 1000L;
    private static final long SAVE_INTERVAL_MS = 30000L; // 30s periodic save (spec.md 4.1)
    private static final long SYNC_INTERVAL_MS = 60000L; // 60s periodic sync with server
    private static final long SYNC_THRESHOLD_MS = 2000L; // 2s threshold for progress differences

    private final IBinder binder = new PlaybackBinder();
    private final List<PlaybackListener> listeners = new CopyOnWriteArrayList<>();

    private MediaPlayer mediaPlayer;
    private MediaSessionCompat mediaSession;
    private MediaButtonController mediaButtonController;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest; // API 26+

    private ResumeManager resumeManager;
    private SettingsManager settingsManager;
    private AudiobookshelfClient absClient;
    private final LocalStreamProxy localStreamProxy = new LocalStreamProxy();

    private Audiobook currentAudiobook;
    private List<Chapter> chapters = new ArrayList<>();
    private int currentChapterIndex = 0;
    private float currentSpeed = 1.0f;
    private boolean isPrepared = false;
    private boolean isForeground = false;
    private boolean resumeOnFocusGain = false;
    private boolean autoPlayOnPrepare = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null && isPrepared && mediaPlayer.isPlaying()) {
                long pos = mediaPlayer.getCurrentPosition();
                long dur = mediaPlayer.getDuration();
                updateChapterForPosition(pos);
                for (PlaybackListener l : listeners) {
                    l.onProgressUpdate(pos, dur);
                }
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
            }
            handler.postDelayed(this, PROGRESS_INTERVAL_MS);
        }
    };

    private final Runnable periodicSaveRunnable = new Runnable() {
        @Override
        public void run() {
            saveCurrentPosition();
            handler.postDelayed(this, SAVE_INTERVAL_MS);
        }
    };

    private final Runnable periodicSyncRunnable = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null && isPrepared && mediaPlayer.isPlaying()) {
                syncPlaybackProgressWithServer(false);
            }
            handler.postDelayed(this, SYNC_INTERVAL_MS);
        }
    };

    // Noisy receiver (headphone unplugged)
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pause();
            }
        }
    };
    private boolean isNoisyReceiverRegistered = false;

    // Sleep timer support
    private long sleepTimerEndTimeMs = 0;
    private final Runnable sleepTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (sleepTimerEndTimeMs > 0 && System.currentTimeMillis() >= sleepTimerEndTimeMs) {
                sleepTimerEndTimeMs = 0;
                pause();
            } else if (sleepTimerEndTimeMs > 0) {
                handler.postDelayed(this, 1000);
            }
        }
    };

    public class PlaybackBinder extends Binder {
        public PlaybackService getService() {
            return PlaybackService.this;
        }

        public void loadAudiobook(String filePath, boolean startPlaying) {
            PlaybackService.this.loadAudiobook(filePath, startPlaying);
        }

        public void play() {
            PlaybackService.this.play();
        }

        public void pause() {
            PlaybackService.this.pause();
        }

        public void stopPlayback() {
            PlaybackService.this.stopPlayback();
        }

        public void togglePlayPause() {
            PlaybackService.this.togglePlayPause();
        }

        public void seekTo(long positionMs) {
            PlaybackService.this.seekTo(positionMs);
        }

        public void skipForward(int seconds) {
            PlaybackService.this.skipForward(seconds);
        }

        public void skipBackward(int seconds) {
            PlaybackService.this.skipBackward(seconds);
        }

        public void nextChapter() {
            PlaybackService.this.nextChapter();
        }

        public void previousChapter() {
            PlaybackService.this.previousChapter();
        }

        public void seekToChapter(int index) {
            PlaybackService.this.seekToChapter(index);
        }

        public void setPlaybackSpeed(float speed) {
            PlaybackService.this.setPlaybackSpeed(speed);
        }

        public float getPlaybackSpeed() {
            return currentSpeed;
        }

        public boolean isPlaying() {
            return PlaybackService.this.isPlaying();
        }

        public Audiobook getCurrentAudiobook() {
            return currentAudiobook;
        }

        public Chapter getCurrentChapter() {
            if (chapters != null && currentChapterIndex >= 0 && currentChapterIndex < chapters.size()) {
                return chapters.get(currentChapterIndex);
            }
            return null;
        }

        public List<Chapter> getChapters() {
            return chapters;
        }

        public int getCurrentChapterIndex() {
            return currentChapterIndex;
        }

        public long getCurrentPosition() {
            return (mediaPlayer != null && isPrepared) ? mediaPlayer.getCurrentPosition() : 0;
        }

        public long getDuration() {
            return (mediaPlayer != null && isPrepared) ? mediaPlayer.getDuration() : (currentAudiobook != null ? currentAudiobook.getDurationMs() : 0);
        }

        public void addListener(PlaybackListener listener) {
            if (listener != null && !listeners.contains(listener)) {
                listeners.add(listener);
                // Immediately notify initial state
                listener.onPlaybackStateChanged(isPlaying());
                if (currentAudiobook != null) {
                    listener.onAudiobookLoaded(currentAudiobook, chapters);
                    listener.onChapterChanged(currentChapterIndex, getCurrentChapter());
                    listener.onProgressUpdate(getCurrentPosition(), getDuration());
                }
            }
        }

        public void removeListener(PlaybackListener listener) {
            if (listener != null) {
                listeners.remove(listener);
            }
        }

        public void startSleepTimer(int minutes) {
            PlaybackService.this.startSleepTimer(minutes);
        }

        public void cancelSleepTimer() {
            PlaybackService.this.cancelSleepTimer();
        }

        public boolean isSleepTimerActive() {
            return PlaybackService.this.isSleepTimerActive();
        }

        public int getRemainingSleepMinutes() {
            return PlaybackService.this.getRemainingSleepMinutes();
        }

        public void refreshNotification() {
            PlaybackService.this.refreshNotification();
        }

        public void syncWithServer() {
            PlaybackService.this.syncPlaybackProgressWithServer(true);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        resumeManager = new ResumeManager(this);
        settingsManager = new SettingsManager(this);
        absClient = new AudiobookshelfClient();
        currentSpeed = settingsManager.getPlaybackSpeed();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        createNotificationChannel();
        initMediaSession();
        initMediaPlayer();

        handler.post(progressRunnable);
        handler.post(periodicSaveRunnable);
        handler.postDelayed(periodicSyncRunnable, SYNC_INTERVAL_MS);

        // Register noisy receiver
        registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        isNoisyReceiverRegistered = true;
    }

    private void initMediaPlayer() {
        isPrepared = false;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.release();
            } catch (Exception ignored) {
            }
        }
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        mediaPlayer.setOnPreparedListener(this);
        mediaPlayer.setOnCompletionListener(this);
        mediaPlayer.setOnErrorListener(this);
        mediaPlayer.setOnSeekCompleteListener(this);
    }

    private void initMediaSession() {
        mediaButtonController = new MediaButtonController(this, settingsManager, new MediaButtonController.PlaybackController() {
            @Override
            public void onPlayPause() {
                togglePlayPause();
            }

            @Override
            public void onForward(int seconds) {
                skipForward(seconds);
            }

            @Override
            public void onRewind(int seconds) {
                skipBackward(seconds);
            }

            @Override
            public void onNextChapter() {
                nextChapter();
            }

            @Override
            public void onPreviousChapter() {
                previousChapter();
            }

            @Override
            public void onPauseOnly() {
                pause();
            }
        });

        ComponentName receiver = new ComponentName(this, MediaButtonReceiver.class);
        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mediaButtonIntent.setComponent(receiver);
        PendingIntent mbrIntent = PendingIntent.getBroadcast(this, 0, mediaButtonIntent, 0);
        mediaSession = new MediaSessionCompat(this, "AudiobookSession", receiver, mbrIntent);
        mediaSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        );
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                if (mediaButtonController != null && mediaButtonController.handleIntent(mediaButtonIntent)) {
                    return true;
                }
                return super.onMediaButtonEvent(mediaButtonIntent);
            }

            @Override
            public void onPlay() {
                play();
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onSkipToNext() {
                nextChapter();
            }

            @Override
            public void onSkipToPrevious() {
                previousChapter();
            }

            @Override
            public void onFastForward() {
                skipForward(settingsManager.getSkipDurationSeconds());
            }

            @Override
            public void onRewind() {
                skipBackward(settingsManager.getSkipDurationSeconds());
            }

            @Override
            public void onSeekTo(long pos) {
                seekTo(pos);
            }

            @Override
            public void onSetPlaybackSpeed(float speed) {
                setPlaybackSpeed(speed);
            }
        });
        mediaSession.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        MediaButtonReceiver.handleIntent(mediaSession, intent);

        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if (ACTION_PLAY.equals(action)) {
                play();
            } else if (ACTION_PAUSE.equals(action)) {
                pause();
            } else if (ACTION_TOGGLE.equals(action)) {
                togglePlayPause();
            } else if (ACTION_REWIND.equals(action)) {
                skipBackward(settingsManager.getSkipDurationSeconds());
            } else if (ACTION_FORWARD.equals(action)) {
                skipForward(settingsManager.getSkipDurationSeconds());
            } else if (ACTION_PREV_CHAPTER.equals(action)) {
                previousChapter();
            } else if (ACTION_NEXT_CHAPTER.equals(action)) {
                nextChapter();
            } else if (ACTION_STOP.equals(action)) {
                stopPlayback();
            } else if (ACTION_REFRESH_NOTIFICATION.equals(action)) {
                refreshNotification();
            }
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void loadAudiobook(String filePath, boolean startPlaying) {
        if (filePath == null) return;
        File file = new File(filePath);
        if (!file.exists()) {
            notifyError("File does not exist: " + filePath);
            return;
        }

        this.autoPlayOnPrepare = startPlaying;

        // Save position of previous book if any
        saveCurrentPosition();

        currentAudiobook = AudioMetadataHelper.extractAudiobookInfo(file);
        long resumePos = resumeManager.getResumePosition(filePath);
        currentAudiobook.setResumePositionMs(resumePos);
        String serverId = resumeManager.getServerItemId(filePath);
        if (serverId != null && !serverId.isEmpty()) {
            currentAudiobook.setServerItemId(serverId);
        }

        // Extract chapters asynchronously or directly
        new Thread(() -> {
            String mediaSource = filePath;
            AudioSanitizer.PatchInfo patch = AudioSanitizer.getPatchInfoIfNeeded(file);
            if (patch != null) {
                boolean patchedInPlace = AudioSanitizer.applyInPlacePatch(file, patch);
                if (!patchedInPlace) {
                    try {
                        mediaSource = localStreamProxy.start(file, patch.fileOffset, patch.fixedByte);
                        Log.i(TAG, "AudioSanitizer: using streaming proxy fallback for read-only media: " + mediaSource);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to start LocalStreamProxy: " + e.getMessage(), e);
                    }
                } else {
                    localStreamProxy.stop();
                }
            } else {
                localStreamProxy.stop();
            }

            chapters = ChapterExtractor.extractChapters(PlaybackService.this, file, currentAudiobook.getDurationMs());
            currentAudiobook.setChapterCount(chapters.size());

            final String finalDataSource = mediaSource;
            handler.post(() -> {
                try {
                    initMediaPlayer();
                    mediaPlayer.setDataSource(finalDataSource);
                    mediaPlayer.prepareAsync();
                } catch (Exception e) {
                    autoPlayOnPrepare = false;
                    notifyError("Failed to open media: " + e.getMessage());
                }

                for (PlaybackListener l : listeners) {
                    l.onAudiobookLoaded(currentAudiobook, chapters);
                }
            });
        }).start();
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        isPrepared = true;
        if (currentAudiobook != null && currentAudiobook.getDurationMs() <= 0) {
            currentAudiobook.setDurationMs(mp.getDuration());
        }

        // Apply playback speed on API 23+
        applySpeedParams();

        long targetPos = resumeManager.getResumePosition(currentAudiobook.getFilePath());
        if (targetPos > 0 && targetPos < mp.getDuration()) {
            // API 16 seekTo(int)
            mp.seekTo((int) targetPos);
        } else {
            updateChapterForPosition(0);
            updateMetadata();
            if (autoPlayOnPrepare) {
                autoPlayOnPrepare = false;
                play();
            } else {
                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
            }
        }

        syncPlaybackProgressWithServer(false);
    }

    @Override
    public void onSeekComplete(MediaPlayer mp) {
        long pos = mp.getCurrentPosition();
        updateChapterForPosition(pos);
        updateMetadata();
        saveCurrentPosition();
        for (PlaybackListener l : listeners) {
            l.onProgressUpdate(pos, mp.getDuration());
        }
        if (autoPlayOnPrepare) {
            autoPlayOnPrepare = false;
            play();
        } else {
            updatePlaybackState(mp.isPlaying() ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED);
        }
        if (isForeground) {
            updateNotification();
        }
    }

    public void play() {
        if (!isPrepared || mediaPlayer == null) return;

        if (requestAudioFocus()) {
            mediaPlayer.start();
            applySpeedParams();

            if (!isForeground) {
                startForeground(NOTIFICATION_ID, buildNotification());
                isForeground = true;
            } else {
                updateNotification();
            }

            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
            notifyPlaybackStateChanged(true);

            startServerSessionIfNeeded();
        }
    }

    public void pause() {
        if (mediaPlayer != null && isPrepared && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            saveCurrentPosition();
            syncPlaybackProgressWithServer(true);
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
            notifyPlaybackStateChanged(false);
            if (isForeground) {
                updateNotification();
            }
        }
    }

    public void stopPlayback() {
        if (mediaPlayer != null) {
            if (isPrepared && mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            }
            saveCurrentPosition();
            syncPlaybackProgressWithServer(true);
        }
        abandonAudioFocus();
        stopForeground(true);
        isForeground = false;
        localStreamProxy.stop();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(NOTIFICATION_ID);
        }
        currentAudiobook = null;
        chapters = null;
        currentChapterIndex = 0;
        isPrepared = false;
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED);
        notifyPlaybackStateChanged(false);
    }

    public void togglePlayPause() {
        if (isPlaying()) {
            pause();
        } else {
            play();
        }
    }

    public void seekTo(long positionMs) {
        if (mediaPlayer != null && isPrepared) {
            long clamped = Math.max(0, Math.min(positionMs, mediaPlayer.getDuration()));
            mediaPlayer.seekTo((int) clamped); // API 16+ safe
        }
    }

    public void skipForward(int seconds) {
        if (mediaPlayer != null && isPrepared) {
            long target = mediaPlayer.getCurrentPosition() + (seconds * 1000L);
            seekTo(target);
        }
    }

    public void skipBackward(int seconds) {
        if (mediaPlayer != null && isPrepared) {
            long target = mediaPlayer.getCurrentPosition() - (seconds * 1000L);
            seekTo(target);
        }
    }

    public void nextChapter() {
        if (chapters == null || chapters.isEmpty()) return;
        if (currentChapterIndex < chapters.size() - 1) {
            seekToChapter(currentChapterIndex + 1);
        } else {
            // Already on last chapter, seek to end
            seekTo(mediaPlayer != null ? mediaPlayer.getDuration() : 0);
        }
    }

    public void previousChapter() {
        if (chapters == null || chapters.isEmpty()) return;
        long currentPos = (mediaPlayer != null && isPrepared) ? mediaPlayer.getCurrentPosition() : 0;
        Chapter curr = getCurrentChapter();

        // If played more than 3 seconds into chapter, rewind to start of current chapter
        if (curr != null && (currentPos - curr.getStartMs()) > 3000) {
            seekTo(curr.getStartMs());
        } else if (currentChapterIndex > 0) {
            seekToChapter(currentChapterIndex - 1);
        } else {
            seekTo(0);
        }
    }

    public void seekToChapter(int index) {
        if (chapters == null || index < 0 || index >= chapters.size()) return;
        currentChapterIndex = index;
        Chapter ch = chapters.get(index);
        seekTo(ch.getStartMs());
        notifyChapterChanged();
        if (!isPlaying()) {
            play();
        }
    }

    public void startSleepTimer(int minutes) {
        if (minutes <= 0) {
            cancelSleepTimer();
            return;
        }
        sleepTimerEndTimeMs = System.currentTimeMillis() + (minutes * 60L * 1000L);
        handler.removeCallbacks(sleepTimerRunnable);
        handler.post(sleepTimerRunnable);
    }

    public void cancelSleepTimer() {
        sleepTimerEndTimeMs = 0;
        handler.removeCallbacks(sleepTimerRunnable);
    }

    public boolean isSleepTimerActive() {
        return sleepTimerEndTimeMs > System.currentTimeMillis();
    }

    public int getRemainingSleepMinutes() {
        if (!isSleepTimerActive()) return 0;
        long diffMs = sleepTimerEndTimeMs - System.currentTimeMillis();
        return (int) Math.max(1, (diffMs + 59999) / 60000);
    }

    public void setPlaybackSpeed(float speed) {
        this.currentSpeed = Math.max(0.5f, Math.min(3.0f, speed));
        if (settingsManager != null) {
            settingsManager.setPlaybackSpeed(this.currentSpeed);
        }
        applySpeedParams();
    }

    private void applySpeedParams() {
        // Spec 6.4: Option A - setPlaybackParams on API >= 23
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (mediaPlayer != null && isPrepared) {
                try {
                    boolean wasPlaying = false;
                    try {
                        wasPlaying = mediaPlayer.isPlaying();
                    } catch (Exception ignored) {
                    }

                    PlaybackParams params = null;
                    try {
                        params = mediaPlayer.getPlaybackParams();
                    } catch (Exception ignored) {
                    }
                    if (params == null) {
                        params = new PlaybackParams();
                    }
                    params.setSpeed(currentSpeed);
                    mediaPlayer.setPlaybackParams(params);

                    // Android's setPlaybackParams automatically starts playback if speed > 0.
                    // If the player was paused prior to setting playback params, restore paused state.
                    if (!wasPlaying) {
                        try {
                            mediaPlayer.pause();
                        } catch (Exception ignored) {
                        }
                    }
                    updatePlaybackState(wasPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to apply playback speed: " + currentSpeed, e);
                }
            }
        }
    }

    public boolean isPlaying() {
        return mediaPlayer != null && isPrepared && mediaPlayer.isPlaying();
    }

    public Chapter getCurrentChapter() {
        if (chapters != null && currentChapterIndex >= 0 && currentChapterIndex < chapters.size()) {
            return chapters.get(currentChapterIndex);
        }
        return null;
    }

    private void updateChapterForPosition(long pos) {
        if (chapters == null || chapters.isEmpty()) return;
        int newIndex = 0;
        for (int i = 0; i < chapters.size(); i++) {
            Chapter ch = chapters.get(i);
            if (pos >= ch.getStartMs()) {
                newIndex = i;
            } else {
                break;
            }
        }
        if (newIndex != currentChapterIndex) {
            currentChapterIndex = newIndex;
            notifyChapterChanged();
            updateMetadata();
            if (isForeground) {
                updateNotification();
            }
        }
    }

    private void saveCurrentPosition() {
        if (currentAudiobook != null && mediaPlayer != null && isPrepared) {
            long pos = mediaPlayer.getCurrentPosition();
            resumeManager.saveResumePosition(currentAudiobook.getFilePath(), pos);
            currentAudiobook.setResumePositionMs(pos);
        }
    }

    private void startServerSessionIfNeeded() {
        if (currentAudiobook == null || settingsManager == null || !settingsManager.isServerConfigured() || absClient == null) {
            return;
        }

        final String filePath = currentAudiobook.getFilePath();
        if (filePath == null) return;

        String serverId = currentAudiobook.getServerItemId();
        if (serverId == null || serverId.isEmpty()) {
            if (resumeManager != null) {
                serverId = resumeManager.getServerItemId(filePath);
                if (serverId != null) currentAudiobook.setServerItemId(serverId);
            }
        }

        if (serverId == null || serverId.isEmpty()) {
            absClient.resolveServerItemId(settingsManager.getServerUrl(), settingsManager.getServerToken(), settingsManager.getServerLibraryId(), currentAudiobook.getTitle(), currentAudiobook.getAuthor(), filePath, resolvedId -> {
                if (resolvedId != null) {
                    if (resumeManager != null) resumeManager.saveServerItemId(filePath, resolvedId);
                    if (currentAudiobook != null && filePath.equals(currentAudiobook.getFilePath())) {
                        currentAudiobook.setServerItemId(resolvedId);
                    }
                    startServerSessionIfNeeded();
                }
            });
            return;
        }

        final String itemId = serverId;
        final String serverUrl = settingsManager.getServerUrl();
        final String token = settingsManager.getServerToken();

        absClient.startPlaybackSession(serverUrl, token, itemId, new AudiobookshelfClient.PlaybackSessionCallback() {
            @Override
            public void onSuccess(String sessionId, long serverProgressMs) {
                if (currentAudiobook == null || !filePath.equals(currentAudiobook.getFilePath())) return;
                if (sessionId != null && !sessionId.isEmpty() && resumeManager != null) {
                    resumeManager.saveSessionId(filePath, sessionId);
                }

                long curLocalPos = (mediaPlayer != null && isPrepared) ? mediaPlayer.getCurrentPosition() : (resumeManager != null ? resumeManager.getResumePosition(filePath) : 0L);

                if (serverProgressMs > curLocalPos + SYNC_THRESHOLD_MS) {
                    Log.i(TAG, "Audiobookshelf start session: server is ahead (" + serverProgressMs + "ms vs " + curLocalPos + "ms). Updating local position.");
                    if (resumeManager != null) {
                        resumeManager.saveResumePosition(filePath, serverProgressMs);
                        resumeManager.saveLastSyncedPosition(filePath, serverProgressMs);
                    }
                    currentAudiobook.setResumePositionMs(serverProgressMs);
                    currentAudiobook.setServerProgressMs(serverProgressMs);
                    if (mediaPlayer != null && isPrepared) {
                        seekTo(serverProgressMs);
                    }
                } else if (curLocalPos > serverProgressMs + SYNC_THRESHOLD_MS && curLocalPos > 0) {
                    // Local is further ahead: push local position to server session
                    syncPlaybackProgressWithServer(true);
                }
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "startPlaybackSession error: " + message);
            }
        });
    }

    private void syncPlaybackProgressWithServer(final boolean force) {
        if (currentAudiobook == null || settingsManager == null || !settingsManager.isServerConfigured() || absClient == null) {
            return;
        }

        final String filePath = currentAudiobook.getFilePath();
        if (filePath == null || filePath.isEmpty()) {
            return;
        }

        String serverId = currentAudiobook.getServerItemId();
        if (serverId == null || serverId.isEmpty()) {
            if (resumeManager != null) {
                serverId = resumeManager.getServerItemId(filePath);
                if (serverId != null && !serverId.isEmpty()) {
                    currentAudiobook.setServerItemId(serverId);
                }
            }
        }

        if (serverId == null || serverId.isEmpty()) {
            absClient.resolveServerItemId(settingsManager.getServerUrl(), settingsManager.getServerToken(), settingsManager.getServerLibraryId(), currentAudiobook.getTitle(), currentAudiobook.getAuthor(), filePath, resolvedId -> {
                if (resolvedId != null) {
                    if (resumeManager != null) resumeManager.saveServerItemId(filePath, resolvedId);
                    if (currentAudiobook != null && filePath.equals(currentAudiobook.getFilePath())) {
                        currentAudiobook.setServerItemId(resolvedId);
                    }
                    syncPlaybackProgressWithServer(force);
                }
            });
            return;
        }

        final String itemId = serverId;
        final String serverUrl = settingsManager.getServerUrl();
        final String token = settingsManager.getServerToken();
        final String sessionId = (resumeManager != null) ? resumeManager.getSessionId(filePath) : null;
        final long sessionStartedAt = (resumeManager != null) ? resumeManager.getSessionStartedAt(filePath) : 0L;

        long pos = (mediaPlayer != null && isPrepared) ? mediaPlayer.getCurrentPosition() : (resumeManager != null ? resumeManager.getResumePosition(filePath) : currentAudiobook.getResumePositionMs());
        long dur = (mediaPlayer != null && isPrepared) ? mediaPlayer.getDuration() : currentAudiobook.getDurationMs();
        final long localPosMs = pos;
        final long durationMs = dur;

        absClient.fetchItemProgress(serverUrl, token, itemId, new AudiobookshelfClient.ProgressCallback() {
            @Override
            public void onSuccess(long serverProgressMs, double durationSec, boolean isFinished) {
                if (currentAudiobook == null || !filePath.equals(currentAudiobook.getFilePath())) {
                    return;
                }

                long curLocalPos = (mediaPlayer != null && isPrepared) ? mediaPlayer.getCurrentPosition() : (resumeManager != null ? resumeManager.getResumePosition(filePath) : localPosMs);

                if (serverProgressMs > curLocalPos + SYNC_THRESHOLD_MS) {
                    // Server value is further along: prioritize server and modify local time to match
                    Log.i(TAG, "Audiobookshelf sync: server is ahead (" + serverProgressMs + "ms vs local " + curLocalPos + "ms). Updating local position.");
                    if (resumeManager != null) {
                        resumeManager.saveResumePosition(filePath, serverProgressMs);
                        resumeManager.saveLastSyncedPosition(filePath, serverProgressMs);
                    }
                    currentAudiobook.setResumePositionMs(serverProgressMs);
                    currentAudiobook.setServerProgressMs(serverProgressMs);

                    if (mediaPlayer != null && isPrepared) {
                        seekTo(serverProgressMs);
                    }
                } else if (curLocalPos > serverProgressMs + SYNC_THRESHOLD_MS) {
                    // Local value is further along: prioritize local and update server
                    long lastSynced = (resumeManager != null) ? resumeManager.getLastSyncedPosition(filePath) : -1L;
                    if (force || Math.abs(curLocalPos - lastSynced) >= SYNC_THRESHOLD_MS) {
                        Log.i(TAG, "Audiobookshelf sync: local is ahead (" + curLocalPos + "ms vs server " + serverProgressMs + "ms). Updating server.");
                        absClient.updateItemProgress(serverUrl, token, itemId, sessionId, curLocalPos, durationMs, sessionStartedAt, new AudiobookshelfClient.SyncCallback() {
                            @Override
                            public void onSuccess() {
                                if (resumeManager != null) {
                                    resumeManager.saveLastSyncedPosition(filePath, curLocalPos);
                                }
                                if (currentAudiobook != null && filePath.equals(currentAudiobook.getFilePath())) {
                                    currentAudiobook.setServerProgressMs(curLocalPos);
                                }
                            }

                            @Override
                            public void onError(String message) {
                                Log.w(TAG, "Failed to update server progress: " + message);
                            }
                        });
                    }
                } else {
                    if (resumeManager != null) {
                        resumeManager.saveLastSyncedPosition(filePath, curLocalPos);
                    }
                }
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "Could not fetch server progress: " + message);
                if (force && localPosMs > SYNC_THRESHOLD_MS) {
                    absClient.updateItemProgress(serverUrl, token, itemId, sessionId, localPosMs, durationMs, sessionStartedAt, null);
                }
            }
        });
    }

    private void updateMetadata() {
        if (currentAudiobook == null) return;

        Chapter ch = getCurrentChapter();
        String chapterTitle = (ch != null) ? ch.getTitle() : currentAudiobook.getTitle();
        long dur = (mediaPlayer != null && isPrepared) ? mediaPlayer.getDuration() : currentAudiobook.getDurationMs();

        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, chapterTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentAudiobook.getTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentAudiobook.getAuthor())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, dur);

        Bitmap cover = AudioMetadataHelper.loadCoverArt(currentAudiobook.getFilePath(), 256, 256);
        if (cover != null) {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cover);
        }

        mediaSession.setMetadata(builder.build());
    }

    private void updatePlaybackState(int state) {
        long position = (mediaPlayer != null && isPrepared) ? mediaPlayer.getCurrentPosition() : 0;
        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY |
                                PlaybackStateCompat.ACTION_PAUSE |
                                PlaybackStateCompat.ACTION_PLAY_PAUSE |
                                PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                                PlaybackStateCompat.ACTION_FAST_FORWARD |
                                PlaybackStateCompat.ACTION_REWIND |
                                PlaybackStateCompat.ACTION_SEEK_TO)
                .setState(state, position, currentSpeed);

        mediaSession.setPlaybackState(stateBuilder.build());
    }

    private Notification buildNotification() {
        Chapter ch = getCurrentChapter();
        String bookTitle = currentAudiobook != null ? currentAudiobook.getTitle() : getString(R.string.unknown_title);
        String chapterTitle = ch != null ? ch.getTitle() : bookTitle;
        String contentText = bookTitle + " • " + (currentAudiobook != null ? currentAudiobook.getAuthor() : "");

        Intent openIntent = new Intent(this, PlayerActivity.class);
        if (currentAudiobook != null) {
            openIntent.putExtra("audiobook_path", currentAudiobook.getFilePath());
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentPendingIntent = PendingIntent.getActivity(this, 0, openIntent, flags);

        // Notification Actions
        PendingIntent prevIntent = createServicePendingIntent(ACTION_PREV_CHAPTER, 1);
        PendingIntent rewindIntent = createServicePendingIntent(ACTION_REWIND, 2);
        PendingIntent playPauseIntent = createServicePendingIntent(ACTION_TOGGLE, 3);
        PendingIntent forwardIntent = createServicePendingIntent(ACTION_FORWARD, 4);
        PendingIntent nextIntent = createServicePendingIntent(ACTION_NEXT_CHAPTER, 5);

        int playPauseIcon = isPlaying() ? R.drawable.ic_notif_pause : R.drawable.ic_notif_play;
        String playPauseTitle = isPlaying() ? getString(R.string.pause) : getString(R.string.play);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_play)
                .setColor(getNotificationColor())
                .setContentTitle(chapterTitle)
                .setContentText(contentText)
                .setContentIntent(contentPendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setShowWhen(false)
                .addAction(R.drawable.ic_notif_skip_previous, getString(R.string.previous_chapter), prevIntent)
                .addAction(R.drawable.ic_notif_skip_backward, getString(R.string.rewind_30, settingsManager.getSkipDurationSeconds()), rewindIntent)
                .addAction(playPauseIcon, playPauseTitle, playPauseIntent)
                .addAction(R.drawable.ic_notif_skip_forward, getString(R.string.forward_30, settingsManager.getSkipDurationSeconds()), forwardIntent)
                .addAction(R.drawable.ic_notif_skip_next, getString(R.string.next_chapter), nextIntent)
                .setStyle(new MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(1, 2, 3)); // Rewind, Play/Pause, Forward

        if (currentAudiobook != null) {
            Bitmap cover = AudioMetadataHelper.loadCoverArt(currentAudiobook.getFilePath(), 128, 128);
            if (cover != null) {
                builder.setLargeIcon(cover);
            }
        }

        return builder.build();
    }

    private PendingIntent createServicePendingIntent(String action, int requestCode) {
        Intent intent = new Intent(this, PlaybackService.class);
        intent.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(this, requestCode, intent, flags);
    }

    private int getNotificationColor() {
        Context context = this;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            Configuration config = new Configuration(getResources().getConfiguration());
            boolean isNight = (settingsManager != null && !settingsManager.isLightMode());
            config.uiMode = isNight
                    ? (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES
                    : (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_NO;
            context = createConfigurationContext(config);
        }
        return androidx.core.content.ContextCompat.getColor(context, R.color.action_bar_background);
    }

    public void refreshNotification() {
        if (isForeground) {
            updateNotification();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        refreshNotification();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.notification_channel_description));
            channel.setShowBadge(false);
            channel.setSound(null, null);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private boolean requestAudioFocus() {
        if (audioManager == null) return true;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(this)
                    .build();
            int res = audioManager.requestAudioFocus(audioFocusRequest);
            return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            int res = audioManager.requestAudioFocus(
                    this,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
            );
            return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
            }
        } else {
            audioManager.abandonAudioFocus(this);
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
                resumeOnFocusGain = false;
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (isPlaying()) {
                    resumeOnFocusGain = true;
                    pause();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (mediaPlayer != null && isPlaying()) {
                    mediaPlayer.setVolume(0.2f, 0.2f);
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (mediaPlayer != null) {
                    mediaPlayer.setVolume(1.0f, 1.0f);
                }
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false;
                    play();
                }
                break;
        }
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        saveCurrentPosition();
        syncPlaybackProgressWithServer(true);
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
        notifyPlaybackStateChanged(false);
        if (isForeground) {
            updateNotification();
        }
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        autoPlayOnPrepare = false;
        notifyError("MediaPlayer error: what=" + what + ", extra=" + extra);
        initMediaPlayer();
        return true;
    }

    private void notifyPlaybackStateChanged(boolean isPlaying) {
        for (PlaybackListener l : listeners) {
            l.onPlaybackStateChanged(isPlaying);
        }
    }

    private void notifyChapterChanged() {
        Chapter ch = getCurrentChapter();
        for (PlaybackListener l : listeners) {
            l.onChapterChanged(currentChapterIndex, ch);
        }
    }

    private void notifyError(String msg) {
        for (PlaybackListener l : listeners) {
            l.onError(msg);
        }
    }

    @Override
    public void onDestroy() {
        autoPlayOnPrepare = false;
        saveCurrentPosition();
        syncPlaybackProgressWithServer(true);
        handler.removeCallbacks(progressRunnable);
        handler.removeCallbacks(periodicSaveRunnable);
        handler.removeCallbacks(periodicSyncRunnable);
        handler.removeCallbacks(sleepTimerRunnable);

        if (isNoisyReceiverRegistered) {
            try {
                unregisterReceiver(noisyReceiver);
            } catch (Exception ignored) {
            }
            isNoisyReceiverRegistered = false;
        }

        abandonAudioFocus();

        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }

        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }

        if (mediaButtonController != null) {
            mediaButtonController.release();
            mediaButtonController = null;
        }

        localStreamProxy.stop();

        stopForeground(true);
        isForeground = false;
        super.onDestroy();
    }

    public boolean isHeadsetConnected() {
        return mediaButtonController != null && mediaButtonController.isHeadsetOrHeadphonesConnected();
    }

    public MediaButtonController getMediaButtonController() {
        return mediaButtonController;
    }
}
