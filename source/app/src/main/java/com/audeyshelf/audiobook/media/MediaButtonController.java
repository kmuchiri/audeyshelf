package com.audeyshelf.audiobook.media;

import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import com.audeyshelf.audiobook.data.SettingsManager;

/**
 * Controller for headset and headphone button gestures (single tap, double tap, triple tap).
 * Handles hardware media button events from wired headsets, Bluetooth headphones, and USB-C audio devices.
 */
public class MediaButtonController {
    public static final long DEFAULT_TAP_TIMEOUT_MS = 350L;

    public interface PlaybackController {
        void onPlayPause();
        void onForward(int seconds);
        void onRewind(int seconds);
        void onNextChapter();
        void onPreviousChapter();
        void onPauseOnly();
    }

    private final Context context;
    private final SettingsManager settingsManager;
    private final PlaybackController playbackController;
    private final Handler handler;
    private final long tapTimeoutMs;

    private int tapCount = 0;
    private final Runnable tapTimeoutRunnable;

    public MediaButtonController(Context context, SettingsManager settingsManager, PlaybackController playbackController) {
        this(context, settingsManager, playbackController, new Handler(Looper.getMainLooper()), DEFAULT_TAP_TIMEOUT_MS);
    }

    public MediaButtonController(Context context, SettingsManager settingsManager, PlaybackController playbackController,
                                 Handler handler, long tapTimeoutMs) {
        this.context = context != null ? context.getApplicationContext() : null;
        this.settingsManager = settingsManager;
        this.playbackController = playbackController;
        this.handler = handler;
        this.tapTimeoutMs = tapTimeoutMs;
        this.tapTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                int count = tapCount;
                tapCount = 0;
                executeTapAction(count);
            }
        };
    }

    /**
     * Checks if a wired headset, headphones, or Bluetooth audio output device is currently connected.
     */
    public boolean isHeadsetOrHeadphonesConnected() {
        if (context == null) return false;
        try {
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) return false;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                if (devices != null) {
                    for (AudioDeviceInfo device : devices) {
                        int type = device.getType();
                        if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                                || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                                || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                                || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                                || type == AudioDeviceInfo.TYPE_USB_HEADSET
                                || type == AudioDeviceInfo.TYPE_USB_DEVICE
                                || type == AudioDeviceInfo.TYPE_HEARING_AID
                                || type == 26 // TYPE_BLE_HEADSET
                                || type == 27 // TYPE_BLE_SPEAKER
                                || type == 30 // TYPE_BLE_BROADCAST
                        ) {
                            return true;
                        }
                    }
                }
                return false;
            } else {
                return audioManager.isWiredHeadsetOn()
                        || audioManager.isBluetoothA2dpOn()
                        || audioManager.isBluetoothScoOn();
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Dispatches a media button intent.
     * @return true if the event was consumed and handled.
     */
    public boolean handleIntent(Intent intent) {
        if (intent == null) return false;
        KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        return handleMediaKeyEvent(event);
    }

    /**
     * Handles a hardware media button KeyEvent.
     * @return true if the event was consumed and handled.
     */
    public boolean handleMediaKeyEvent(KeyEvent event) {
        if (event == null) return false;
        return handleMediaKeyEvent(event.getAction(), event.getKeyCode(), event.getRepeatCount());
    }

    /**
     * Handles key event details directly.
     * @return true if the event was consumed and handled.
     */
    public boolean handleMediaKeyEvent(int action, int keyCode, int repeatCount) {
        if (settingsManager != null && !settingsManager.isButtonControlEnabled()) {
            return false;
        }

        // Check for dedicated next/previous track buttons on headphones
        if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
            if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
                if (playbackController != null) {
                    playbackController.onNextChapter();
                }
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
                if (playbackController != null) {
                    playbackController.onPreviousChapter();
                }
            }
            return true;
        }

        if (!isPauseOrHeadsetButton(keyCode)) {
            return false;
        }

        // Consume key up to avoid duplicate dispatch
        if (action == KeyEvent.ACTION_UP) {
            return true;
        }

        if (action == KeyEvent.ACTION_DOWN) {
            // Ignore held long-press repeats
            if (repeatCount > 0) {
                return true;
            }

            tapCount++;
            if (handler != null) {
                handler.removeCallbacks(tapTimeoutRunnable);
            }

            if (tapCount >= 3) {
                int count = tapCount;
                tapCount = 0;
                executeTapAction(count);
            } else if (handler != null) {
                handler.postDelayed(tapTimeoutRunnable, tapTimeoutMs);
            }
            return true;
        }

        return false;
    }

    /**
     * Executes the action configured for the detected number of taps.
     */
    public void executeTapAction(int count) {
        if (settingsManager == null) return;
        int action;
        if (count == 1) {
            action = settingsManager.getButtonSingleTapAction();
        } else if (count == 2) {
            action = settingsManager.getButtonDoubleTapAction();
        } else if (count >= 3) {
            action = settingsManager.getButtonTripleTapAction();
        } else {
            return;
        }

        executeAction(action);
    }

    /**
     * Executes a specific ButtonAction.
     */
    public void executeAction(int action) {
        if (playbackController == null) return;

        switch (action) {
            case SettingsManager.ACTION_PLAY_PAUSE:
                playbackController.onPlayPause();
                break;
            case SettingsManager.ACTION_FORWARD_10S:
                playbackController.onForward(10);
                break;
            case SettingsManager.ACTION_REWIND_10S:
                playbackController.onRewind(10);
                break;
            case SettingsManager.ACTION_NEXT_CHAPTER:
                playbackController.onNextChapter();
                break;
            case SettingsManager.ACTION_PREV_CHAPTER:
                playbackController.onPreviousChapter();
                break;
            case SettingsManager.ACTION_PAUSE_ONLY:
                playbackController.onPauseOnly();
                break;
            case SettingsManager.ACTION_NONE:
            default:
                break;
        }
    }

    /**
     * Checks if the key code corresponds to the pause, play/pause, or headset hook button.
     */
    public static boolean isPauseOrHeadsetButton(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_HEADSETHOOK
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                || keyCode == KeyEvent.KEYCODE_MEDIA_STOP;
    }

    /**
     * Flushes any pending taps immediately (useful for testing or immediate execution).
     */
    public void flushPendingTaps() {
        if (tapCount > 0) {
            if (handler != null) {
                handler.removeCallbacks(tapTimeoutRunnable);
            }
            int count = tapCount;
            tapCount = 0;
            executeTapAction(count);
        }
    }

    /**
     * Current tap count in progress.
     */
    public int getTapCount() {
        return tapCount;
    }

    /**
     * Cancels any pending runnables and cleans up resources.
     */
    public void release() {
        if (handler != null) {
            handler.removeCallbacks(tapTimeoutRunnable);
        }
        tapCount = 0;
    }
}
