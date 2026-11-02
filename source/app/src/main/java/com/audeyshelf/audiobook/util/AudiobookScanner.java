package com.audeyshelf.audiobook.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.audeyshelf.audiobook.data.ResumeManager;
import com.audeyshelf.audiobook.model.Audiobook;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Scans directories for audiobook files (.m4b, .m4a, .mp3) and retrieves metadata.
 */
public class AudiobookScanner {
    private final Context context;
    private final ResumeManager resumeManager;
    private final ExecutorService executor;
    private final Handler mainHandler;

    public interface ScanCallback {
        void onProgress(int count);
        void onScanComplete(List<Audiobook> audiobooks);
    }

    public AudiobookScanner(Context context) {
        this.context = context.getApplicationContext();
        this.resumeManager = new ResumeManager(context);
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void scanDirectory(final String rootDirPath, final ScanCallback callback) {
        executor.execute(() -> {
            List<File> audioFiles = new ArrayList<>();
            File rootDir = new File(rootDirPath);
            if (rootDir.exists() && rootDir.isDirectory()) {
                collectAudioFiles(rootDir, audioFiles, 0);
            }

            List<Audiobook> books = new ArrayList<>();
            int count = 0;
            for (File file : audioFiles) {
                try {
                    Audiobook book = AudioMetadataHelper.extractAudiobookInfo(file);
                    long savedPos = resumeManager.getResumePosition(file.getAbsolutePath());
                    book.setResumePositionMs(savedPos);
                    books.add(book);
                    count++;
                    final int currentCount = count;
                    mainHandler.post(() -> {
                        if (callback != null) callback.onProgress(currentCount);
                    });
                } catch (Exception ignored) {
                }
            }

            // Sort alphabetically by title
            Collections.sort(books, (a, b) -> a.getTitle().compareToIgnoreCase(b.getTitle()));

            mainHandler.post(() -> {
                if (callback != null) callback.onScanComplete(books);
            });
        });
    }

    private void collectAudioFiles(File dir, List<File> result, int depth) {
        if (dir == null || !dir.exists() || !dir.canRead() || depth > 4 || result.size() >= 500) return;

        File[] files = null;
        try {
            files = dir.listFiles();
        } catch (Throwable ignored) {
        }
        if (files == null) return;

        for (File file : files) {
            String name = file.getName();
            if (file.isDirectory() && !name.startsWith(".")) {
                if ("Android".equalsIgnoreCase(name) || "data".equalsIgnoreCase(name) || "cache".equalsIgnoreCase(name)) {
                    continue;
                }
                collectAudioFiles(file, result, depth + 1);
            } else if (file.isFile() && isAudiobookFile(name)) {
                result.add(file);
            }
        }
    }

    public static boolean isAudiobookFile(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.US);
        return lower.endsWith(".m4b") || lower.endsWith(".m4a") || lower.endsWith(".mp3");
    }
}
