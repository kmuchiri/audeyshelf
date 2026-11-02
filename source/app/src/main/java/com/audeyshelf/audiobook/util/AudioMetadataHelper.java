package com.audeyshelf.audiobook.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;

import com.audeyshelf.audiobook.model.Audiobook;

import java.io.File;

/**
 * Extracts metadata and downsampled cover art from media files.
 * Follows spec.md Section 8.3 for efficient image loading.
 */
public class AudioMetadataHelper {

    public static Audiobook extractAudiobookInfo(File file) {
        String path = file.getAbsolutePath();
        String title = null;
        String author = null;
        String album = null;
        long durationMs = 0;
        long fileSize = file.length();

        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(path);
            title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            author = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (author == null) {
                author = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR);
            }
            if (author == null) {
                author = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
            }
            if (author == null) {
                author = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER);
            }
            if (author == null) {
                author = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_WRITER);
            }

            String durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durStr != null) {
                durationMs = Long.parseLong(durStr);
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                mmr.release();
            } catch (Throwable ignored) {
            }
        }

        // If title is generic track/chapter name, prefer album (which is usually the audiobook title)
        if (title != null && (title.matches("(?i)^(track|chapter|part|disc|disk)\\s*\\d+.*") || title.matches("^\\d{1,3}$"))) {
            if (album != null && !album.trim().isEmpty()) {
                title = album.trim();
            }
        }

        // Fallback title to album or filename without extension
        if (title == null || title.trim().isEmpty()) {
            if (album != null && !album.trim().isEmpty()) {
                title = album.trim();
            } else {
                title = file.getName();
                int dot = title.lastIndexOf('.');
                if (dot > 0) {
                    title = title.substring(0, dot);
                }
                if (title.contains("_") && !title.contains(" ")) {
                    title = title.replace('_', ' ');
                }
            }
        }

        if (author == null || author.trim().isEmpty()) {
            author = "Unknown Author";
        }

        return new Audiobook(path, title, author, durationMs, fileSize);
    }

    public static Bitmap loadCoverArt(String filePath, int targetWidth, int targetHeight) {
        if (filePath == null) return null;

        // Check memory cache first
        String cacheKey = filePath + "_" + targetWidth + "x" + targetHeight;
        ImageCache cache = ImageCache.getInstance();
        Bitmap cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(filePath);
            byte[] pictureBytes = mmr.getEmbeddedPicture();
            if (pictureBytes == null || pictureBytes.length == 0) {
                return null;
            }

            // Decode bounds only first
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(pictureBytes, 0, pictureBytes.length, options);

            // Calculate inSampleSize
            options.inSampleSize = calculateInSampleSize(options, targetWidth, targetHeight);
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888; // Full clarity and fidelity

            Bitmap bitmap = BitmapFactory.decodeByteArray(pictureBytes, 0, pictureBytes.length, options);
            if (bitmap != null) {
                cache.put(cacheKey, bitmap);
            }
            return bitmap;
        } catch (Throwable e) {
            return null;
        } finally {
            try {
                mmr.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }
}
