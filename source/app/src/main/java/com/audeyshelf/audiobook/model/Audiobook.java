package com.audeyshelf.audiobook.model;

import java.io.Serializable;

/**
 * Represents an audiobook item with metadata and playback progress.
 */
public class Audiobook implements Serializable {
    private String filePath;
    private String title;
    private String author;
    private long durationMs;
    private long resumePositionMs;
    private long fileSize;
    private int chapterCount;

    // Server / Audiobookshelf integration fields
    private String serverItemId;
    private boolean isOnDevice = true;
    private String coverUrl;
    private long serverProgressMs = 0;
    private String publishedDate;

    public Audiobook(String filePath, String title, String author, long durationMs, long fileSize) {
        this.filePath = filePath;
        this.title = title;
        this.author = author;
        this.durationMs = durationMs;
        this.fileSize = fileSize;
        this.resumePositionMs = 0;
        this.chapterCount = 1;
        this.isOnDevice = true;
        this.publishedDate = "";
    }

    public Audiobook(String serverItemId, String title, String author, long durationMs, String coverUrl, long serverProgressMs) {
        this(serverItemId, title, author, durationMs, coverUrl, serverProgressMs, "");
    }

    public Audiobook(String serverItemId, String title, String author, long durationMs, String coverUrl, long serverProgressMs, String publishedDate) {
        this.serverItemId = serverItemId;
        this.filePath = "";
        this.title = title;
        this.author = author;
        this.durationMs = durationMs;
        this.coverUrl = coverUrl;
        this.serverProgressMs = serverProgressMs;
        this.resumePositionMs = serverProgressMs;
        this.fileSize = 0;
        this.chapterCount = 1;
        this.isOnDevice = false;
        this.publishedDate = publishedDate != null ? publishedDate : "";
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public boolean isServerItem() {
        return serverItemId != null && !serverItemId.trim().isEmpty();
    }

    public String getServerItemId() {
        return serverItemId;
    }

    public void setServerItemId(String serverItemId) {
        this.serverItemId = serverItemId;
    }

    public boolean isOnDevice() {
        return isOnDevice;
    }

    public void setOnDevice(boolean onDevice) {
        this.isOnDevice = onDevice;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }

    public String getPublishedDate() {
        return (publishedDate != null && !publishedDate.trim().isEmpty()) ? publishedDate.trim() : "Unknown";
    }

    public void setPublishedDate(String publishedDate) {
        this.publishedDate = publishedDate;
    }

    public long getServerProgressMs() {
        return serverProgressMs;
    }

    public void setServerProgressMs(long serverProgressMs) {
        this.serverProgressMs = serverProgressMs;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getTitle() {
        return (title != null && !title.trim().isEmpty()) ? title.trim() : "Untitled Audiobook";
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthor() {
        return (author != null && !author.trim().isEmpty()) ? author.trim() : "Unknown Author";
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public long getResumePositionMs() {
        return resumePositionMs;
    }

    public void setResumePositionMs(long resumePositionMs) {
        this.resumePositionMs = resumePositionMs;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public int getChapterCount() {
        return chapterCount;
    }

    public void setChapterCount(int chapterCount) {
        this.chapterCount = chapterCount;
    }

    public int getProgressPercentage() {
        if (durationMs <= 0) return 0;
        int pct = (int) ((resumePositionMs * 100L) / durationMs);
        return Math.min(100, Math.max(0, pct));
    }
}
