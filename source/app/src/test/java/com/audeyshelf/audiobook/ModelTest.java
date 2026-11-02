package com.audeyshelf.audiobook;

import com.audeyshelf.audiobook.model.Audiobook;
import com.audeyshelf.audiobook.model.Bookmark;
import com.audeyshelf.audiobook.model.Chapter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ModelTest {

    @Test
    public void testAudiobookProgress() {
        Audiobook book = new Audiobook("/path/to/book.m4b", "Sherlock Holmes", "Arthur Conan Doyle", 100000L, 5000000L);
        assertEquals(0, book.getProgressPercentage());

        book.setResumePositionMs(25000L);
        assertEquals(25, book.getProgressPercentage());

        book.setResumePositionMs(50000L);
        assertEquals(50, book.getProgressPercentage());

        book.setResumePositionMs(100000L);
        assertEquals(100, book.getProgressPercentage());

        book.setResumePositionMs(120000L);
        assertEquals(100, book.getProgressPercentage());
    }

    @Test
    public void testAudiobookDefaults() {
        Audiobook book = new Audiobook("/path/to/test.m4b", null, null, 0L, 1024L);
        assertEquals("Untitled Audiobook", book.getTitle());
        assertEquals("Unknown Author", book.getAuthor());
        assertEquals(true, book.isOnDevice());
        assertEquals(false, book.isServerItem());
    }

    @Test
    public void testServerAudiobookModel() {
        Audiobook serverBook = new Audiobook("item-1234", "The Hobbit", "J.R.R. Tolkien", 36000000L, "http://server/cover.jpg", 1500000L, "1937");
        assertEquals("The Hobbit", serverBook.getTitle());
        assertEquals("J.R.R. Tolkien", serverBook.getAuthor());
        assertEquals("item-1234", serverBook.getServerItemId());
        assertEquals("http://server/cover.jpg", serverBook.getCoverUrl());
        assertEquals(1500000L, serverBook.getServerProgressMs());
        assertEquals("1937", serverBook.getPublishedDate());
        assertEquals(true, serverBook.isServerItem());
        assertEquals(false, serverBook.isOnDevice());

        serverBook.setOnDevice(true);
        serverBook.setFilePath("/storage/books/hobbit.m4b");
        assertEquals(true, serverBook.isOnDevice());
        assertEquals("/storage/books/hobbit.m4b", serverBook.getFilePath());
    }

    @Test
    public void testChapterModel() {
        Chapter ch = new Chapter("Chapter 1", 0L, 5000L, 0);
        assertEquals("Chapter 1", ch.getTitle());
        assertEquals(0L, ch.getStartMs());
        assertEquals(5000L, ch.getDurationMs());
        assertEquals(0, ch.getIndex());
    }

    @Test
    public void testBookmarkModel() {
        Bookmark bm = new Bookmark(1L, "/path/to/book.m4b", 15000L, "Interesting Quote", 1700000000L);
        assertEquals(1L, bm.getId());
        assertEquals("/path/to/book.m4b", bm.getFilePath());
        assertEquals(15000L, bm.getPositionMs());
        assertEquals("Interesting Quote", bm.getLabel());
        assertEquals(1700000000L, bm.getCreatedAt());
    }

    @Test
    public void testPlaybackServiceActionConstants() {
        assertEquals("com.audeyshelf.audiobook.ACTION_REFRESH_NOTIFICATION",
                com.audeyshelf.audiobook.media.PlaybackService.ACTION_REFRESH_NOTIFICATION);
    }
}
