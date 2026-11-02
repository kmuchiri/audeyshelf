package com.audeyshelf.audiobook.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * SQLite helper for bookmark management.
 * Follows spec.md Section 4.2.
 */
public class BookmarkDbHelper extends SQLiteOpenHelper {
    public static final String DB_NAME = "audiobook.db";
    public static final int DB_VERSION = 1;

    public static final String TABLE_BOOKMARKS = "bookmarks";
    public static final String COLUMN_ID = "_id";
    public static final String COLUMN_FILE_PATH = "file_path";
    public static final String COLUMN_POSITION_MS = "position_ms";
    public static final String COLUMN_LABEL = "label";
    public static final String COLUMN_CREATED_AT = "created_at";

    public BookmarkDbHelper(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_BOOKMARKS + " (" +
                COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                COLUMN_FILE_PATH + " TEXT NOT NULL," +
                COLUMN_POSITION_MS + " INTEGER NOT NULL," +
                COLUMN_LABEL + " TEXT," +
                COLUMN_CREATED_AT + " INTEGER NOT NULL DEFAULT (strftime('%s','now'))" +
                ");");
        db.execSQL("CREATE INDEX idx_bookmarks_file ON " + TABLE_BOOKMARKS +
                "(" + COLUMN_FILE_PATH + ", " + COLUMN_POSITION_MS + ");");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // No migrations for v1
    }
}
