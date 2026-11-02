package com.audeyshelf.audiobook.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Manages audiobook playback resume positions using SharedPreferences with apply().
 * Follows spec.md Section 4.1.
 */
public class ResumeManager {
    private static final String PREFS_NAME = "resume_prefs";
    private static final String KEY_POSITION_PREFIX = "pos_";
    private static final String KEY_LAST_PLAYED_PATH = "last_played_path";
    private static final String KEY_SERVER_ITEM_ID_PREFIX = "server_id_";
    private static final String KEY_LAST_SYNCED_PREFIX = "last_synced_pos_";
    private static final String KEY_SESSION_ID_PREFIX = "session_id_";
    private static final String KEY_SESSION_START_PREFIX = "session_start_";

    private final SharedPreferences prefs;

    public ResumeManager(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void saveResumePosition(String filePath, long positionMs) {
        if (filePath == null) return;
        prefs.edit()
                .putLong(KEY_POSITION_PREFIX + filePath.hashCode(), positionMs)
                .putString(KEY_LAST_PLAYED_PATH, filePath)
                .apply();
    }

    public long getResumePosition(String filePath) {
        if (filePath == null) return 0L;
        return prefs.getLong(KEY_POSITION_PREFIX + filePath.hashCode(), 0L);
    }

    public String getLastPlayedAudiobookPath() {
        return prefs.getString(KEY_LAST_PLAYED_PATH, null);
    }

    public void clearResumePosition(String filePath) {
        if (filePath == null) return;
        prefs.edit().remove(KEY_POSITION_PREFIX + filePath.hashCode()).apply();
    }

    public void saveServerItemId(String filePath, String serverItemId) {
        if (filePath == null || serverItemId == null) return;
        prefs.edit().putString(KEY_SERVER_ITEM_ID_PREFIX + filePath.hashCode(), serverItemId).apply();
    }

    public String getServerItemId(String filePath) {
        if (filePath == null) return null;
        return prefs.getString(KEY_SERVER_ITEM_ID_PREFIX + filePath.hashCode(), null);
    }

    public void saveLastSyncedPosition(String filePath, long positionMs) {
        if (filePath == null) return;
        prefs.edit().putLong(KEY_LAST_SYNCED_PREFIX + filePath.hashCode(), positionMs).apply();
    }

    public long getLastSyncedPosition(String filePath) {
        if (filePath == null) return -1L;
        return prefs.getLong(KEY_LAST_SYNCED_PREFIX + filePath.hashCode(), -1L);
    }

    public String getSessionId(String filePath) {
        if (filePath == null) return java.util.UUID.randomUUID().toString();
        String saved = prefs.getString(KEY_SESSION_ID_PREFIX + filePath.hashCode(), null);
        if (saved == null || saved.isEmpty()) {
            saved = java.util.UUID.randomUUID().toString();
            prefs.edit().putString(KEY_SESSION_ID_PREFIX + filePath.hashCode(), saved).apply();
        }
        return saved;
    }

    public void saveSessionId(String filePath, String sessionId) {
        if (filePath == null || sessionId == null) return;
        prefs.edit().putString(KEY_SESSION_ID_PREFIX + filePath.hashCode(), sessionId).apply();
    }

    public long getSessionStartedAt(String filePath) {
        if (filePath == null) return System.currentTimeMillis();
        long started = prefs.getLong(KEY_SESSION_START_PREFIX + filePath.hashCode(), 0L);
        if (started <= 0L) {
            started = System.currentTimeMillis();
            prefs.edit().putLong(KEY_SESSION_START_PREFIX + filePath.hashCode(), started).apply();
        }
        return started;
    }

    public void saveSessionStartedAt(String filePath, long startedAt) {
        if (filePath == null) return;
        prefs.edit().putLong(KEY_SESSION_START_PREFIX + filePath.hashCode(), startedAt).apply();
    }
}
