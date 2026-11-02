package com.audeyshelf.audiobook.data;

import android.content.Context;

import com.audeyshelf.audiobook.model.Chapter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Caches extracted chapters to a small JSON file in context.getCacheDir().
 * Follows spec.md Section 4.3.
 */
public class ChapterCache {
    private final Context context;

    public ChapterCache(Context context) {
        this.context = context.getApplicationContext();
    }

    private File getCacheFile(String filePath) {
        int hash = filePath.hashCode();
        return new File(context.getCacheDir(), "chap_" + hash + ".json");
    }

    private File getTimestampFile(String filePath) {
        int hash = filePath.hashCode();
        return new File(context.getCacheDir(), "chap_" + hash + ".meta");
    }

    public List<Chapter> getCachedChapters(File sourceFile) {
        if (sourceFile == null || !sourceFile.exists()) return null;

        File cacheFile = getCacheFile(sourceFile.getAbsolutePath());
        File metaFile = getTimestampFile(sourceFile.getAbsolutePath());

        if (!cacheFile.exists() || !metaFile.exists()) {
            return null;
        }

        // Check if source file was modified
        try {
            long cachedTimestamp = Long.parseLong(readFileString(metaFile).trim());
            if (cachedTimestamp != sourceFile.lastModified()) {
                // Invalidate cache
                cacheFile.delete();
                metaFile.delete();
                return null;
            }

            String jsonStr = readFileString(cacheFile);
            JSONArray array = new JSONArray(jsonStr);
            List<Chapter> chapters = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                String title = obj.optString("title", "Chapter " + (i + 1));
                long startMs = obj.optLong("startMs", 0);
                long durationMs = obj.optLong("durationMs", 0);
                chapters.add(new Chapter(title, startMs, durationMs, i));
            }

            if (chapters.size() == 1 && "Entire Book".equalsIgnoreCase(chapters.get(0).getTitle())) {
                cacheFile.delete();
                metaFile.delete();
                return null;
            }

            return chapters.isEmpty() ? null : chapters;
        } catch (Exception e) {
            return null;
        }
    }

    public void saveChapters(File sourceFile, List<Chapter> chapters) {
        if (sourceFile == null || chapters == null || chapters.isEmpty()) return;

        try {
            JSONArray array = new JSONArray();
            for (Chapter ch : chapters) {
                JSONObject obj = new JSONObject();
                obj.put("title", ch.getTitle());
                obj.put("startMs", ch.getStartMs());
                obj.put("durationMs", ch.getDurationMs());
                array.put(obj);
            }

            File cacheFile = getCacheFile(sourceFile.getAbsolutePath());
            File metaFile = getTimestampFile(sourceFile.getAbsolutePath());

            writeFileString(cacheFile, array.toString());
            writeFileString(metaFile, String.valueOf(sourceFile.lastModified()));
        } catch (Exception ignored) {
        }
    }

    private String readFileString(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
        return sb.toString();
    }

    private void writeFileString(File file, String content) throws Exception {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            fos.write(content.getBytes("UTF-8"));
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
