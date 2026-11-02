package com.audeyshelf.audiobook.util;

import android.content.ContentResolver;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;

/**
 * Helper to handle file modifications and deletions across internal and external SD storage,
 * including Storage Access Framework (SAF) for Android 5.0+ removable SD cards.
 */
public class StorageFileHelper {
    private static final String TAG = "StorageFileHelper";

    /**
     * Checks if a file is located on removable secondary storage (e.g. /storage/extSdCard).
     */
    public static boolean isOnRemovableStorage(File file) {
        if (file == null) return false;
        String path = file.getAbsolutePath();
        if (path.startsWith("/storage/emulated/") || path.startsWith("/storage/sdcard0")) {
            return false;
        }
        File primaryExt = Environment.getExternalStorageDirectory();
        if (primaryExt != null && path.startsWith(primaryExt.getAbsolutePath())) {
            return false;
        }
        return path.startsWith("/storage/") || path.startsWith("/mnt/media_rw/") || path.startsWith("/mnt/extSdCard");
    }

    /**
     * Attempts to delete a file or directory.
     * Tries standard File.delete() first. If on SD card and that fails, attempts deletion
     * via DocumentFile / DocumentsContract using the provided SAF treeUriString.
     *
     * @return true if the file was actually deleted from disk, false otherwise.
     */
    public static boolean deleteAudiobookFile(Context context, File targetFile, String treeUriString) {
        if (targetFile == null || !targetFile.exists()) {
            return true;
        }

        // 1. Try standard Java File.delete() first
        boolean deleted = deleteStandardFileOrDir(targetFile);

        // 2. If standard delete failed and we have a SAF tree URI on API 21+
        if (!deleted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && treeUriString != null && !treeUriString.isEmpty()) {
            deleted = deleteViaSaf(context, targetFile, Uri.parse(treeUriString));
        }

        // 3. If successfully deleted, clean up parent directory if empty/only companions, and clean MediaStore
        if (deleted || !targetFile.exists()) {
            cleanupParentDirectoryIfEmpty(context, targetFile.getParentFile(), treeUriString);
            cleanMediaStoreEntry(context, targetFile.getAbsolutePath());
            return true;
        }

        return false;
    }

    private static boolean deleteStandardFileOrDir(File file) {
        if (file == null || !file.exists()) return true;
        try {
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) {
                    for (File child : children) {
                        deleteStandardFileOrDir(child);
                    }
                }
            }
            return file.delete();
        } catch (Exception e) {
            Log.w(TAG, "Standard delete failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean deleteViaSaf(Context context, File targetFile, Uri treeUri) {
        if (context == null || targetFile == null || treeUri == null) return false;
        ContentResolver cr = context.getContentResolver();

        // Approach A: Direct DocumentsContract URI
        try {
            String treeDocId = DocumentsContract.getTreeDocumentId(treeUri);
            int colonIdx = treeDocId.indexOf(':');
            String volumePrefix = colonIdx >= 0 ? treeDocId.substring(0, colonIdx + 1) : treeDocId + ":";

            String relativePath = extractRelativePathFromStorage(targetFile.getAbsolutePath());
            if (relativePath != null && !relativePath.isEmpty()) {
                String targetDocId = volumePrefix + relativePath;
                Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, targetDocId);
                if (DocumentsContract.deleteDocument(cr, docUri)) {
                    Log.d(TAG, "Deleted via DocumentsContract: " + targetFile.getAbsolutePath());
                    return true;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Direct documentUri delete failed, falling back to tree traversal: " + e.getMessage());
        }

        // Approach B: DocumentFile tree traversal
        try {
            DocumentFile current = DocumentFile.fromTreeUri(context, treeUri);
            if (current == null) return false;

            String relativePath = extractRelativePathFromStorage(targetFile.getAbsolutePath());
            if (relativePath == null || relativePath.isEmpty()) return false;

            // If treeUri was selected inside a subfolder, adjust relative path
            String treeDocId = DocumentsContract.getTreeDocumentId(treeUri);
            int colonIdx = treeDocId.indexOf(':');
            if (colonIdx >= 0 && colonIdx < treeDocId.length() - 1) {
                String subfolder = treeDocId.substring(colonIdx + 1);
                if (relativePath.startsWith(subfolder)) {
                    relativePath = relativePath.substring(subfolder.length());
                    if (relativePath.startsWith("/")) relativePath = relativePath.substring(1);
                }
            }

            String[] segments = relativePath.split("/");
            for (int i = 0; i < segments.length; i++) {
                String segment = segments[i];
                if (segment.isEmpty()) continue;
                DocumentFile next = current.findFile(segment);
                if (next == null) {
                    for (DocumentFile child : current.listFiles()) {
                        if (segment.equalsIgnoreCase(child.getName())) {
                            next = child;
                            break;
                        }
                    }
                }
                if (next == null) {
                    return !targetFile.exists();
                }
                current = next;
            }

            if (current != null && current.exists()) {
                boolean result = current.delete();
                Log.d(TAG, "Deleted via DocumentFile: " + result);
                return result;
            }
        } catch (Exception e) {
            Log.e(TAG, "DocumentFile traversal delete failed", e);
        }

        return !targetFile.exists();
    }

    private static String extractRelativePathFromStorage(String absolutePath) {
        if (absolutePath == null) return null;
        int storageIdx = absolutePath.indexOf("/storage/");
        if (storageIdx >= 0) {
            String afterStorage = absolutePath.substring(storageIdx + 9); // e.g. "extSdCard/Audiobooks/..."
            int nextSlash = afterStorage.indexOf('/');
            if (nextSlash >= 0) {
                return afterStorage.substring(nextSlash + 1); // e.g. "Audiobooks/..."
            }
        }
        return absolutePath;
    }

    private static void cleanupParentDirectoryIfEmpty(Context context, File parentDir, String treeUriString) {
        if (parentDir == null || !parentDir.exists() || !parentDir.isDirectory()) return;

        // Do not delete top-level folders like /storage/extSdCard/Audiobooks
        String parentName = parentDir.getName();
        if (parentName.equalsIgnoreCase("Audiobooks") || parentName.equalsIgnoreCase("Download") || parentName.equalsIgnoreCase("Music")) {
            return;
        }

        File[] files = parentDir.listFiles();
        if (files == null || files.length == 0) {
            deleteStandardFileOrDir(parentDir);
            if (parentDir.exists() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && treeUriString != null) {
                deleteViaSaf(context, parentDir, Uri.parse(treeUriString));
            }
            return;
        }

        // Check if only non-audio companion files remain (e.g. EmbeddedCover.jpg, position.sabp.dat)
        boolean hasAudio = false;
        for (File f : files) {
            String name = f.getName().toLowerCase();
            if (name.endsWith(".m4b") || name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".aac")) {
                hasAudio = true;
                break;
            }
        }

        if (!hasAudio) {
            for (File companion : files) {
                deleteStandardFileOrDir(companion);
                if (companion.exists() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && treeUriString != null) {
                    deleteViaSaf(context, companion, Uri.parse(treeUriString));
                }
            }
            deleteStandardFileOrDir(parentDir);
            if (parentDir.exists() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && treeUriString != null) {
                deleteViaSaf(context, parentDir, Uri.parse(treeUriString));
            }
        }
    }

    private static void cleanMediaStoreEntry(Context context, String filePath) {
        if (context == null || filePath == null) return;
        try {
            context.getContentResolver().delete(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Audio.Media.DATA + "=?",
                    new String[]{filePath}
            );
            context.getContentResolver().delete(
                    MediaStore.Files.getContentUri("external"),
                    "_data=?",
                    new String[]{filePath}
            );
            MediaScannerConnection.scanFile(context, new String[]{filePath}, null, null);
        } catch (Exception ignored) {
        }
    }

    /**
     * Accurately tests if a directory can be written to by creating and deleting a temporary probe file.
     * Avoids false positives from File.canWrite() on Android SD cards.
     */
    public static boolean isDirectoryActuallyWritable(File dir) {
        if (dir == null) return false;
        try {
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    return false;
                }
            }
            File probe = new File(dir, ".probe_" + System.currentTimeMillis());
            if (probe.createNewFile()) {
                probe.delete();
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Resolves or creates a DocumentFile for the target file on the SD card using the SAF tree URI.
     */
    public static DocumentFile getOrCreateDocumentFile(Context context, Uri treeUri, File targetDir, String fileName) {
        if (context == null || treeUri == null || targetDir == null || fileName == null) return null;
        try {
            DocumentFile current = DocumentFile.fromTreeUri(context, treeUri);
            if (current == null) return null;

            String relativeDir = extractRelativePathFromStorage(targetDir.getAbsolutePath());
            if (relativeDir != null && !relativeDir.isEmpty()) {
                String treeDocId = DocumentsContract.getTreeDocumentId(treeUri);
                int colonIdx = treeDocId.indexOf(':');
                if (colonIdx >= 0 && colonIdx < treeDocId.length() - 1) {
                    String subfolder = treeDocId.substring(colonIdx + 1);
                    if (relativeDir.startsWith(subfolder)) {
                        relativeDir = relativeDir.substring(subfolder.length());
                        if (relativeDir.startsWith("/")) relativeDir = relativeDir.substring(1);
                    }
                }

                String[] segments = relativeDir.split("/");
                for (String segment : segments) {
                    if (segment.isEmpty()) continue;
                    DocumentFile next = current.findFile(segment);
                    if (next == null) {
                        next = current.createDirectory(segment);
                    }
                    if (next == null) {
                        for (DocumentFile child : current.listFiles()) {
                            if (segment.equalsIgnoreCase(child.getName())) {
                                next = child;
                                break;
                            }
                        }
                    }
                    if (next != null) {
                        current = next;
                    }
                }
            }

            DocumentFile existing = current.findFile(fileName);
            if (existing != null && existing.exists()) {
                existing.delete();
            }

            String mime = "audio/mp4";
            if (fileName.toLowerCase().endsWith(".mp3")) {
                mime = "audio/mpeg";
            }

            return current.createFile(mime, fileName);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create document file on SD card", e);
            return null;
        }
    }
}
