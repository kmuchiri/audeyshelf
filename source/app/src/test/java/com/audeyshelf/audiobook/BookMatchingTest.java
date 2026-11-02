package com.audeyshelf.audiobook;

import com.audeyshelf.audiobook.model.Audiobook;
import com.audeyshelf.audiobook.ui.LibraryActivity;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BookMatchingTest {

    @Test
    public void testMatchingDontBelieveEverythingYouThink() {
        // Server has standard title
        Audiobook serverBook = new Audiobook("item-1", "Don't Believe Everything You Think", "Joseph Nguyen", 10000L, "", 0L);
        // Local has downloaded file name
        Audiobook localBook = new Audiobook("/storage/extSdCard/Audiobooks/Dont_Believe_Everything_You_Think_(Expanded_Edition)_Why_Your_Thinking_Is_The_Beginning__End_Of_Suffering.m4b",
                "Dont_Believe_Everything_You_Think_(Expanded_Edition)_Why_Your_Thinking_Is_The_Beginning__End_Of_Suffering",
                "Joseph Nguyen", 10000L, 1000000L);

        assertTrue("Should match server book with downloaded file title", LibraryActivity.isMatchingBook(serverBook, localBook));
    }

    @Test
    public void testMatchingServerSubtitleWithLocalShortTitle() {
        Audiobook serverBook = new Audiobook("item-2", "Atomic Habits: An Easy & Proven Way to Build Good Habits", "James Clear", 10000L, "", 0L);
        Audiobook localBook = new Audiobook("/storage/extSdCard/Audiobooks/Atomic Habits.m4b",
                "Atomic Habits", "James Clear", 10000L, 1000000L);

        assertTrue("Should match when server has subtitle and local is short", LibraryActivity.isMatchingBook(serverBook, localBook));
    }

    @Test
    public void testMatchingUnabridgedSuffix() {
        Audiobook serverBook = new Audiobook("item-3", "12 Rules for Life (Unabridged)", "Jordan B. Peterson", 10000L, "", 0L);
        Audiobook localBook = new Audiobook("/storage/extSdCard/Audiobooks/12 Rules for Life/12 Rules for Life.m4b",
                "12 Rules for Life", "Jordan B. Peterson", 10000L, 1000000L);

        assertTrue("Should match unabridged suffix", LibraryActivity.isMatchingBook(serverBook, localBook));
    }

    @Test
    public void testMatchingGenericTrackTitleUsingFilePath() {
        Audiobook serverBook = new Audiobook("item-4", "Bad Blood: Secrets and Lies in a Silicon Valley Startup", "John Carreyrou", 10000L, "", 0L);
        Audiobook localBook = new Audiobook("/storage/extSdCard/Audiobooks/Bad_Blood_Secrets_and_Lies_in_a_Silicon_Valley_Startup/track01.mp3",
                "Track 01", "John Carreyrou", 10000L, 1000000L);

        assertTrue("Should match using parent folder name", LibraryActivity.isMatchingBook(serverBook, localBook));
    }

    @Test
    public void testDifferentBooksDoNotMatch() {
        Audiobook serverBook = new Audiobook("item-5", "The Hobbit", "J.R.R. Tolkien", 10000L, "", 0L);
        Audiobook localBook = new Audiobook("/storage/extSdCard/Audiobooks/Atomic Habits.m4b",
                "Atomic Habits", "James Clear", 10000L, 1000000L);

        assertFalse("Different books must not match", LibraryActivity.isMatchingBook(serverBook, localBook));
    }
}
