package com.audeyshelf.audiobook.model;

import java.io.Serializable;

/**
 * Represents a chapter inside an M4B/MP4 audiobook file.
 */
public class Chapter implements Serializable {
    private final String title;
    private final long startMs;
    private long durationMs;
    private int index;

    public Chapter(String title, long startMs) {
        this.title = title != null ? title.trim() : "";
        this.startMs = Math.max(0, startMs);
        this.durationMs = 0;
        this.index = 0;
    }

    public Chapter(String title, long startMs, long durationMs, int index) {
        this.title = title != null ? title.trim() : "";
        this.startMs = Math.max(0, startMs);
        this.durationMs = Math.max(0, durationMs);
        this.index = index;
    }

    public String getTitle() {
        return title;
    }

    public long getStartMs() {
        return startMs;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = Math.max(0, durationMs);
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    @Override
    public String toString() {
        return "Chapter{" +
                "title='" + title + '\'' +
                ", startMs=" + startMs +
                ", durationMs=" + durationMs +
                ", index=" + index +
                '}';
    }
}
