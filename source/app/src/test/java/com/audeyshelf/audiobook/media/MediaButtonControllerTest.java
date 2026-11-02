package com.audeyshelf.audiobook.media;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import com.audeyshelf.audiobook.data.SettingsManager;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MediaButtonControllerTest {

    private TestPlaybackController playbackController;
    private TestSettingsManager settingsManager;
    private MediaButtonController controller;

    static class TestPlaybackController implements MediaButtonController.PlaybackController {
        int playPauseCount = 0;
        int forwardSeconds = 0;
        int rewindSeconds = 0;
        int nextChapterCount = 0;
        int prevChapterCount = 0;
        int pauseOnlyCount = 0;

        @Override
        public void onPlayPause() {
            playPauseCount++;
        }

        @Override
        public void onForward(int seconds) {
            forwardSeconds += seconds;
        }

        @Override
        public void onRewind(int seconds) {
            rewindSeconds += seconds;
        }

        @Override
        public void onNextChapter() {
            nextChapterCount++;
        }

        @Override
        public void onPreviousChapter() {
            prevChapterCount++;
        }

        @Override
        public void onPauseOnly() {
            pauseOnlyCount++;
        }
    }

    /**
     * In-memory mock SettingsManager for isolated unit testing.
     */
    static class TestSettingsManager extends SettingsManager {
        private final Map<String, Object> storage = new HashMap<>();

        public TestSettingsManager() {
            super(null);
        }

        @Override
        public boolean isButtonControlEnabled() {
            Object val = storage.get("pref_button_control_enabled");
            return val instanceof Boolean ? (Boolean) val : true;
        }

        @Override
        public void setButtonControlEnabled(boolean enabled) {
            storage.put("pref_button_control_enabled", enabled);
        }

        @Override
        public int getButtonSingleTapAction() {
            Object val = storage.get("pref_button_single_tap_action");
            return val instanceof Integer ? (Integer) val : ACTION_PLAY_PAUSE;
        }

        @Override
        public void setButtonSingleTapAction(int action) {
            storage.put("pref_button_single_tap_action", action);
        }

        @Override
        public int getButtonDoubleTapAction() {
            Object val = storage.get("pref_button_double_tap_action");
            return val instanceof Integer ? (Integer) val : ACTION_FORWARD_10S;
        }

        @Override
        public void setButtonDoubleTapAction(int action) {
            storage.put("pref_button_double_tap_action", action);
        }

        @Override
        public int getButtonTripleTapAction() {
            Object val = storage.get("pref_button_triple_tap_action");
            return val instanceof Integer ? (Integer) val : ACTION_REWIND_10S;
        }

        @Override
        public void setButtonTripleTapAction(int action) {
            storage.put("pref_button_triple_tap_action", action);
        }
    }

    @Before
    public void setUp() {
        playbackController = new TestPlaybackController();
        settingsManager = new TestSettingsManager();
        controller = new MediaButtonController(null, settingsManager, playbackController, null, 350L);
    }

    @Test
    public void testIsPauseOrHeadsetButton() {
        assertTrue(MediaButtonController.isPauseOrHeadsetButton(KeyEvent.KEYCODE_HEADSETHOOK));
        assertTrue(MediaButtonController.isPauseOrHeadsetButton(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        assertTrue(MediaButtonController.isPauseOrHeadsetButton(KeyEvent.KEYCODE_MEDIA_PAUSE));
        assertTrue(MediaButtonController.isPauseOrHeadsetButton(KeyEvent.KEYCODE_MEDIA_PLAY));
        assertTrue(MediaButtonController.isPauseOrHeadsetButton(KeyEvent.KEYCODE_MEDIA_STOP));

        assertFalse(MediaButtonController.isPauseOrHeadsetButton(KeyEvent.KEYCODE_MEDIA_NEXT));
        assertFalse(MediaButtonController.isPauseOrHeadsetButton(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        assertFalse(MediaButtonController.isPauseOrHeadsetButton(KeyEvent.KEYCODE_VOLUME_UP));
    }

    @Test
    public void testSingleTapDefaultPlayPause() {
        assertTrue(controller.handleMediaKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 0));
        assertTrue(controller.handleMediaKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK, 0));

        // Flush pending single tap
        controller.flushPendingTaps();

        assertEquals(1, playbackController.playPauseCount);
        assertEquals(0, playbackController.forwardSeconds);
        assertEquals(0, playbackController.rewindSeconds);
    }

    @Test
    public void testDoubleTapDefaultForward10s() {
        // Tap 1
        assertTrue(controller.handleMediaKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 0));
        assertTrue(controller.handleMediaKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK, 0));

        // Tap 2
        assertTrue(controller.handleMediaKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 0));
        assertTrue(controller.handleMediaKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK, 0));

        // Flush pending double tap
        controller.flushPendingTaps();

        assertEquals(0, playbackController.playPauseCount);
        assertEquals(10, playbackController.forwardSeconds);
        assertEquals(0, playbackController.rewindSeconds);
    }

    @Test
    public void testTripleTapDefaultRewind10s() {
        // Tap 1
        assertTrue(controller.handleMediaKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 0));
        assertTrue(controller.handleMediaKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK, 0));

        // Tap 2
        assertTrue(controller.handleMediaKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 0));
        assertTrue(controller.handleMediaKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK, 0));

        // Tap 3 (triggers immediately upon 3 taps)
        assertTrue(controller.handleMediaKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 0));
        assertTrue(controller.handleMediaKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK, 0));

        assertEquals(0, playbackController.playPauseCount);
        assertEquals(0, playbackController.forwardSeconds);
        assertEquals(10, playbackController.rewindSeconds);
    }

    @Test
    public void testCustomDoubleTapJumpChapterForward() {
        // Configure double tap to jump chapter forward
        settingsManager.setButtonDoubleTapAction(SettingsManager.ACTION_NEXT_CHAPTER);

        // Tap 1
        controller.handleMediaKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0);
        controller.handleMediaKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0);

        // Tap 2
        controller.handleMediaKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0);
        controller.handleMediaKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0);

        controller.flushPendingTaps();

        assertEquals(0, playbackController.playPauseCount);
        assertEquals(0, playbackController.forwardSeconds);
        assertEquals(1, playbackController.nextChapterCount);
        assertEquals(0, playbackController.prevChapterCount);
    }

    @Test
    public void testCustomTripleTapJumpChapterBehind() {
        // Configure triple tap to jump chapter behind
        settingsManager.setButtonTripleTapAction(SettingsManager.ACTION_PREV_CHAPTER);

        // Tap 1
        controller.handleMediaKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0);
        controller.handleMediaKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0);

        // Tap 2
        controller.handleMediaKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0);
        controller.handleMediaKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0);

        // Tap 3
        controller.handleMediaKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0);
        controller.handleMediaKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0);

        assertEquals(0, playbackController.playPauseCount);
        assertEquals(0, playbackController.rewindSeconds);
        assertEquals(0, playbackController.nextChapterCount);
        assertEquals(1, playbackController.prevChapterCount);
    }

    @Test
    public void testButtonControlDisabled() {
        settingsManager.setButtonControlEnabled(false);

        assertFalse(controller.handleMediaKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 0));

        controller.flushPendingTaps();
        assertEquals(0, playbackController.playPauseCount);
        assertEquals(0, playbackController.forwardSeconds);
        assertEquals(0, playbackController.rewindSeconds);
    }

    @Test
    public void testDedicatedNextAndPreviousButtons() {
        assertTrue(controller.handleMediaKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0));
        assertEquals(1, playbackController.nextChapterCount);

        assertTrue(controller.handleMediaKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS, 0));
        assertEquals(1, playbackController.prevChapterCount);
    }

    @Test
    public void testLongPressRepeatsIgnored() {
        // First DOWN with repeat 0
        assertTrue(controller.handleMediaKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 0));
        assertEquals(1, controller.getTapCount());

        // Repeated DOWN with repeat 1 and 2
        assertTrue(controller.handleMediaKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 1));
        assertTrue(controller.handleMediaKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 2));

        // Still only 1 tap registered!
        assertEquals(1, controller.getTapCount());
    }
}
