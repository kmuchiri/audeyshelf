package com.audeyshelf.audiobook.media;

import android.content.Context;

import com.coremedia.iso.IsoFile;
import com.coremedia.iso.boxes.Box;
import com.coremedia.iso.boxes.ChunkOffset64BitBox;
import com.coremedia.iso.boxes.ChunkOffsetBox;
import com.coremedia.iso.boxes.Container;
import com.coremedia.iso.boxes.EditBox;
import com.coremedia.iso.boxes.EditListBox;
import com.coremedia.iso.boxes.MediaBox;
import com.coremedia.iso.boxes.MediaHeaderBox;
import com.coremedia.iso.boxes.MediaInformationBox;
import com.coremedia.iso.boxes.MovieBox;
import com.coremedia.iso.boxes.SampleSizeBox;
import com.coremedia.iso.boxes.SampleTableBox;
import com.coremedia.iso.boxes.SampleToChunkBox;
import com.coremedia.iso.boxes.TimeToSampleBox;
import com.coremedia.iso.boxes.TrackBox;
import com.coremedia.iso.boxes.TrackReferenceBox;
import com.coremedia.iso.boxes.UnknownBox;
import com.audeyshelf.audiobook.data.ChapterCache;
import com.audeyshelf.audiobook.model.Chapter;
import com.mp4parser.iso14496.part12.TrackReferenceTypeBox;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts chapter metadata from M4B/MP4 files (QuickTime chapter tracks, Nero chpl boxes,
 * and description timestamp markers) with single-chapter fallback.
 * Follows spec.md Section 5.
 */
public class ChapterExtractor {

    private static final String TAG = "ChapterExtractor";
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final Charset UTF_16BE = Charset.forName("UTF-16BE");
    private static final Charset UTF_16LE = Charset.forName("UTF-16LE");

    private static final Pattern TIMESTAMP_LINE_PATTERN = Pattern.compile(
            "^(?:\\d+\\s*[.)-]\\s*)?(\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\.\\d+)?)\\s*[-–—:]?\\s*(.*)$"
    );

    public static List<Chapter> extractChapters(Context context, File m4bFile, long totalDurationMs) {
        if (m4bFile == null || !m4bFile.exists()) {
            return fallbackSingleChapter(totalDurationMs);
        }

        Log.d(TAG, "extractChapters starting for: " + m4bFile.getAbsolutePath() + " (size=" + m4bFile.length() + ")");

        // 1. Check ChapterCache first (spec.md Section 4.3)
        ChapterCache cache = (context != null) ? new ChapterCache(context) : null;
        List<Chapter> cached = (cache != null) ? cache.getCachedChapters(m4bFile) : null;
        if (cached != null && !cached.isEmpty()) {
            // Ignore cached results if it's only 1 fallback chapter ("Entire Book")
            if (cached.size() > 1 || !"Entire Book".equalsIgnoreCase(cached.get(0).getTitle())) {
                return cached;
            }
        }

        long movieTimescale = 1000;
        // Check moov for movie timescale and duration if totalDurationMs <= 0
        try {
            IsoFile iso = new IsoFile(m4bFile.getAbsolutePath());
            try {
                MovieBox moov = iso.getMovieBox();
                if (moov != null && moov.getMovieHeaderBox() != null) {
                    movieTimescale = moov.getMovieHeaderBox().getTimescale();
                    if (movieTimescale > 0) {
                        long mvDur = (moov.getMovieHeaderBox().getDuration() * 1000L) / movieTimescale;
                        if (totalDurationMs <= 0 && mvDur > 0) {
                            totalDurationMs = mvDur;
                        }
                    }
                }
            } finally {
                iso.close();
            }
        } catch (Throwable ignored) {
        }

        List<Chapter> qtChapters = Collections.emptyList();
        List<Chapter> neroChapters = Collections.emptyList();

        try {
            // 2. Try QuickTime Chapter Track (spec.md Section 5.2)
            qtChapters = extractQuickTimeChapters(m4bFile);
            Log.d(TAG, "extractQuickTimeChapters returned: " + (qtChapters != null ? qtChapters.size() : 0) + " chapters");
        } catch (Throwable t) {
            Log.w(TAG, "extractQuickTimeChapters failed", t);
        }

        try {
            // 3. Try Nero chpl (spec.md Section 5.3)
            neroChapters = extractNeroChapters(m4bFile, movieTimescale, totalDurationMs);
            Log.d(TAG, "extractNeroChapters returned: " + (neroChapters != null ? neroChapters.size() : 0) + " chapters");
        } catch (Throwable t) {
            Log.w(TAG, "extractNeroChapters failed", t);
        }

        // Choose the best chapter list:
        List<Chapter> chapters = null;
        if (qtChapters.size() > 1 && neroChapters.size() > 1) {
            chapters = (neroChapters.size() > qtChapters.size()) ? neroChapters : qtChapters;
        } else if (qtChapters.size() > 1) {
            chapters = qtChapters;
        } else if (neroChapters.size() > 1) {
            chapters = neroChapters;
        } else if (!qtChapters.isEmpty()) {
            chapters = qtChapters;
        } else if (!neroChapters.isEmpty()) {
            chapters = neroChapters;
        }

        // 4. Try Description / Comment timestamps
        if (chapters == null || chapters.isEmpty()) {
            try {
                chapters = extractDescriptionChapters(m4bFile, totalDurationMs);
                Log.d(TAG, "extractDescriptionChapters returned: " + (chapters != null ? chapters.size() : 0) + " chapters");
            } catch (Throwable t) {
                Log.w(TAG, "extractDescriptionChapters failed", t);
            }
        }

        // 5. Fallback if no chapter metadata found
        if (chapters == null || chapters.isEmpty()) {
            Log.d(TAG, "Using fallbackSingleChapter");
            chapters = fallbackSingleChapter(totalDurationMs);
        } else {
            // Sort by startMs without using Long.compare for API 16 compatibility
            Collections.sort(chapters, new Comparator<Chapter>() {
                @Override
                public int compare(Chapter a, Chapter b) {
                    long aStart = a.getStartMs();
                    long bStart = b.getStartMs();
                    return (aStart < bStart) ? -1 : ((aStart == bStart) ? 0 : 1);
                }
            });

            // If the first chapter is close to 0 (e.g. <= 2000ms), snap to 0
            if (!chapters.isEmpty() && chapters.get(0).getStartMs() > 0 && chapters.get(0).getStartMs() <= 2000) {
                Chapter first = chapters.get(0);
                chapters.set(0, new Chapter(first.getTitle(), 0, first.getDurationMs(), first.getIndex()));
            }

            // Assign index and durations
            for (int i = 0; i < chapters.size(); i++) {
                Chapter ch = chapters.get(i);
                ch.setIndex(i);
                if (i < chapters.size() - 1) {
                    long nextStart = chapters.get(i + 1).getStartMs();
                    ch.setDurationMs(Math.max(0, nextStart - ch.getStartMs()));
                } else if (totalDurationMs > ch.getStartMs()) {
                    ch.setDurationMs(totalDurationMs - ch.getStartMs());
                }
            }
        }

        // Save to cache (don't cache fallback "Entire Book")
        if (cache != null && (chapters.size() > 1 || !"Entire Book".equalsIgnoreCase(chapters.get(0).getTitle()))) {
            cache.saveChapters(m4bFile, chapters);
        }
        return chapters;
    }

    private static List<Chapter> fallbackSingleChapter(long totalDurationMs) {
        List<Chapter> fallback = new ArrayList<>();
        fallback.add(new Chapter("Entire Book", 0, Math.max(0, totalDurationMs), 0));
        return fallback;
    }

    /**
     * Extracts QuickTime chapter text track samples.
     */
    public static List<Chapter> extractQuickTimeChapters(File m4bFile) {
        IsoFile isoFile = null;
        RandomAccessFile raf = null;
        try {
            isoFile = new IsoFile(m4bFile.getAbsolutePath());
            MovieBox moov = isoFile.getMovieBox();
            if (moov == null) return Collections.emptyList();

            long movieTimescale = 1000;
            if (moov.getMovieHeaderBox() != null && moov.getMovieHeaderBox().getTimescale() > 0) {
                movieTimescale = moov.getMovieHeaderBox().getTimescale();
            }

            TrackBox chapterTrack = findChapterTrack(moov);
            if (chapterTrack == null) return Collections.emptyList();

            MediaBox mdia = chapterTrack.getMediaBox();
            if (mdia == null) return Collections.emptyList();

            MediaHeaderBox mdhd = mdia.getMediaHeaderBox();
            long timescale = mdhd != null ? mdhd.getTimescale() : 1000;
            if (timescale <= 0) timescale = 1000;

            SampleTableBox stbl = getSampleTableBox(chapterTrack);
            if (stbl == null) return Collections.emptyList();

            long[] sampleSizes = getSampleSizes(stbl);
            if (sampleSizes == null || sampleSizes.length == 0) {
                return Collections.emptyList();
            }

            long[] sampleOffsets = getSampleOffsets(stbl, sampleSizes);
            if (sampleOffsets == null || sampleOffsets.length == 0) {
                return Collections.emptyList();
            }

            int count = Math.min(sampleOffsets.length, sampleSizes.length);
            if (count == 0) return Collections.emptyList();

            long[] sampleStartMs = getSampleStartTimes(stbl, timescale, count);
            applyEditList(chapterTrack, movieTimescale, timescale, sampleStartMs);

            List<Chapter> chapters = new ArrayList<>(count);
            raf = new RandomAccessFile(m4bFile, "r");

            for (int i = 0; i < count; i++) {
                long offset = sampleOffsets[i];
                int size = (int) sampleSizes[i];
                if (size <= 0 || offset < 0 || offset >= m4bFile.length()) continue;

                int readSize = Math.min(size, 65536);
                raf.seek(offset);
                byte[] buf = new byte[readSize];
                raf.readFully(buf);

                String title = decodeTextSample(buf, size);
                if (title.isEmpty()) {
                    title = "Chapter " + (i + 1);
                }

                long startMs = (sampleStartMs != null && i < sampleStartMs.length) ? sampleStartMs[i] : 0;
                chapters.add(new Chapter(title, startMs));
            }

            return chapters;
        } catch (Exception e) {
            return Collections.emptyList();
        } finally {
            try {
                if (raf != null) raf.close();
            } catch (Exception ignored) {
            }
            try {
                if (isoFile != null) isoFile.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static TrackBox findChapterTrack(MovieBox moov) {
        if (moov == null) return null;
        List<TrackBox> tracks = moov.getBoxes(TrackBox.class);
        if (tracks == null || tracks.isEmpty()) return null;

        // 1. Look for tref -> chap references in all tracks
        for (TrackBox track : tracks) {
            List<TrackReferenceBox> trefBoxes = track.getBoxes(TrackReferenceBox.class);
            if (trefBoxes != null) {
                for (TrackReferenceBox tref : trefBoxes) {
                    for (Box box : tref.getBoxes()) {
                        if ("chap".equals(box.getType())) {
                            List<Long> chapTrackIds = getTrackIdsFromChapBox(box);
                            for (long chapTrackId : chapTrackIds) {
                                for (TrackBox candidate : tracks) {
                                    if (candidate.getTrackHeaderBox() != null &&
                                            candidate.getTrackHeaderBox().getTrackId() == chapTrackId) {
                                        return candidate;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Look for tracks with text/subtitle handler or text sample description
        TrackBox bestCandidate = null;
        for (TrackBox track : tracks) {
            MediaBox mdia = track.getMediaBox();
            if (mdia == null) continue;

            String handlerType = mdia.getHandlerBox() != null ? mdia.getHandlerBox().getHandlerType() : "";
            String handlerName = mdia.getHandlerBox() != null ? mdia.getHandlerBox().getName() : "";
            if (handlerName == null) handlerName = "";

            boolean isTextOrSub = "text".equalsIgnoreCase(handlerType)
                    || "sbtl".equalsIgnoreCase(handlerType)
                    || "subp".equalsIgnoreCase(handlerType)
                    || "clcp".equalsIgnoreCase(handlerType)
                    || handlerName.toLowerCase().contains("chapter")
                    || handlerName.toLowerCase().contains("subtitle");

            SampleTableBox stbl = getSampleTableBox(track);
            if (!isTextOrSub && stbl != null && stbl.getSampleDescriptionBox() != null) {
                for (Box entry : stbl.getSampleDescriptionBox().getBoxes()) {
                    String type = entry.getType();
                    if ("text".equalsIgnoreCase(type) || "tx3g".equalsIgnoreCase(type)
                            || "c608".equalsIgnoreCase(type) || "wvtt".equalsIgnoreCase(type)) {
                        isTextOrSub = true;
                        break;
                    }
                }
            }

            // Exclude audio and video tracks
            if ("soun".equalsIgnoreCase(handlerType) || "vide".equalsIgnoreCase(handlerType)) {
                continue;
            }

            if (isTextOrSub) {
                long sampleCount = getSampleCount(stbl);
                if (sampleCount > 0) {
                    return track;
                }
                if (bestCandidate == null) {
                    bestCandidate = track;
                }
            }
        }

        return bestCandidate;
    }

    private static List<Long> getTrackIdsFromChapBox(Box box) {
        List<Long> trackIds = new ArrayList<>();
        if (box instanceof TrackReferenceTypeBox) {
            long[] ids = ((TrackReferenceTypeBox) box).getTrackIds();
            if (ids != null) {
                for (long id : ids) trackIds.add(id);
            }
        } else {
            ByteBuffer buf = getBoxData(box);
            if (buf != null) {
                buf.rewind();
                while (buf.remaining() >= 4) {
                    trackIds.add((long) buf.getInt() & 0xFFFFFFFFL);
                }
            }
        }
        return trackIds;
    }

    private static SampleTableBox getSampleTableBox(TrackBox track) {
        if (track == null) return null;
        SampleTableBox stbl = track.getSampleTableBox();
        if (stbl != null) return stbl;

        MediaBox mdia = track.getMediaBox();
        if (mdia != null) {
            MediaInformationBox minf = mdia.getMediaInformationBox();
            if (minf != null) {
                return minf.getSampleTableBox();
            }
        }
        return null;
    }

    private static long getSampleCount(SampleTableBox stbl) {
        if (stbl == null) return 0;
        SampleSizeBox stsz = stbl.getSampleSizeBox();
        if (stsz != null) {
            return stsz.getSampleCount();
        }
        return 0;
    }

    private static long[] getChunkOffsets(SampleTableBox stbl) {
        if (stbl == null) return new long[0];
        ChunkOffsetBox stco = stbl.getChunkOffsetBox();
        if (stco != null && stco.getChunkOffsets() != null && stco.getChunkOffsets().length > 0) {
            return stco.getChunkOffsets();
        }
        List<ChunkOffset64BitBox> co64List = stbl.getBoxes(ChunkOffset64BitBox.class);
        if (co64List != null && !co64List.isEmpty() && co64List.get(0).getChunkOffsets() != null) {
            return co64List.get(0).getChunkOffsets();
        }
        for (Box box : stbl.getBoxes()) {
            if ("co64".equals(box.getType())) {
                ByteBuffer buf = getBoxData(box);
                if (buf != null && buf.remaining() >= 8) {
                    buf.getInt(); // version + flags
                    int entryCount = buf.getInt();
                    if (entryCount > 0 && entryCount <= 1_000_000 && buf.remaining() >= entryCount * 8) {
                        long[] offsets = new long[entryCount];
                        for (int i = 0; i < entryCount; i++) {
                            offsets[i] = buf.getLong();
                        }
                        return offsets;
                    }
                }
            }
        }
        return new long[0];
    }

    private static long[] getSampleSizes(SampleTableBox stbl) {
        if (stbl == null) return new long[0];
        SampleSizeBox stsz = stbl.getSampleSizeBox();
        if (stsz != null) {
            if (stsz.getSampleSize() > 0) {
                int count = (int) stsz.getSampleCount();
                long[] sizes = new long[count];
                long uniformSize = stsz.getSampleSize();
                for (int i = 0; i < count; i++) {
                    sizes[i] = uniformSize;
                }
                return sizes;
            } else if (stsz.getSampleSizes() != null) {
                return stsz.getSampleSizes();
            }
        }
        for (Box box : stbl.getBoxes()) {
            if ("stsz".equals(box.getType())) {
                ByteBuffer buf = getBoxData(box);
                if (buf != null && buf.remaining() >= 12) {
                    buf.getInt(); // version + flags
                    long sampleSize = buf.getInt() & 0xFFFFFFFFL;
                    int count = buf.getInt();
                    if (count > 0 && count <= 1_000_000) {
                        long[] sizes = new long[count];
                        if (sampleSize > 0) {
                            for (int i = 0; i < count; i++) sizes[i] = sampleSize;
                        } else if (buf.remaining() >= count * 4) {
                            for (int i = 0; i < count; i++) sizes[i] = buf.getInt() & 0xFFFFFFFFL;
                        }
                        return sizes;
                    }
                }
            }
        }
        return new long[0];
    }

    private static long[] getSampleOffsets(SampleTableBox stbl, long[] sampleSizes) {
        if (stbl == null || sampleSizes == null || sampleSizes.length == 0) {
            return new long[0];
        }

        long[] chunkOffsets = getChunkOffsets(stbl);
        if (chunkOffsets == null || chunkOffsets.length == 0) {
            return new long[0];
        }

        int sampleCount = sampleSizes.length;
        long[] sampleOffsets = new long[sampleCount];

        SampleToChunkBox stsc = stbl.getSampleToChunkBox();
        if (stsc == null || stsc.getEntries() == null || stsc.getEntries().isEmpty()) {
            int count = Math.min(sampleCount, chunkOffsets.length);
            for (int i = 0; i < count; i++) {
                sampleOffsets[i] = chunkOffsets[i];
            }
            return sampleOffsets;
        }

        List<SampleToChunkBox.Entry> entries = stsc.getEntries();
        int entryIdx = 0;
        int sampleIdx = 0;

        for (int chunkIdx = 0; chunkIdx < chunkOffsets.length && sampleIdx < sampleCount; chunkIdx++) {
            long chunkNumber = chunkIdx + 1; // 1-indexed

            while (entryIdx + 1 < entries.size() && chunkNumber >= entries.get(entryIdx + 1).getFirstChunk()) {
                entryIdx++;
            }

            long samplesInChunk = entries.get(entryIdx).getSamplesPerChunk();
            long currentOffset = chunkOffsets[chunkIdx];

            for (int s = 0; s < samplesInChunk && sampleIdx < sampleCount; s++) {
                sampleOffsets[sampleIdx] = currentOffset;
                currentOffset += sampleSizes[sampleIdx];
                sampleIdx++;
            }
        }

        return sampleOffsets;
    }

    private static long[] getSampleStartTimes(SampleTableBox stbl, long timescale, int sampleCount) {
        if (sampleCount <= 0) return new long[0];
        long[] startTimesMs = new long[sampleCount];

        if (stbl == null) return startTimesMs;
        TimeToSampleBox stts = stbl.getTimeToSampleBox();
        if (stts == null || stts.getEntries() == null || stts.getEntries().isEmpty()) {
            return startTimesMs;
        }

        if (timescale <= 0) timescale = 1000;

        long currentTicks = 0;
        int sampleIdx = 0;
        for (TimeToSampleBox.Entry entry : stts.getEntries()) {
            long count = entry.getCount();
            long delta = entry.getDelta();
            for (int i = 0; i < count && sampleIdx < sampleCount; i++) {
                startTimesMs[sampleIdx] = (currentTicks * 1000L) / timescale;
                currentTicks += delta;
                sampleIdx++;
            }
        }
        return startTimesMs;
    }

    private static void applyEditList(TrackBox track, long movieTimescale, long mediaTimescale, long[] sampleStartMs) {
        if (track == null || sampleStartMs == null || sampleStartMs.length == 0) return;
        try {
            EditBox edts = null;
            for (Box b : track.getBoxes()) {
                if (b instanceof EditBox) {
                    edts = (EditBox) b;
                    break;
                }
            }
            if (edts == null) return;

            List<EditListBox> elstList = edts.getBoxes(EditListBox.class);
            if (elstList == null || elstList.isEmpty()) return;

            EditListBox elst = elstList.get(0);
            List<EditListBox.Entry> entries = elst.getEntries();
            if (entries == null || entries.isEmpty()) return;

            if (movieTimescale <= 0) movieTimescale = 1000;
            if (mediaTimescale <= 0) mediaTimescale = 1000;

            long initialDelayMs = 0;
            long mediaOffsetMs = 0;

            for (EditListBox.Entry entry : entries) {
                if (entry.getMediaTime() == -1) {
                    initialDelayMs += (entry.getSegmentDuration() * 1000L) / movieTimescale;
                } else if (entry.getMediaTime() >= 0) {
                    mediaOffsetMs = (entry.getMediaTime() * 1000L) / mediaTimescale;
                    break;
                }
            }

            long shiftMs = initialDelayMs - mediaOffsetMs;
            if (shiftMs != 0) {
                for (int i = 0; i < sampleStartMs.length; i++) {
                    sampleStartMs[i] = Math.max(0, sampleStartMs[i] + shiftMs);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String decodeTextSample(byte[] buf, int fullSampleSize) {
        if (buf == null || buf.length == 0) return "";

        int len = buf.length;
        // Check for 2-byte big-endian length prefix (QuickTime text and 3GPP tx3g)
        if (len >= 2) {
            int textLen = ((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF);
            if (textLen >= 0 && textLen <= fullSampleSize - 2) {
                int bytesToRead = Math.min(textLen, len - 2);
                return cleanTitle(decodeString(buf, 2, bytesToRead));
            }
        }

        return cleanTitle(decodeString(buf, 0, len));
    }

    private static String decodeString(byte[] buf, int offset, int length) {
        if (length <= 0 || offset + length > buf.length) return "";

        // Check for BOM
        if (length >= 2 && buf[offset] == (byte) 0xFE && buf[offset + 1] == (byte) 0xFF) {
            return new String(buf, offset + 2, length - 2, UTF_16BE);
        }
        if (length >= 2 && buf[offset] == (byte) 0xFF && buf[offset + 1] == (byte) 0xFE) {
            return new String(buf, offset + 2, length - 2, UTF_16LE);
        }
        if (length >= 3 && buf[offset] == (byte) 0xEF && buf[offset + 1] == (byte) 0xBB && buf[offset + 2] == (byte) 0xBF) {
            return new String(buf, offset + 3, length - 3, UTF_8);
        }

        return new String(buf, offset, length, UTF_8);
    }

    private static String cleanTitle(String raw) {
        if (raw == null) return "";
        int nullIdx = raw.indexOf('\0');
        if (nullIdx >= 0) {
            raw = raw.substring(0, nullIdx);
        }
        raw = raw.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "");
        return raw.trim();
    }

    /**
     * Extracts Nero chpl chapters from udta box.
     * Follows spec.md Section 5.3.
     */
    public static List<Chapter> extractNeroChapters(File m4bFile) {
        return extractNeroChapters(m4bFile, 0, 0);
    }

    public static List<Chapter> extractNeroChapters(File m4bFile, long movieTimescale, long totalDurationMs) {
        IsoFile isoFile = null;
        try {
            isoFile = new IsoFile(m4bFile.getAbsolutePath());
            MovieBox moov = isoFile.getMovieBox();
            if (moov == null) return Collections.emptyList();

            if (movieTimescale <= 0 && moov.getMovieHeaderBox() != null) {
                movieTimescale = moov.getMovieHeaderBox().getTimescale();
            }

            Box chplBox = findBoxByType(isoFile, "chpl");
            if (chplBox == null) return Collections.emptyList();

            ByteBuffer buf = getBoxData(chplBox);
            if (buf == null || buf.remaining() < 5) return Collections.emptyList();

            buf.rewind();
            int version = buf.get() & 0xFF;
            // 3 bytes flags
            buf.get(); buf.get(); buf.get();

            List<Chapter> chapters;
            if (version == 1) {
                chapters = parseNeroV1(buf, movieTimescale, totalDurationMs);
            } else {
                chapters = parseNeroV0(buf, movieTimescale, totalDurationMs);
            }

            return chapters != null ? chapters : Collections.emptyList();
        } catch (Exception e) {
            return Collections.emptyList();
        } finally {
            if (isoFile != null) {
                try {
                    isoFile.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static Box findBoxByType(Container container, String type) {
        if (container == null) return null;
        for (Box box : container.getBoxes()) {
            if (type.equals(box.getType())) {
                return box;
            }
            if (box instanceof Container) {
                Box found = findBoxByType((Container) box, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static ByteBuffer getBoxData(Box box) {
        if (box instanceof UnknownBox) {
            return ((UnknownBox) box).getData().duplicate();
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            box.getBox(Channels.newChannel(baos));
            byte[] all = baos.toByteArray();
            int headerSize = 8;
            if (all.length >= 8) {
                long size = ((all[0] & 0xFFL) << 24) | ((all[1] & 0xFFL) << 16) | ((all[2] & 0xFFL) << 8) | (all[3] & 0xFFL);
                if (size == 1 && all.length >= 16) {
                    headerSize = 16;
                }
                if (all.length > headerSize) {
                    return ByteBuffer.wrap(all, headerSize, all.length - headerSize);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static List<Chapter> parseNeroV1(ByteBuffer buf, long movieTimescale, long totalDurationMs) {
        if (buf.remaining() < 5) return Collections.emptyList();
        // Skip 4 bytes reserved
        buf.getInt();

        int count = buf.get() & 0xFF;
        if (count == 0 && buf.remaining() >= 3) {
            int pos = buf.position();
            int next3 = ((buf.get() & 0xFF) << 16) | ((buf.get() & 0xFF) << 8) | (buf.get() & 0xFF);
            if (next3 > 0 && next3 < 2000) {
                count = next3;
            } else {
                buf.position(pos);
            }
        }

        return parseNeroChapterEntries(buf, count, movieTimescale, totalDurationMs);
    }

    private static List<Chapter> parseNeroV0(ByteBuffer buf, long movieTimescale, long totalDurationMs) {
        if (buf.remaining() < 1) return Collections.emptyList();
        int initialPos = buf.position();

        int count1 = buf.get() & 0xFF;
        List<Chapter> chapters = parseNeroChapterEntries(buf.duplicate(), count1, movieTimescale, totalDurationMs);
        if (!chapters.isEmpty()) {
            return chapters;
        }

        if (buf.remaining() >= 2) {
            buf.position(initialPos + 1);
            int count2 = buf.get() & 0xFF;
            chapters = parseNeroChapterEntries(buf.duplicate(), count2, movieTimescale, totalDurationMs);
            if (!chapters.isEmpty()) {
                return chapters;
            }
        }

        return Collections.emptyList();
    }

    private static List<Chapter> parseNeroChapterEntries(ByteBuffer buf, int count, long movieTimescale, long totalDurationMs) {
        if (count <= 0 || count > 2000) return Collections.emptyList();

        List<Chapter> chapters = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (buf.remaining() < 9) break;

            long rawTime = buf.getLong();
            long startMs = rawTime / 10_000L; // 100ns units -> ms
            if (movieTimescale > 0 && totalDurationMs > 0 && startMs > totalDurationMs * 2) {
                long altMs = (rawTime * 1000L) / movieTimescale;
                if (altMs <= totalDurationMs * 2) {
                    startMs = altMs;
                }
            }

            int titleLen = buf.get() & 0xFF;
            if (buf.remaining() < titleLen) break;

            byte[] titleBytes = new byte[titleLen];
            buf.get(titleBytes);
            String title = cleanTitle(new String(titleBytes, UTF_8));
            if (title.isEmpty()) {
                title = "Chapter " + (i + 1);
            }

            chapters.add(new Chapter(title, startMs));
        }

        return chapters;
    }

    public static List<Chapter> extractDescriptionChapters(File m4bFile, long totalDurationMs) {
        if (m4bFile == null || !m4bFile.exists()) return Collections.emptyList();

        List<String> textBlocks = new ArrayList<>();
        IsoFile iso = null;
        try {
            iso = new IsoFile(m4bFile.getAbsolutePath());
            for (String tag : new String[]{"desc", "©des", "ldes", "©cmt", "cprt"}) {
                Box box = findBoxByType(iso, tag);
                if (box != null) {
                    ByteBuffer buf = getBoxData(box);
                    if (buf != null && buf.remaining() > 0) {
                        byte[] bytes = new byte[buf.remaining()];
                        buf.get(bytes);
                        String str = cleanTitle(new String(bytes, UTF_8));
                        if (!str.isEmpty()) {
                            textBlocks.add(str);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (iso != null) {
                try {
                    iso.close();
                } catch (Throwable ignored) {
                }
            }
        }

        for (String comment : textBlocks) {
            String[] lines = comment.split("[\\r\\n]+");
            List<Chapter> chapters = new ArrayList<>();
            long prevStart = -1;

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;

                Matcher matcher = TIMESTAMP_LINE_PATTERN.matcher(line);
                if (matcher.matches()) {
                    String timeStr = matcher.group(1);
                    String title = matcher.group(2);
                    long startMs = parseTimestampToMs(timeStr);
                    if (startMs >= 0 && startMs >= prevStart) {
                        if (totalDurationMs > 0 && startMs > totalDurationMs) {
                            continue;
                        }
                        if (title == null || title.trim().isEmpty()) {
                            title = "Chapter " + (chapters.size() + 1);
                        }
                        chapters.add(new Chapter(title.trim(), startMs));
                        prevStart = startMs;
                    }
                }
            }
            if (chapters.size() >= 2) {
                return chapters;
            }
        }

        return Collections.emptyList();
    }

    private static long parseTimestampToMs(String timeStr) {
        if (timeStr == null) return -1;
        try {
            String[] parts = timeStr.trim().split(":");
            if (parts.length == 2) {
                long min = Long.parseLong(parts[0]);
                double sec = Double.parseDouble(parts[1]);
                return (long) ((min * 60 + sec) * 1000.0);
            } else if (parts.length == 3) {
                long hr = Long.parseLong(parts[0]);
                long min = Long.parseLong(parts[1]);
                double sec = Double.parseDouble(parts[2]);
                return (long) ((hr * 3600 + min * 60 + sec) * 1000.0);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }
}
