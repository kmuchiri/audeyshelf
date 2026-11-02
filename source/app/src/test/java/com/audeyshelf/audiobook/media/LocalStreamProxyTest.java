package com.audeyshelf.audiobook.media;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class LocalStreamProxyTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void testStreamingWithPatchAndRange() throws Exception {
        File testFile = temporaryFolder.newFile("test_audio.m4b");

        // Write 1000 bytes with byte at index 500 = 0x12
        byte[] original = new byte[1000];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (i % 128);
        }
        original[500] = 0x12;

        try (FileOutputStream fos = new FileOutputStream(testFile)) {
            fos.write(original);
        }

        LocalStreamProxy proxy = new LocalStreamProxy();
        // Patch index 500 from 0x12 to 0x10
        String urlStr = proxy.start(testFile, 500, (byte) 0x10);
        Assert.assertNotNull(urlStr);
        Assert.assertTrue(urlStr.startsWith("http://127.0.0.1:"));

        try {
            // 1. Full GET request
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            int responseCode = conn.getResponseCode();
            Assert.assertEquals(200, responseCode);
            Assert.assertEquals(1000, conn.getContentLengthLong());

            byte[] fullContent = readAllBytes(conn.getInputStream());
            Assert.assertEquals(1000, fullContent.length);
            Assert.assertEquals(0x10, fullContent[500]); // Patched byte!
            Assert.assertEquals(original[499], fullContent[499]);
            Assert.assertEquals(original[501], fullContent[501]);

            // 2. Range request covering the patched byte: bytes=450-550
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Range", "bytes=450-550");
            responseCode = conn.getResponseCode();
            Assert.assertEquals(206, responseCode);
            Assert.assertEquals(101, conn.getContentLengthLong());
            Assert.assertEquals("bytes 450-550/1000", conn.getHeaderField("Content-Range"));

            byte[] rangeContent = readAllBytes(conn.getInputStream());
            Assert.assertEquals(101, rangeContent.length);
            // 500 - 450 = 50 in rangeContent
            Assert.assertEquals(0x10, rangeContent[50]);

            // 3. Range request before the patched byte: bytes=0-100
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Range", "bytes=0-100");
            responseCode = conn.getResponseCode();
            Assert.assertEquals(206, responseCode);
            Assert.assertEquals(101, conn.getContentLengthLong());
            byte[] earlyRange = readAllBytes(conn.getInputStream());
            Assert.assertEquals(original[50], earlyRange[50]);

            // 4. HEAD request
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("HEAD");
            responseCode = conn.getResponseCode();
            Assert.assertEquals(200, responseCode);
            Assert.assertEquals(1000, conn.getContentLengthLong());

        } finally {
            proxy.stop();
            Assert.assertFalse(proxy.isRunning());
        }
    }

    private static byte[] readAllBytes(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int r;
        while ((r = in.read(buf)) != -1) {
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }
}
