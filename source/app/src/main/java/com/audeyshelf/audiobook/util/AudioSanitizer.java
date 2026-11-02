package com.audeyshelf.audiobook.util;

import android.util.Log;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;

/**
 * Sanitizes M4B / M4A container metadata for compatibility with legacy Android devices.
 * Specifically handles the Stagefright MPEG4Extractor crash on Android 5.1.1 and earlier,
 * where an AAC AudioSpecificConfig with 2 bytes has dependsOnCoreCoder=1, causing
 * ABitReader.cpp CHECK_GT(mSize, 0u) assertion failure and crashing mediaserver.
 */
public final class AudioSanitizer {

    private static final String TAG = "AudioSanitizer";
    private static final Charset US_ASCII = Charset.forName("US-ASCII");

    public static final class PatchInfo {
        public final long fileOffset;
        public final byte originalByte;
        public final byte fixedByte;

        public PatchInfo(long fileOffset, byte originalByte, byte fixedByte) {
            this.fileOffset = fileOffset;
            this.originalByte = originalByte;
            this.fixedByte = fixedByte;
        }
    }

    private AudioSanitizer() {
    }

    /**
     * Inspects the audio file in read-only mode and returns patch metadata if the file
     * has an invalid dependsOnCoreCoder bit in a 2-byte AAC AudioSpecificConfig.
     * Safe to call on read-only files.
     *
     * @param file the audiobook file to inspect
     * @return PatchInfo describing the file offset and byte fix, or null if clean.
     */
    public static PatchInfo getPatchInfoIfNeeded(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return null;
        }

        String name = file.getName().toLowerCase();
        if (!name.endsWith(".m4b") && !name.endsWith(".m4a") && !name.endsWith(".mp4")) {
            return null;
        }

        RandomAccessFile raf = null;
        try {
            long fileLength = file.length();
            if (fileLength < 16) {
                return null;
            }

            raf = new RandomAccessFile(file, "r");
            byte[] header = new byte[8];
            long pos = 0;

            // Search top-level atoms for 'moov'
            while (pos + 8 <= fileLength) {
                raf.seek(pos);
                if (raf.read(header) < 8) break;

                long size = ((header[0] & 0xFFL) << 24)
                        | ((header[1] & 0xFFL) << 16)
                        | ((header[2] & 0xFFL) << 8)
                        | (header[3] & 0xFFL);
                String type = new String(header, 4, 4, US_ASCII);

                long atomHeaderSize = 8;
                if (size == 1) { // 64-bit size
                    byte[] ext = new byte[8];
                    if (raf.read(ext) < 8) break;
                    size = 0;
                    for (int i = 0; i < 8; i++) {
                        size = (size << 8) | (ext[i] & 0xFFL);
                    }
                    atomHeaderSize = 16;
                } else if (size == 0) {
                    size = fileLength - pos;
                }

                if (size < atomHeaderSize) break;

                if ("moov".equals(type)) {
                    long moovPayloadStart = pos + atomHeaderSize;
                    long moovPayloadSize = size - atomHeaderSize;
                    return scanMoovForPatch(raf, moovPayloadStart, moovPayloadSize);
                }

                pos += size;
            }
        } catch (Throwable t) {
            Log.w(TAG, "AudioSanitizer inspection skipped or failed: " + t.getMessage());
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    /**
     * Attempts to apply the patch in-place.
     *
     * @param file the file to modify
     * @param patch the patch descriptor
     * @return true if successfully modified in-place, false if failed (e.g. read-only storage).
     */
    public static boolean applyInPlacePatch(File file, PatchInfo patch) {
        if (file == null || patch == null) return false;

        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "rw");
            raf.seek(patch.fileOffset);
            raf.write(patch.fixedByte);
            Log.i(TAG, "Patched corrupt dependsOnCoreCoder in-place at offset " + patch.fileOffset +
                    ": 0x" + Integer.toHexString(patch.originalByte & 0xFF) + " -> 0x" + Integer.toHexString(patch.fixedByte & 0xFF));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "AudioSanitizer in-place patch failed (likely read-only storage): " + t.getMessage());
            return false;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Inspects the audio file and, if it is an MP4/M4B/M4A file with an invalid
     * dependsOnCoreCoder bit in a 2-byte AAC AudioSpecificConfig, attempts to clear the bit in-place.
     * Safe to call on any file: if file is read-only or not an MP4, returns false.
     *
     * @param file the audiobook file to inspect
     * @return true if the file was modified in-place, false otherwise
     */
    public static boolean sanitizeM4bIfNeeded(File file) {
        PatchInfo patch = getPatchInfoIfNeeded(file);
        if (patch != null) {
            return applyInPlacePatch(file, patch);
        }
        return false;
    }

    private static PatchInfo scanMoovForPatch(RandomAccessFile raf, long start, long size) throws Exception {
        final int BUFFER_SIZE = 64 * 1024;
        byte[] buffer = new byte[BUFFER_SIZE];
        long current = start;
        long end = start + size;

        while (current < end) {
            long remaining = end - current;
            int toRead = (int) Math.min(BUFFER_SIZE, remaining);
            raf.seek(current);
            int read = raf.read(buffer, 0, toRead);
            if (read <= 0) break;

            // Search for 'esds' atom
            for (int i = 0; i < read - 4; i++) {
                if (buffer[i] == 'e' && buffer[i + 1] == 's' && buffer[i + 2] == 'd' && buffer[i + 3] == 's') {
                    long esdsOffset = current + i;
                    PatchInfo patch = checkEsds(raf, esdsOffset);
                    if (patch != null) {
                        return patch;
                    }
                }
            }

            // Step with overlap to avoid missing atom spanning buffer boundary
            if (read < BUFFER_SIZE) break;
            current += (read - 64);
        }

        return null;
    }

    private static PatchInfo checkEsds(RandomAccessFile raf, long esdsOffset) {
        try {
            // Read up to 128 bytes of esds atom
            raf.seek(esdsOffset);
            byte[] esdsBuf = new byte[128];
            int n = raf.read(esdsBuf);
            if (n < 16) return null;

            // Search for DecSpecificInfoTag (0x05)
            for (int i = 4; i < n - 6; i++) {
                if (esdsBuf[i] == 0x05) {
                    int p = i + 1;
                    int length = 0;
                    for (int k = 0; k < 4 && p < n; k++) {
                        int b = esdsBuf[p++] & 0xFF;
                        length = (length << 7) | (b & 0x7F);
                        if ((b & 0x80) == 0) break;
                    }

                    // A 2-byte AudioSpecificConfig cannot have dependsOnCoreCoder=1
                    // because coreCoderDelay (14 bits) is missing from the stream.
                    if (length == 2 && p + 1 < n) {
                        int b1 = esdsBuf[p + 1] & 0xFF;
                        if ((b1 & 0x02) != 0) { // bit 1 is dependsOnCoreCoder
                            long targetFileOffset = esdsOffset + p + 1;
                            byte fixedByte = (byte) (b1 & ~0x02);
                            return new PatchInfo(targetFileOffset, (byte) b1, fixedByte);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed inspecting esds: " + t.getMessage());
        }
        return null;
    }
}
