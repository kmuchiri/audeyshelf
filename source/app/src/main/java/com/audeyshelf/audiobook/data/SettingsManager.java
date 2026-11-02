package com.audeyshelf.audiobook.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import androidx.appcompat.app.AppCompatDelegate;

import com.audeyshelf.audiobook.R;

import java.io.File;

/**
 * Manages user preferences including audiobook root folder, theme mode (Light/Dark/System),
 * and skip duration.
 */
public class SettingsManager {
    private static final String PREFS_NAME = "ember_settings";
    private static final String KEY_ROOT_FOLDER = "pref_root_folder";
    private static final String KEY_THEME_MODE = "pref_theme_mode";
    private static final String KEY_SKIP_DURATION = "pref_skip_duration";
    private static final String KEY_SLEEP_TIMER = "pref_sleep_timer_minutes";
    private static final String KEY_PLAYBACK_SPEED = "pref_playback_speed";
    private static final String KEY_BUTTON_CONTROL_ENABLED = "pref_button_control_enabled";
    private static final String KEY_BUTTON_SINGLE_TAP_ACTION = "pref_button_single_tap_action";
    private static final String KEY_BUTTON_DOUBLE_TAP_ACTION = "pref_button_double_tap_action";
    private static final String KEY_BUTTON_TRIPLE_TAP_ACTION = "pref_button_triple_tap_action";

    // Audiobookshelf Server Preferences
    private static final String KEY_SERVER_URL = "pref_abs_server_url";
    private static final String KEY_SERVER_TOKEN = "pref_abs_token";
    private static final String KEY_SERVER_USERNAME = "pref_abs_username";
    private static final String KEY_SERVER_LIBRARY_ID = "pref_abs_library_id";
    private static final String KEY_SERVER_LIBRARY_NAME = "pref_abs_library_name";
    private static final String KEY_SD_CARD_TREE_URI = "pref_sd_card_tree_uri";

    public static final int THEME_SYSTEM = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2; // Stak mode

    // Button Control Actions
    public static final int ACTION_PLAY_PAUSE = 0;
    public static final int ACTION_FORWARD_10S = 1;
    public static final int ACTION_REWIND_10S = 2;
    public static final int ACTION_NEXT_CHAPTER = 3;
    public static final int ACTION_PREV_CHAPTER = 4;
    public static final int ACTION_PAUSE_ONLY = 5;
    public static final int ACTION_NONE = 6;

    private final SharedPreferences prefs;
    private final Context context;

    public SettingsManager(Context context) {
        if (context != null) {
            this.context = context.getApplicationContext();
            this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        } else {
            this.context = null;
            this.prefs = null;
        }
    }

    public String getRootFolder() {
        String saved = prefs.getString(KEY_ROOT_FOLDER, null);
        if (saved != null && new File(saved).isDirectory()) {
            return saved;
        }

        File extSdAudiobooks = new File("/storage/extSdCard/Audiobooks");
        if (extSdAudiobooks.isDirectory()) {
            return extSdAudiobooks.getAbsolutePath();
        }

        // Default: External storage Audiobooks or removable SD card Audiobooks
        File audiobooksDir = new File(Environment.getExternalStorageDirectory(), "Audiobooks");
        if (audiobooksDir.isDirectory()) {
            return audiobooksDir.getAbsolutePath();
        }

        File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        if (musicDir != null && musicDir.isDirectory()) {
            return musicDir.getAbsolutePath();
        }

        File ext = Environment.getExternalStorageDirectory();
        return ext != null ? ext.getAbsolutePath() : "/sdcard";
    }

    public void setRootFolder(String folderPath) {
        if (folderPath == null) return;
        prefs.edit().putString(KEY_ROOT_FOLDER, folderPath).apply();
    }

    public String getSdCardTreeUri() {
        return prefs != null ? prefs.getString(KEY_SD_CARD_TREE_URI, null) : null;
    }

    public void setSdCardTreeUri(String uriString) {
        if (prefs != null) {
            prefs.edit().putString(KEY_SD_CARD_TREE_URI, uriString).apply();
        }
    }

    public int getThemeMode() {
        return prefs.getInt(KEY_THEME_MODE, THEME_SYSTEM);
    }

    public void setThemeMode(int themeMode) {
        prefs.edit().putInt(KEY_THEME_MODE, themeMode).apply();
        applyTheme(themeMode);
        notifyThemeChanged();
    }

    private void notifyThemeChanged() {
        try {
            android.content.Intent refreshIntent = new android.content.Intent(context, com.audeyshelf.audiobook.media.PlaybackService.class);
            refreshIntent.setAction(com.audeyshelf.audiobook.media.PlaybackService.ACTION_REFRESH_NOTIFICATION);
            context.startService(refreshIntent);
        } catch (Exception ignored) {
        }
    }

    public void applySavedTheme() {
        applyTheme(getThemeMode());
    }

    private void applyTheme(int themeMode) {
        switch (themeMode) {
            case THEME_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case THEME_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case THEME_SYSTEM:
            default:
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                } else {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                }
                break;
        }
    }

    public int getSkipDurationSeconds() {
        return prefs.getInt(KEY_SKIP_DURATION, 30);
    }

    public void setSkipDurationSeconds(int seconds) {
        prefs.edit().putInt(KEY_SKIP_DURATION, seconds).apply();
    }

    public int getSleepTimerMinutes() {
        return prefs.getInt(KEY_SLEEP_TIMER, 5);
    }

    public void setSleepTimerMinutes(int minutes) {
        prefs.edit().putInt(KEY_SLEEP_TIMER, minutes).apply();
    }

    public float getPlaybackSpeed() {
        return prefs.getFloat(KEY_PLAYBACK_SPEED, 1.0f);
    }

    public void setPlaybackSpeed(float speed) {
        prefs.edit().putFloat(KEY_PLAYBACK_SPEED, speed).apply();
    }

    public boolean isLightMode() {
        int mode = getThemeMode();
        if (mode == THEME_LIGHT) return true;
        if (mode == THEME_DARK) return false;
        int currentNightMode = context.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return currentNightMode != android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    public void setLightMode(boolean isLight) {
        setThemeMode(isLight ? THEME_LIGHT : THEME_DARK);
    }

    public boolean isButtonControlEnabled() {
        return prefs.getBoolean(KEY_BUTTON_CONTROL_ENABLED, true);
    }

    public void setButtonControlEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_BUTTON_CONTROL_ENABLED, enabled).apply();
    }

    public int getButtonSingleTapAction() {
        return prefs.getInt(KEY_BUTTON_SINGLE_TAP_ACTION, ACTION_PLAY_PAUSE);
    }

    public void setButtonSingleTapAction(int action) {
        prefs.edit().putInt(KEY_BUTTON_SINGLE_TAP_ACTION, action).apply();
    }

    public int getButtonDoubleTapAction() {
        return prefs.getInt(KEY_BUTTON_DOUBLE_TAP_ACTION, ACTION_FORWARD_10S);
    }

    public void setButtonDoubleTapAction(int action) {
        prefs.edit().putInt(KEY_BUTTON_DOUBLE_TAP_ACTION, action).apply();
    }

    public int getButtonTripleTapAction() {
        return prefs.getInt(KEY_BUTTON_TRIPLE_TAP_ACTION, ACTION_REWIND_10S);
    }

    public void setButtonTripleTapAction(int action) {
        prefs.edit().putInt(KEY_BUTTON_TRIPLE_TAP_ACTION, action).apply();
    }

    public static String getActionTitle(Context context, int action) {
        if (context == null) return "";
        switch (action) {
            case ACTION_PLAY_PAUSE:
                return context.getString(R.string.btn_action_play_pause);
            case ACTION_FORWARD_10S:
                return context.getString(R.string.btn_action_forward_10s);
            case ACTION_REWIND_10S:
                return context.getString(R.string.btn_action_rewind_10s);
            case ACTION_NEXT_CHAPTER:
                return context.getString(R.string.btn_action_next_chapter);
            case ACTION_PREV_CHAPTER:
                return context.getString(R.string.btn_action_prev_chapter);
            case ACTION_PAUSE_ONLY:
                return context.getString(R.string.btn_action_pause_only);
            case ACTION_NONE:
            default:
                return context.getString(R.string.btn_action_none);
        }
    }

    public String getServerUrl() {
        return prefs != null ? prefs.getString(KEY_SERVER_URL, "") : "";
    }

    public void setServerUrl(String url) {
        if (prefs != null) {
            String normalized = com.audeyshelf.audiobook.net.AudiobookshelfClient.normalizeUrl(url);
            prefs.edit().putString(KEY_SERVER_URL, normalized).apply();
        }
    }

    public String getServerToken() {
        return prefs != null ? prefs.getString(KEY_SERVER_TOKEN, "") : "";
    }

    public void setServerToken(String token) {
        if (prefs != null) {
            prefs.edit().putString(KEY_SERVER_TOKEN, token != null ? token.trim() : "").apply();
        }
    }

    public String getServerUsername() {
        return prefs != null ? prefs.getString(KEY_SERVER_USERNAME, "") : "";
    }

    public void setServerUsername(String username) {
        if (prefs != null) {
            prefs.edit().putString(KEY_SERVER_USERNAME, username != null ? username.trim() : "").apply();
        }
    }

    public String getServerLibraryId() {
        return prefs != null ? prefs.getString(KEY_SERVER_LIBRARY_ID, "") : "";
    }

    public void setServerLibraryId(String libraryId) {
        if (prefs != null) {
            prefs.edit().putString(KEY_SERVER_LIBRARY_ID, libraryId != null ? libraryId.trim() : "").apply();
        }
    }

    public String getServerLibraryName() {
        return prefs != null ? prefs.getString(KEY_SERVER_LIBRARY_NAME, "") : "";
    }

    public void setServerLibraryName(String libraryName) {
        if (prefs != null) {
            prefs.edit().putString(KEY_SERVER_LIBRARY_NAME, libraryName != null ? libraryName.trim() : "").apply();
        }
    }

    public boolean isServerConfigured() {
        return prefs != null && !getServerUrl().isEmpty() && !getServerToken().isEmpty();
    }

    public void clearServerCredentials() {
        if (prefs != null) {
            prefs.edit()
                    .remove(KEY_SERVER_URL)
                    .remove(KEY_SERVER_TOKEN)
                    .remove(KEY_SERVER_USERNAME)
                    .remove(KEY_SERVER_LIBRARY_ID)
                    .remove(KEY_SERVER_LIBRARY_NAME)
                    .apply();
        }
    }
}
