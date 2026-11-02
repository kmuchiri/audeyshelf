package com.audeyshelf.audiobook;

import com.audeyshelf.audiobook.util.AudioSanitizer;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;

public class AudioSanitizerTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void testSanitizeCorruptDependsOnCoreCoder() throws Exception {
        File m4bFile = temporaryFolder.newFile("test_corrupt.m4b");

        // Construct mock MP4 structure: ftyp + moov + trak ... + esds
        byte[] esdsPayload = new byte[]{
                'e', 's', 'd', 's',
                0x00, 0x00, 0x00, 0x00, // version + flags
                0x03, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x22, 0x00, 0x02, 0x00,
                0x04, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x14, 0x40, 0x15, 0x00,
                0x00, 0x00, 0x00, 0x01, (byte) 0xea, (byte) 0x94, 0x00, 0x01, (byte) 0xea, (byte) 0x94,
                0x05, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x02,
                0x12, 0x12, // Corrupt 2-byte audio specific config with bit 1 set!
                0x06, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01, 0x02
        };

        int moovSize = 8 + esdsPayload.length;
        byte[] moovHeader = new byte[]{
                (byte) ((moovSize >> 24) & 0xFF),
                (byte) ((moovSize >> 16) & 0xFF),
                (byte) ((moovSize >> 8) & 0xFF),
                (byte) (moovSize & 0xFF),
                'm', 'o', 'o', 'v'
        };

        byte[] ftyp = new byte[]{
                0x00, 0x00, 0x00, 0x14,
                'f', 't', 'y', 'p',
                'M', '4', 'A', ' ',
                0x00, 0x00, 0x02, 0x00,
                'M', '4', 'A', ' '
        };

        try (FileOutputStream fos = new FileOutputStream(m4bFile)) {
            fos.write(ftyp);
            fos.write(moovHeader);
            fos.write(esdsPayload);
        }

        // Run sanitizer
        boolean fixed = AudioSanitizer.sanitizeM4bIfNeeded(m4bFile);
        Assert.assertTrue("File should be detected as corrupt and fixed", fixed);

        // Verify the byte was patched to 0x10
        try (RandomAccessFile raf = new RandomAccessFile(m4bFile, "r")) {
            // Find position of AudioSpecificConfig
            byte[] fileBytes = new byte[(int) m4bFile.length()];
            raf.readFully(fileBytes);
            // Search for 0x05 0x80 0x80 0x80 0x02
            int foundIdx = -1;
            for (int i = 0; i < fileBytes.length - 6; i++) {
                if (fileBytes[i] == 0x05 && fileBytes[i + 4] == 0x02) {
                    foundIdx = i;
                    break;
                }
            }
            Assert.assertNotEquals(-1, foundIdx);
            Assert.assertEquals(0x12, fileBytes[foundIdx + 5]);
            Assert.assertEquals(0x10, fileBytes[foundIdx + 6]);
        }

        // Running a second time should report false (already clean)
        boolean secondRun = AudioSanitizer.sanitizeM4bIfNeeded(m4bFile);
        Assert.assertFalse("Already clean file should not be modified", secondRun);
    }
}
