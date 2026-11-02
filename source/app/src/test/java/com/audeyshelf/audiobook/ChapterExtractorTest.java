package com.audeyshelf.audiobook;

import com.audeyshelf.audiobook.media.ChapterExtractor;
import com.audeyshelf.audiobook.model.Chapter;

import org.junit.Test;

import java.io.File;
import java.net.URL;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ChapterExtractorTest {

    @Test
    public void testFallbackSingleChapterWhenFileNull() {
        List<Chapter> chapters = ChapterExtractor.extractChapters(null, null, 60000L);
        assertNotNull(chapters);
        assertEquals(1, chapters.size());
        assertEquals("Entire Book", chapters.get(0).getTitle());
        assertEquals(0L, chapters.get(0).getStartMs());
        assertEquals(60000L, chapters.get(0).getDurationMs());
    }

    @Test
    public void testFallbackSingleChapterWhenFileNotFound() {
        File missing = new File("/non/existent/path/missing.m4b");
        List<Chapter> chapters = ChapterExtractor.extractChapters(null, missing, 120000L);
        assertNotNull(chapters);
        assertEquals(1, chapters.size());
        assertEquals("Entire Book", chapters.get(0).getTitle());
        assertEquals(0L, chapters.get(0).getStartMs());
        assertEquals(120000L, chapters.get(0).getDurationMs());
    }

    @Test
    public void testExtractionOnSampleM4b() {
        URL resource = getClass().getClassLoader().getResource("sample_audiobook.m4b");
        assertNotNull("sample_audiobook.m4b resource should exist", resource);

        File m4bFile = new File(resource.getFile());
        assertTrue("sample_audiobook.m4b file should exist", m4bFile.exists());

        // 1. Verify QuickTime chapter track extraction
        List<Chapter> qtChapters = ChapterExtractor.extractQuickTimeChapters(m4bFile);
        assertNotNull(qtChapters);
        assertEquals(3, qtChapters.size());
        assertEquals("Chapter 1: The Beginning", qtChapters.get(0).getTitle());
        assertEquals(0L, qtChapters.get(0).getStartMs());
        assertEquals("Chapter 2: The Investigation", qtChapters.get(1).getTitle());
        assertEquals(3000L, qtChapters.get(1).getStartMs());
        assertEquals("Chapter 3: The Resolution", qtChapters.get(2).getTitle());
        assertEquals(7000L, qtChapters.get(2).getStartMs());

        // Verify titles do not contain trailing modifier atom artifacts (e.g. encd)
        for (Chapter ch : qtChapters) {
            assertFalse("Title should not contain encd artifact", ch.getTitle().contains("encd"));
            assertFalse("Title should not contain null characters", ch.getTitle().contains("\0"));
        }

        // 2. Verify Nero chpl box extraction
        List<Chapter> neroChapters = ChapterExtractor.extractNeroChapters(m4bFile);
        assertNotNull(neroChapters);
        assertEquals(3, neroChapters.size());
        assertEquals("Chapter 1: The Beginning", neroChapters.get(0).getTitle());
        assertEquals(0L, neroChapters.get(0).getStartMs());
        assertEquals("Chapter 2: The Investigation", neroChapters.get(1).getTitle());
        assertEquals(3000L, neroChapters.get(1).getStartMs());
        assertEquals("Chapter 3: The Resolution", neroChapters.get(2).getTitle());
        assertEquals(7000L, neroChapters.get(2).getStartMs());

        // 3. Verify Combined chapter extraction with duration calculation
        List<Chapter> chapters = ChapterExtractor.extractChapters(null, m4bFile, 10000L);
        assertNotNull(chapters);
        assertEquals(3, chapters.size());

        assertEquals(0, chapters.get(0).getIndex());
        assertEquals("Chapter 1: The Beginning", chapters.get(0).getTitle());
        assertEquals(0L, chapters.get(0).getStartMs());
        assertEquals(3000L, chapters.get(0).getDurationMs());

        assertEquals(1, chapters.get(1).getIndex());
        assertEquals("Chapter 2: The Investigation", chapters.get(1).getTitle());
        assertEquals(3000L, chapters.get(1).getStartMs());
        assertEquals(4000L, chapters.get(1).getDurationMs());

        assertEquals(2, chapters.get(2).getIndex());
        assertEquals("Chapter 3: The Resolution", chapters.get(2).getTitle());
        assertEquals(7000L, chapters.get(2).getStartMs());
        assertEquals(3000L, chapters.get(2).getDurationMs());

        // 4. Verify automatic totalDuration resolution when 0 passed in
        List<Chapter> autoDurationChapters = ChapterExtractor.extractChapters(null, m4bFile, 0L);
        assertNotNull(autoDurationChapters);
        assertEquals(3, autoDurationChapters.size());
        assertEquals(3000L, autoDurationChapters.get(2).getDurationMs());
    }

    @Test
    public void testBoundariesExtraction() {
        File file = new File("/home/km/Downloads/Boundaries_Auidobook.m4b");
        if (file.exists()) {
            System.out.println("Testing Boundaries file: " + file.length() + " bytes");
            long t0 = System.currentTimeMillis();
            List<Chapter> chapters = ChapterExtractor.extractChapters(null, file, 0L);
            long t1 = System.currentTimeMillis();
            System.out.println("Extraction took " + (t1 - t0) + " ms, chapters count = " + (chapters != null ? chapters.size() : "null"));
            if (chapters != null) {
                for (Chapter ch : chapters) {
                    System.out.println("Chapter: " + ch.getTitle() + " start=" + ch.getStartMs() + " dur=" + ch.getDurationMs());
                }
            }
        }
    }
}
