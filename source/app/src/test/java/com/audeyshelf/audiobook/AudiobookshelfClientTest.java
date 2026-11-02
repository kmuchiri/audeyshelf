package com.audeyshelf.audiobook;

import com.audeyshelf.audiobook.model.Audiobook;
import com.audeyshelf.audiobook.net.AudiobookshelfClient;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AudiobookshelfClientTest {

    @Test
    public void testLibraryInfoModel() {
        AudiobookshelfClient.LibraryInfo info = new AudiobookshelfClient.LibraryInfo("lib-1", "Sci-Fi Audiobooks", "book");
        assertEquals("lib-1", info.id);
        assertEquals("Sci-Fi Audiobooks", info.name);
        assertEquals("book", info.mediaType);
        assertEquals("Sci-Fi Audiobooks", info.toString());
    }

    @Test
    public void testServerAudiobookCreation() {
        String id = "item-1";
        String title = "Dune";
        String author = "Frank Herbert";
        long durationMs = 72000500L;
        long progressMs = 3600000L;

        Audiobook book = new Audiobook(id, title, author, durationMs, "http://abs/cover.jpg", progressMs);
        assertEquals("item-1", book.getServerItemId());
        assertEquals("Dune", book.getTitle());
        assertEquals("Frank Herbert", book.getAuthor());
        assertEquals(72000500L, book.getDurationMs());
        assertEquals(3600000L, book.getServerProgressMs());
        assertTrue(book.isServerItem());
        assertFalse(book.isOnDevice());
    }

    @Test
    public void testLocalBookMatching() {
        List<Audiobook> localBooks = new ArrayList<>();
        localBooks.add(new Audiobook("/sdcard/Audiobooks/Dune.m4b", "Dune", "Frank Herbert", 72000000L, 1000000L));
        localBooks.add(new Audiobook("/sdcard/Audiobooks/1984.m4b", "1984", "George Orwell", 36000000L, 500000L));

        List<Audiobook> serverBooks = new ArrayList<>();
        Audiobook serverDune = new Audiobook("item-dune", "Dune", "Frank Herbert", 72000000L, "http://abs/dune.jpg", 0L);
        Audiobook serverHobbit = new Audiobook("item-hobbit", "The Hobbit", "J.R.R. Tolkien", 40000000L, "http://abs/hobbit.jpg", 0L);
        serverBooks.add(serverDune);
        serverBooks.add(serverHobbit);

        // Matching logic
        for (Audiobook sb : serverBooks) {
            String sTitle = sb.getTitle().toLowerCase().replaceAll("[^a-z0-9]", "");
            for (Audiobook lb : localBooks) {
                String lTitle = lb.getTitle().toLowerCase().replaceAll("[^a-z0-9]", "");
                if (sTitle.equals(lTitle)) {
                    sb.setOnDevice(true);
                    sb.setFilePath(lb.getFilePath());
                    break;
                }
            }
        }

        assertTrue("Dune should be marked on-device", serverDune.isOnDevice());
        assertEquals("/sdcard/Audiobooks/Dune.m4b", serverDune.getFilePath());

        assertFalse("The Hobbit should not be marked on-device", serverHobbit.isOnDevice());
        assertEquals("", serverHobbit.getFilePath());
    }

    @Test
    public void testNormalizeUrl() {
        assertEquals("http://192.168.1.35:13378", AudiobookshelfClient.normalizeUrl("192.168.1.35:13378"));
        assertEquals("http://192.168.1.35:13378", AudiobookshelfClient.normalizeUrl("192.168.1.35:13378/"));
        assertEquals("http://192.168.1.35:13378", AudiobookshelfClient.normalizeUrl("  192.168.1.35:13378/  "));
        assertEquals("http://192.168.1.35:13378", AudiobookshelfClient.normalizeUrl("http://192.168.1.35:13378"));
        assertEquals("http://192.168.1.35:13378", AudiobookshelfClient.normalizeUrl("http://192.168.1.35:13378/"));
        assertEquals("https://abs.example.com", AudiobookshelfClient.normalizeUrl("https://abs.example.com"));
        assertEquals("https://abs.example.com", AudiobookshelfClient.normalizeUrl("https://abs.example.com/"));
        assertEquals("", AudiobookshelfClient.normalizeUrl(null));
        assertEquals("", AudiobookshelfClient.normalizeUrl(""));
        assertEquals("", AudiobookshelfClient.normalizeUrl("   "));
    }

    @Test
    public void testExtractPublicationYear() throws Exception {
        org.json.JSONObject obj1 = new org.json.JSONObject("{\"publishedYear\": 2018}");
        assertEquals("2018", AudiobookshelfClient.extractPublicationYear(obj1));

        org.json.JSONObject obj2 = new org.json.JSONObject("{\"publishedYear\": \"1997\"}");
        assertEquals("1997", AudiobookshelfClient.extractPublicationYear(obj2));

        org.json.JSONObject obj3 = new org.json.JSONObject("{\"publishedYear\": null, \"publishedDate\": \"2021-04-15\"}");
        assertEquals("2021", AudiobookshelfClient.extractPublicationYear(obj3));

        org.json.JSONObject obj4 = new org.json.JSONObject("{\"releaseDate\": \"Released October 2015\"}");
        assertEquals("2015", AudiobookshelfClient.extractPublicationYear(obj4));

        org.json.JSONObject obj5 = new org.json.JSONObject("{\"publishedYear\": null, \"publishedDate\": null}");
        assertEquals("", AudiobookshelfClient.extractPublicationYear(obj5));

        org.json.JSONObject obj6 = new org.json.JSONObject("{\"publishedYear\": \"null\"}");
        assertEquals("", AudiobookshelfClient.extractPublicationYear(obj6));
    }
}
