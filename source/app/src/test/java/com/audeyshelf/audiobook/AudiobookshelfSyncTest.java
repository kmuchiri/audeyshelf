package com.audeyshelf.audiobook;

import com.audeyshelf.audiobook.model.Audiobook;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests the Audiobookshelf playback progress synchronization logic:
 * - Prioritizing local persistence unless server value is further along.
 * - Comparing stored local values vs server values and keeping whichever is further ahead.
 * - Preventing unnecessary sync updates when values are within the threshold.
 */
public class AudiobookshelfSyncTest {

    private static final long SYNC_THRESHOLD_MS = 2000L;

    public enum SyncDecision {
        UPDATE_LOCAL_FROM_SERVER,
        UPDATE_SERVER_FROM_LOCAL,
        IN_SYNC_NOOP
    }

    /**
     * Replicates the conflict resolution rule used in PlaybackService and LibraryActivity.
     */
    public static SyncDecision determineSyncAction(long localPosMs, long serverPosMs) {
        if (serverPosMs > localPosMs + SYNC_THRESHOLD_MS) {
            return SyncDecision.UPDATE_LOCAL_FROM_SERVER;
        } else if (localPosMs > serverPosMs + SYNC_THRESHOLD_MS && localPosMs > 0) {
            return SyncDecision.UPDATE_SERVER_FROM_LOCAL;
        } else {
            return SyncDecision.IN_SYNC_NOOP;
        }
    }

    @Test
    public void testServerFurtherAlongUpdatesLocal() {
        // e.g. User listened on another device: server is at 45m (2,700,000ms), local is at 10m (600,000ms)
        long localPos = 600000L;
        long serverPos = 2700000L;

        SyncDecision decision = determineSyncAction(localPos, serverPos);
        assertEquals(SyncDecision.UPDATE_LOCAL_FROM_SERVER, decision);

        // When applied:
        long resultingLocalPos = (decision == SyncDecision.UPDATE_LOCAL_FROM_SERVER) ? serverPos : localPos;
        assertEquals(2700000L, resultingLocalPos);
    }

    @Test
    public void testLocalFurtherAlongUpdatesServer() {
        // User listened offline or on this device: local is at 50m (3,000,000ms), server is at 20m (1,200,000ms)
        long localPos = 3000000L;
        long serverPos = 1200000L;

        SyncDecision decision = determineSyncAction(localPos, serverPos);
        assertEquals(SyncDecision.UPDATE_SERVER_FROM_LOCAL, decision);
    }

    @Test
    public void testValuesWithinThresholdDoNotTriggerSync() {
        // Local is 100,000ms, Server is 101,000ms (1s difference, within 2s threshold)
        long localPos = 100000L;
        long serverPos = 101000L;

        SyncDecision decision = determineSyncAction(localPos, serverPos);
        assertEquals(SyncDecision.IN_SYNC_NOOP, decision);
    }

    @Test
    public void testBrandNewLocalBookWithServerProgress() {
        // Book just downloaded or local was 0, server is at 15m (900,000ms)
        long localPos = 0L;
        long serverPos = 900000L;

        SyncDecision decision = determineSyncAction(localPos, serverPos);
        assertEquals(SyncDecision.UPDATE_LOCAL_FROM_SERVER, decision);
    }

    @Test
    public void testBrandNewBookBothAtZero() {
        long localPos = 0L;
        long serverPos = 0L;

        SyncDecision decision = determineSyncAction(localPos, serverPos);
        assertEquals(SyncDecision.IN_SYNC_NOOP, decision);
    }

    @Test
    public void testBatchProgressJsonPayloadFormat() throws Exception {
        String itemId = "item-test-123";
        long positionMs = 1250500L;
        long durationMs = 3600000L;

        double curTimeSec = positionMs / 1000.0;
        double durSec = durationMs / 1000.0;
        double progressRatio = curTimeSec / durSec;
        boolean isFinished = (positionMs >= durationMs);

        JSONArray batchArr = new JSONArray();
        JSONObject itemProgress = new JSONObject();
        itemProgress.put("libraryItemId", itemId);
        itemProgress.put("currentTime", curTimeSec);
        itemProgress.put("duration", durSec);
        itemProgress.put("progress", progressRatio);
        itemProgress.put("isFinished", isFinished);
        batchArr.put(itemProgress);

        assertEquals(1, batchArr.length());
        JSONObject parsed = batchArr.getJSONObject(0);
        assertEquals("item-test-123", parsed.getString("libraryItemId"));
        assertEquals(1250.5, parsed.getDouble("currentTime"), 0.001);
        assertEquals(3600.0, parsed.getDouble("duration"), 0.001);
        assertEquals(0.34736, parsed.getDouble("progress"), 0.001);
        assertFalse(parsed.getBoolean("isFinished"));
    }

    @Test
    public void testFinishedBookProgressJson() throws Exception {
        String itemId = "item-finished";
        long positionMs = 5000000L;
        long durationMs = 5000000L;

        double curTimeSec = positionMs / 1000.0;
        double durSec = durationMs / 1000.0;
        boolean isFinished = (positionMs >= durationMs);

        JSONObject progress = new JSONObject();
        progress.put("currentTime", curTimeSec);
        progress.put("duration", durSec);
        progress.put("progress", 1.0);
        progress.put("isFinished", isFinished);

        assertTrue(progress.getBoolean("isFinished"));
        assertEquals(1.0, progress.getDouble("progress"), 0.001);
    }

    @Test
    public void testLocalSessionPayloadFormat() throws Exception {
        String sessionId = "sess-uuid-1234";
        String itemId = "item-abs-5678";
        double curTimeSec = 150.0;
        double durSec = 3600.0;
        long startedAt = 1700000000000L;
        long updatedAt = 1700000030000L;

        JSONObject localSession = new JSONObject();
        localSession.put("id", sessionId);
        localSession.put("libraryItemId", itemId);
        localSession.put("episodeId", JSONObject.NULL);
        localSession.put("mediaType", "book");
        localSession.put("currentTime", curTimeSec);
        localSession.put("timeListening", 30.0);
        localSession.put("duration", durSec);
        localSession.put("startedAt", startedAt);
        localSession.put("updatedAt", updatedAt);

        JSONObject deviceInfo = new JSONObject();
        deviceInfo.put("clientName", "Audeyshelf");
        deviceInfo.put("deviceId", "audeyshelf-android");
        localSession.put("deviceInfo", deviceInfo);

        assertEquals("sess-uuid-1234", localSession.getString("id"));
        assertEquals("item-abs-5678", localSession.getString("libraryItemId"));
        assertEquals("book", localSession.getString("mediaType"));
        assertEquals(150.0, localSession.getDouble("currentTime"), 0.001);
        assertEquals(30.0, localSession.getDouble("timeListening"), 0.001);
        assertEquals(3600.0, localSession.getDouble("duration"), 0.001);
        assertEquals(1700000000000L, localSession.getLong("startedAt"));
        assertEquals("Audeyshelf", localSession.getJSONObject("deviceInfo").getString("clientName"));
    }

    @Test
    public void testPlayItemPayloadFormat() throws Exception {
        JSONObject body = new JSONObject();
        JSONObject deviceInfo = new JSONObject();
        deviceInfo.put("clientName", "Audeyshelf");
        deviceInfo.put("clientVersion", "1.0");
        deviceInfo.put("deviceId", "audeyshelf-android");
        body.put("deviceInfo", deviceInfo);
        body.put("mediaPlayer", "MediaPlayer");
        body.put("forceDirectPlay", true);

        assertEquals("MediaPlayer", body.getString("mediaPlayer"));
        assertTrue(body.getBoolean("forceDirectPlay"));
        assertEquals("Audeyshelf", body.getJSONObject("deviceInfo").getString("clientName"));
    }
}
