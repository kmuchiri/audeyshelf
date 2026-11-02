package com.audeyshelf.audiobook.util;

import android.graphics.Bitmap;
import androidx.collection.LruCache;

/**
 * Memory-efficient LruCache sized to 1/8th of application max heap memory.
 * Follows spec.md Section 8.3.
 */
public class ImageCache {
    private static ImageCache instance;
    private final LruCache<String, Bitmap> memoryCache;

    private ImageCache() {
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024); // in KB
        final int cacheSize = maxMemory / 8; // 1/8th of max heap

        memoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                // Return size in KB
                return bitmap.getByteCount() / 1024;
            }
        };
    }

    public static synchronized ImageCache getInstance() {
        if (instance == null) {
            instance = new ImageCache();
        }
        return instance;
    }

    public void put(String key, Bitmap bitmap) {
        if (key != null && bitmap != null) {
            memoryCache.put(key, bitmap);
        }
    }

    public Bitmap get(String key) {
        if (key == null) return null;
        return memoryCache.get(key);
    }

    public void clear() {
        memoryCache.evictAll();
    }
}
