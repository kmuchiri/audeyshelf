package com.audeyshelf.audiobook;

import com.audeyshelf.audiobook.util.StorageFileHelper;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StorageFileHelperTest {

    @Test
    public void testIsOnRemovableStorage() {
        // Removable storage paths
        assertTrue(StorageFileHelper.isOnRemovableStorage(new File("/storage/extSdCard/Audiobooks/book.m4b")));
        assertTrue(StorageFileHelper.isOnRemovableStorage(new File("/storage/1234-5678/Audiobooks/book.m4b")));
        assertTrue(StorageFileHelper.isOnRemovableStorage(new File("/mnt/media_rw/extSdCard/book.m4b")));

        // Internal emulated storage paths
        assertFalse(StorageFileHelper.isOnRemovableStorage(new File("/storage/emulated/0/Audiobooks/book.m4b")));
        assertFalse(StorageFileHelper.isOnRemovableStorage(new File("/storage/sdcard0/Audiobooks/book.m4b")));
        assertFalse(StorageFileHelper.isOnRemovableStorage(null));
    }
}
