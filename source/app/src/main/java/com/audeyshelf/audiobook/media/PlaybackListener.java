package com.audeyshelf.audiobook.media;

import com.audeyshelf.audiobook.model.Audiobook;
import com.audeyshelf.audiobook.model.Chapter;

import java.util.List;

/**
 * Callback interface for listening to playback events from PlaybackService.
 */
public interface PlaybackListener {
    void onPlaybackStateChanged(boolean isPlaying);
    void onProgressUpdate(long currentPositionMs, long durationMs);
    void onChapterChanged(int chapterIndex, Chapter chapter);
    void onAudiobookLoaded(Audiobook audiobook, List<Chapter> chapters);
    void onError(String message);
}
