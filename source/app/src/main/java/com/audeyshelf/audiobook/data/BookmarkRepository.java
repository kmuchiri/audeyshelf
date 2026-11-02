package com.audeyshelf.audiobook.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;

import com.audeyshelf.audiobook.model.Bookmark;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Asynchronous repository for bookmark operations.
 */
public class BookmarkRepository {
    private final BookmarkDbHelper dbHelper;
    private final ExecutorService executor;
    private final Handler mainHandler;

    public interface Callback<T> {
        void onResult(T result);
    }

    public BookmarkRepository(Context context) {
        this.dbHelper = new BookmarkDbHelper(context);
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void getBookmarks(final String filePath, final Callback<List<Bookmark>> callback) {
        executor.execute(() -> {
            List<Bookmark> list = new ArrayList<>();
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            Cursor cursor = null;
            try {
                cursor = db.query(
                        BookmarkDbHelper.TABLE_BOOKMARKS,
                        new String[]{
                                BookmarkDbHelper.COLUMN_ID,
                                BookmarkDbHelper.COLUMN_FILE_PATH,
                                BookmarkDbHelper.COLUMN_POSITION_MS,
                                BookmarkDbHelper.COLUMN_LABEL,
                                BookmarkDbHelper.COLUMN_CREATED_AT
                        },
                        BookmarkDbHelper.COLUMN_FILE_PATH + " = ?",
                        new String[]{filePath},
                        null,
                        null,
                        BookmarkDbHelper.COLUMN_POSITION_MS + " ASC"
                );

                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        long id = cursor.getLong(cursor.getColumnIndexOrThrow(BookmarkDbHelper.COLUMN_ID));
                        String path = cursor.getString(cursor.getColumnIndexOrThrow(BookmarkDbHelper.COLUMN_FILE_PATH));
                        long pos = cursor.getLong(cursor.getColumnIndexOrThrow(BookmarkDbHelper.COLUMN_POSITION_MS));
                        String label = cursor.getString(cursor.getColumnIndexOrThrow(BookmarkDbHelper.COLUMN_LABEL));
                        long created = cursor.getLong(cursor.getColumnIndexOrThrow(BookmarkDbHelper.COLUMN_CREATED_AT));
                        list.add(new Bookmark(id, path, pos, label, created));
                    } while (cursor.moveToNext());
                }
            } catch (Exception ignored) {
            } finally {
                if (cursor != null) cursor.close();
            }

            mainHandler.post(() -> {
                if (callback != null) callback.onResult(list);
            });
        });
    }

    public void addBookmark(final Bookmark bookmark, final Callback<Long> callback) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put(BookmarkDbHelper.COLUMN_FILE_PATH, bookmark.getFilePath());
            cv.put(BookmarkDbHelper.COLUMN_POSITION_MS, bookmark.getPositionMs());
            cv.put(BookmarkDbHelper.COLUMN_LABEL, bookmark.getLabel());
            long newId = db.insert(BookmarkDbHelper.TABLE_BOOKMARKS, null, cv);

            mainHandler.post(() -> {
                if (callback != null) callback.onResult(newId);
            });
        });
    }

    public void deleteBookmark(final long id, final Callback<Boolean> callback) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            int rows = db.delete(
                    BookmarkDbHelper.TABLE_BOOKMARKS,
                    BookmarkDbHelper.COLUMN_ID + " = ?",
                    new String[]{String.valueOf(id)}
            );
            final boolean success = rows > 0;
            mainHandler.post(() -> {
                if (callback != null) callback.onResult(success);
            });
        });
    }
}
