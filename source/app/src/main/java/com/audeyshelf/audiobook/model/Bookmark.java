package com.audeyshelf.audiobook.model;

import java.io.Serializable;

/**
 * Represents a persistent user bookmark for an audiobook.
 */
public class Bookmark implements Serializable {
    private final long id;
    private final String filePath;
    private final long positionMs;
    private final String label;
    private final long createdAt;

    public Bookmark(long id, String filePath, long positionMs, String label, long createdAt) {
        this.id = id;
        this.filePath = filePath;
        this.positionMs = positionMs;
        this.label = label;
        this.createdAt = createdAt;
    }

    public Bookmark(String filePath, long positionMs, String label) {
        this(-1, filePath, positionMs, label, System.currentTimeMillis() / 1000L);
    }

    public long getId() {
        return id;
    }

    public String getFilePath() {
        return filePath;
    }

    public long getPositionMs() {
        return positionMs;
    }

    public String getLabel() {
        return label;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "Bookmark{" +
                "id=" + id +
                ", filePath='" + filePath + '\'' +
                ", positionMs=" + positionMs +
                ", label='" + label + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}
