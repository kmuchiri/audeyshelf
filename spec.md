# Specification: Legacy-Optimized M4B Audiobook Player (Android 4.1–9)

## 1. Overview

This document specifies the implementation of a lightweight M4B audiobook player in Java, targeting Android 4.1 (API 16) through Android 9 (API 28). The application prioritizes low memory and CPU usage, reliable background playback, chapter navigation, persistent bookmarks, and automatic playback resume. It deliberately avoids modern AndroidX Media3 / ExoPlayer in favor of the platform `MediaPlayer` and the Support Library, because Media3 requires a minimum SDK of API 21 and introduces a larger runtime footprint—both unacceptable for low-powered legacy devices.

### 1.1 Core Requirements

| Requirement | Target |
|---|---|
| Minimum SDK | 16 (Android 4.1 Jelly Bean) |
| Maximum tested SDK | 28 (Android 9 Pie) |
| Language | Java |
| Media engine | `android.media.MediaPlayer` |
| UI framework | Support Library `appcompat-v7` (v28.0.0) |
| Chapter extraction | `mp4parser` (isoparser) |
| Lock-screen controls | `MediaSessionCompat` |
| Background playback | Foreground `Service` |
| Persistence | `SharedPreferences` (resume) + `SQLite` (bookmarks) |

---

## 2. Architecture

The application follows a **three-layer architecture** designed to keep the UI responsive on devices with limited RAM and single-core or dual-core CPUs.

```
┌─────────────────────────────────────┐
│  UI Layer                           │
│  Activity + Fragments (AppCompat)   │
│  - Library list                     │
│  - Player screen                    │
│  - Bookmark manager                 │
└──────────────┬──────────────────────┘
               │ binds / starts
┌──────────────▼──────────────────────┐
│  Playback Service (Foreground)      │
│  - MediaPlayer lifecycle            │
│  - MediaSessionCompat               │
│  - WakeLock / WifiLock              │
│  - Notification                     │
└──────────────┬──────────────────────┘
               │ reads / writes
┌──────────────▼──────────────────────┐
│  Data Layer                         │
│  - SharedPreferences (resume)       │
│  - SQLiteOpenHelper (bookmarks)     │
│  - Chapter cache (JSON file)        │
└─────────────────────────────────────┘
```

**Key design decisions:**

- The `MediaPlayer` lives inside a foreground `Service`, never inside an `Activity`. This prevents the system from killing playback when the screen turns off or the user navigates away.
- The UI communicates with the service via a local `Binder` (for in-process communication) rather than `AIDL`, reducing complexity and IPC overhead.
- Chapter metadata is parsed **once per file** and cached to a small JSON file; subsequent loads read from cache instead of re-parsing the MP4 atom tree.

---

## 3. Dependencies

```gradle
// build.gradle (app module)
android {
    compileSdkVersion 28
    defaultConfig {
        minSdkVersion 16
        targetSdkVersion 28
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation 'com.android.support:appcompat-v7:28.0.0'
    implementation 'com.android.support:support-compat:28.0.0'   // MediaSessionCompat
    implementation 'com.googlecode.mp4parser:isoparser:1.1.22'   // chapter extraction
}
```

> **Note:** `appcompat-v7` 28.0.0 is the final Support Library release that supports API 14+. `isoparser` 1.1.22 is a pure-Java MP4 box parser with no native dependencies, suitable for low-end devices.

---

## 4. Data Layer

### 4.1 Resume Position — `SharedPreferences`

Resume position is stored per-file using the file’s absolute path (or a stable hash of it) as the key. Only a single `long` value (milliseconds) is required per audiobook.

```java
private static final String PREFS_NAME = "resume_prefs";
private static final String KEY_POSITION_PREFIX = "pos_";

public void saveResumePosition(String filePath, long positionMs) {
    SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    prefs.edit().putLong(KEY_POSITION_PREFIX + filePath.hashCode(), positionMs).apply();
}

public long getResumePosition(String filePath) {
    SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    return prefs.getLong(KEY_POSITION_PREFIX + filePath.hashCode(), 0L);
}
```

`apply()` is used instead of `commit()` to avoid blocking the main thread. Save the position in `onPause()` of the `Activity` and also periodically (every 30 seconds) inside the service while playing, so that an unexpected process death does not lose more than 30 seconds of progress.

### 4.2 Bookmarks — `SQLite`

Bookmarks require multiple records per book, timestamps, optional labels, and ordering by time. `SQLite` is the correct choice.

**Schema:**

```sql
CREATE TABLE bookmarks (
    _id         INTEGER PRIMARY KEY AUTOINCREMENT,
    file_path   TEXT    NOT NULL,
    position_ms INTEGER NOT NULL,
    label       TEXT,
    created_at  INTEGER NOT NULL DEFAULT (strftime('%s','now'))
);
CREATE INDEX idx_bookmarks_file ON bookmarks(file_path, position_ms);
```

**`SQLiteOpenHelper` implementation:**

```java
public class BookmarkDbHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "audiobook.db";
    private static final int DB_VERSION = 1;

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE bookmarks (" +
            "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "file_path TEXT NOT NULL," +
            "position_ms INTEGER NOT NULL," +
            "label TEXT," +
            "created_at INTEGER NOT NULL DEFAULT (strftime('%s','now'))" +
            ");");
        db.execSQL("CREATE INDEX idx_bookmarks_file ON bookmarks(file_path, position_ms);");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // No migrations for v1
    }
}
```

**Bookmark operations** should be performed on a background thread (`AsyncTask` or a dedicated `ExecutorService`) to keep the UI thread free.

### 4.3 Chapter Cache

Chapter parsing via `mp4parser` involves reading and traversing MP4 atoms, which is I/O-heavy. Cache the result as a JSON array:

```json
[
  {"title": "Chapter 1", "startMs": 0},
  {"title": "Chapter 2", "startMs": 1800000},
  ...
]
```

Store the cache in `context.getCacheDir()` with the filename derived from the audiobook path hash. Invalidate the cache if the source file’s last-modified timestamp changes.

---

## 5. Chapter Extraction (M4B / MP4)

### 5.1 Formats to Support

M4B files may contain chapter metadata in one of two common box structures:

1. **QuickTime chapter track** (`tref` → `chap` referencing a text track).
2. **Nero chapter box** (`chpl` inside `udta`).

Both formats must be parsed. The `mp4parser` library provides the box-level access needed, but it does not expose chapter semantics directly, so a small custom extractor is required.

### 5.2 QuickTime Chapter Extraction

QuickTime chapters are stored in a separate text track. The algorithm:

1. Locate the `moov` box.
2. Find the audio track’s `tref` box and the `chap` child, which contains the `track_ID` of the chapter track.
3. Locate the chapter track and read its samples via `stbl` / `stco` / `stsz` / `stts`.
4. Each sample contains a chapter title (UTF-8 text) and a duration derived from the `stts` table.

```java
public static List<Chapter> extractQuickTimeChapters(File m4bFile) throws IOException {
    IsoFile isoFile = new IsoFile(m4bFile.getAbsolutePath());
    Movie movie = isoFile.getMovie();
    List<Chapter> chapters = new ArrayList<>();

    // Find the chapter track via tref/chap (simplified)
    TrackBox audioTrack = findAudioTrack(movie);
    TrackBox chapterTrack = resolveChapterTrack(movie, audioTrack);
    if (chapterTrack == null) return chapters;

    SampleTableBox stbl = chapterTrack.getMediaBox().getMediaInformationBox().getSampleTableBox();
    long[] sampleOffsets = getChunkOffsets(stbl);
    long[] sampleSizes  = getSampleSizes(stbl);
    long[] sampleTimes  = getSampleDurations(stbl);

    long cursorMs = 0;
    try (RandomAccessFile raf = new RandomAccessFile(m4bFile, "r")) {
        for (int i = 0; i < sampleSizes.length; i++) {
            raf.seek(sampleOffsets[i]);
            byte[] buf = new byte[(int) sampleSizes[i]];
            raf.readFully(buf);
            String title = new String(buf, StandardCharsets.UTF_8).trim();
            chapters.add(new Chapter(title, cursorMs));
            cursorMs += sampleTimes[i];
        }
    }
    isoFile.close();
    return chapters;
}
```

### 5.3 Nero `chpl` Chapter Extraction

The `chpl` box lives inside `udta`. Its payload is:

```
[1 byte]  chapter count
for each chapter:
  [8 bytes] timestamp (in 100-nanosecond units)
  [1 byte]  title length
  [N bytes] title (UTF-8)
```

The timestamp unit is **100-nanosecond units**, not milliseconds. To convert to milliseconds, divide by 10,000. Some files store timestamps in the movie timescale instead; read the `mvhd` box’s `timescale` and divide by `timescale / 1000` if the raw values are implausibly large.

```java
public static List<Chapter> extractNeroChapters(File m4bFile) throws IOException {
    IsoFile isoFile = new IsoFile(m4bFile.getAbsolutePath());
    UserDataBox udta = isoFile.getMovieBox().getUserDataBox();
    if (udta == null) return Collections.emptyList();

    ChapterBox chpl = udta.getBox(ChapterBox.class); // custom box registration required
    // Parse chpl payload according to the layout above
    // Convert 100ns → ms: rawTimestamp / 10_000
    ...
}
```

### 5.4 Chapter Fallback

If no chapter metadata is found, the player falls back to a single chapter spanning the entire file. The UI should still display the total duration and allow seeking.

---

## 6. Playback Engine

### 6.1 MediaPlayer Configuration

`MediaPlayer` is created inside the foreground service. The critical configuration steps for low-power devices are:

```java
private void initMediaPlayer() {
    mediaPlayer = new MediaPlayer();
    mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
    mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
    mediaPlayer.setOnPreparedListener(this);
    mediaPlayer.setOnCompletionListener(this);
    mediaPlayer.setOnErrorListener(this);
    mediaPlayer.setOnSeekCompleteListener(this);
}
```

`setWakeMode(..., PARTIAL_WAKE_LOCK)` ensures the CPU remains awake while playing and automatically releases the lock when paused or stopped. Because M4B playback is local, a `WifiLock` is not needed; only acquire one if streaming from a network source is ever added.

### 6.2 Foreground Service

The service must promote itself to the foreground as soon as playback begins. This is mandatory on API 26+ and strongly recommended on all versions to prevent the system from killing playback during screen-off.

```java
@Override
public int onStartCommand(Intent intent, int flags, int startId) {
    if (ACTION_PLAY.equals(intent.getAction())) {
        startForeground(NOTIFICATION_ID, buildNotification());
        mediaPlayer.start();
    }
    return START_STICKY;
}
```

The notification must include at least:
- Play/Pause action
- Previous chapter / next chapter actions
- Content title (book title + chapter title)
- A `PendingIntent` that opens the player `Activity`

### 6.3 Seek Behaviour on API 16

`MediaPlayer.seekTo(int msec)` does not guarantee frame-accurate seeking. For audio-only M4B files, the seek target is rounded to the nearest sync frame, which is acceptable for audiobook navigation. Do **not** use the `seekTo(long, int)` overload—it was added in API 26 and will crash on API 16–25.

```java
// Correct for API 16+
mediaPlayer.seekTo((int) targetMs);
```

If precise chapter starts are critical, implement a “seek-and-correct” strategy: seek to `chapterStart - 200ms`, then begin playback and monitor `getCurrentPosition()` until it reaches the chapter boundary. This adds a small latency but guarantees correct chapter alignment on devices where the default seek granularity is coarse.

### 6.4 Playback Speed Limitation

`MediaPlayer.setPlaybackParams()` (which allows speed changes) was added in API 23. There is **no public API for variable playback speed on API 16–22** using `MediaPlayer`. The specification therefore has two options:

- **Option A (Recommended):** Hide the speed control on devices below API 23. Show it only on API 23+.
- **Option B:** Bundle a lightweight alternative such as a pre-compiled `SoundTouch` JNI library. This increases APK size and native complexity, contradicting the goal of low-footprint legacy support.

This specification adopts **Option A**.

---

## 7. Lock-Screen and Media Button Controls

### 7.1 MediaSessionCompat

`MediaSessionCompat` is the Support Library’s backward-compatible wrapper around `MediaSession`. On API 21+, it delegates to the platform `MediaSession`. On API 14–20, it automatically registers a `RemoteControlClient` internally, which is how lock-screen controls are surfaced on Jelly Bean and KitKat.

```java
private MediaSessionCompat mediaSession;

private void initMediaSession() {
    ComponentName receiver = new ComponentName(this, MediaButtonReceiver.class);
    mediaSession = new MediaSessionCompat(this, "AudiobookSession", receiver, null);
    mediaSession.setFlags(
        MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
    );
    mediaSession.setCallback(new MediaSessionCompat.Callback() {
        @Override public void onPlay()  { play(); }
        @Override public void onPause() { pause(); }
        @Override public void onSkipToNext()     { nextChapter(); }
        @Override public void onSkipToPrevious() { previousChapter(); }
        @Override public void onSeekTo(long pos) { seekTo((int) pos); }
    });
    mediaSession.setActive(true);
}
```

### 7.2 Metadata

Set `MediaMetadataCompat` on every chapter or file change:

```java
MediaMetadataCompat metadata = new MediaMetadataCompat.Builder()
    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, chapterTitle)
    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, authorName)
    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, totalDurationMs)
    .build();
mediaSession.setMetadata(metadata);
```

On API 14–20, artwork may not be displayed on all lock screens; this is a known platform limitation. Text metadata and transport controls will still function.

### 7.3 Playback State

Update the `PlaybackStateCompat` whenever the state changes (playing, paused, buffering). This is what drives the play/pause button state on the lock screen and in Bluetooth headset controls.

```java
PlaybackStateCompat state = new PlaybackStateCompat.Builder()
    .setActions(
        PlaybackStateCompat.ACTION_PLAY |
        PlaybackStateCompat.ACTION_PAUSE |
        PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
        PlaybackStateCompat.ACTION_SEEK_TO)
    .setState(isPlaying ? PlaybackStateCompat.STATE_PLAYING
                        : PlaybackStateCompat.STATE_PAUSED,
              currentPosition, 1.0f)
    .build();
mediaSession.setPlaybackState(state);
```

---

## 8. UI Layer

### 8.0 Design Language & Aesthetics (Material Design 1)

This application adheres strictly to the original **Material Design (Material 1)** design cues introduced with Android 5.0 Lollipop (and Support Library v21/v28):
- **Square, crisp geometry:** Avoid modern rounded corners, bubbly pill shapes, or heavy corner radii (e.g., Material You / MD3). All buttons, cards, dialog surfaces, path badges, and status indicators must feature flat rectangular shapes with sharp 90-degree corners.
- **Classic touch and action cues:** Rectangular raised or flat action buttons, standard FAB circles for primary playback actions, crisp borders, and subtle elevation/shadows.
- **Predictable, low-overhead layout trees:** Standard `LinearLayout`, `FrameLayout`, `ListView`, and `GridView` widgets for fast rasterization without nested layout passes on low-power CPUs.
- **Server Grid Thumbnail Presentation:** Thumbnail cards in the server audiobooks grid display the cover art by itself (using a 1:1 `SquareImageView`), avoiding text title overlays. A square green corner badge (`✓`, `#2E7D32`) at `top|end` indicates whether the audiobook file exists locally on device.
- **Publication Metadata & Item Details:** Publication year details are parsed from server metadata (`publishedYear`, `publishedDate`, or `releaseDate`) and retrieved dynamically via `/api/items/{id}` when inspecting book details in the info dialog.
- **Device File Modifications & Permissions:** Runtime and manifest write permissions (`WRITE_EXTERNAL_STORAGE` and `requestLegacyExternalStorage="true"`) are implemented to allow full file lifecycle operations directly on device (downloading server items and removing local audiobooks). Target download directories automatically verify writability, falling back to primary external storage (`/sdcard/Audiobooks`) if secondary SD cards are read-only on Android 5.x.

### 8.1 Activities

Keep the activity count minimal to reduce memory usage:

| Activity | Responsibility |
|---|---|
| `LibraryActivity` | Lists `.m4b` files found on external storage; launches `PlayerActivity` |
| `PlayerActivity` | Playback controls, chapter list, bookmark list |

`PlayerActivity` binds to the `PlaybackService` in `onStart()` and unbinds in `onStop()`. All playback commands go through the binder interface.

### 8.2 Player Screen Layout

Use a `LinearLayout` with `weight`-based sizing rather than nested `ConstraintLayout` hierarchies. Avoid `RecyclerView` for the chapter list if the chapter count is small (< 200); a `ListView` with a view holder is lighter on API 16 devices and has a smaller memory footprint. If `RecyclerView` is preferred, use the Support Library version and disable item animators.

**Key controls:**

- Seek bar with current position / total duration
- Play/Pause button
- Skip back 30 s / skip forward 30 s
- Chapter list (tap to jump)
- Bookmark list (tap to jump, long-press to delete)
- “Add bookmark” floating action button

### 8.3 Efficient Image Loading

If cover art is extracted from the M4B metadata (`covr` atom), decode it with `BitmapFactory.Options.inSampleSize` to avoid allocating a full-resolution bitmap. Cache the decoded bitmap in an `LruCache` sized to `Runtime.getRuntime().maxMemory() / 8`.

---

## 9. Background Behaviour and Lifecycle

### 9.1 Audio Focus

Request audio focus before starting playback. On API 16–25, use the legacy `AudioManager.requestAudioFocus(listener, STREAM_MUSIC, AUDIOFOCUS_GAIN)`. On API 26+, use `AudioFocusRequest`. The service must pause playback on `AUDIOFOCUS_LOSS` and duck or pause on transient loss depending on the focus type.

### 9.2 Service Lifecycle

```java
@Override
public void onDestroy() {
    if (mediaPlayer != null) {
        mediaPlayer.release();
        mediaPlayer = null;
    }
    if (mediaSession != null) {
        mediaSession.release();
    }
    stopForeground(true);
    super.onDestroy();
}
```

Release the `MediaPlayer` and `MediaSession` in `onDestroy()` to prevent native leaks. Call `stopForeground(true)` to remove the notification when playback ends.

### 9.3 Screen-Off Resilience

The combination of:

1. `MediaPlayer.setWakeMode(PARTIAL_WAKE_LOCK)`, and
2. running inside a foreground `Service`

is sufficient to keep audio playing when the screen turns off on API 16–28. A `WifiLock` is unnecessary for local files.

---

## 10. Performance Optimizations for Low-Powered Devices

| Optimization | Rationale |
|---|---|
| **Use `MediaPlayer`, not ExoPlayer** | ExoPlayer/Media3 adds ~1–2 MB to APK size and allocates more buffers; `MediaPlayer` uses hardware codecs via `Stagefright` and has a smaller heap footprint |
| **Parse chapters once, cache as JSON** | Avoids repeated `mp4parser` I/O on every player launch |
| **Use `ListView`, not `RecyclerView`** (for small lists) | Lower memory overhead and no item animator on API 16 |
| **Save resume position with `SharedPreferences.apply()`** | Non-blocking write to disk |
| **No native libraries** | Avoids JNI, reduces APK size, eliminates ABI splits |
| **Disable unnecessary animations** | Set `android:windowAnimationStyle` to `@null` on activities |
| **Use `SparseArray` instead of `HashMap<Integer, ...>`** | More memory-efficient for integer-keyed collections |
| **Avoid `Enum` where possible** | Enums carry per-value overhead; use `@IntDef` constants instead |

### 10.1 APK Size

Target APK size under **2 MB**. The only external library is `mp4parser` (≈ 300 KB). ProGuard/R8 should be enabled with rules that keep the `mp4parser` box classes:

```proguard
-keep class com.coremedia.iso.boxes.** { *; }
-keep class com.googlecode.mp4parser.** { *; }
```

---

## 11. Testing Matrix

| Device class | API level | What to verify |
|---|---|---|
| Low-end phone (512 MB RAM) | 16 | App launches within 2 s; playback survives screen-off; no `OutOfMemoryError` |
| Mid-range phone | 19 | Lock-screen controls appear; Bluetooth media buttons work |
| Phone with API 23 | 23 | Playback speed control is visible and functional |
| Phone with API 28 | 28 | Foreground service notification appears; background restrictions do not kill playback |

**Critical test cases:**

- Seek to a bookmark on API 16 → position lands within ±1 s of the bookmark.
- Rotate the device during playback → no interruption, resume position is preserved.
- Receive an incoming call → audio focus is lost, playback pauses, and resumes after the call (if transient loss).
- Kill the app from recents → service stops and notification is removed.
- Open a 10-hour M4B file → chapter list scrolls smoothly without `jank`.

---

## 12. Build Configuration Checklist

- [ ] `minSdkVersion 16`
- [ ] `targetSdkVersion 28`
- [ ] Java 8 source/target compatibility
- [ ] Support Library `appcompat-v7:28.0.0` and `support-compat:28.0.0`
- [ ] `isoparser:1.1.22` dependency
- [ ] ProGuard/R8 enabled with `mp4parser` keep rules
- [ ] `android:largeHeap="false"` (do not request large heap; optimize instead)
- [ ] Permissions: `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `FOREGROUND_SERVICE`, `WAKE_LOCK`, `INTERNET`

---

## 13. Summary of Key Technical Constraints

| Constraint | Reason | Mitigation |
|---|---|---|
| `MediaPlayer.setPlaybackParams()` unavailable below API 23 | Added in Android 6.0 | Hide speed control on API < 23 |
| `MediaSession` unavailable below API 21 | Added in Android 5.0 | Use `MediaSessionCompat` which falls back to `RemoteControlClient` on API 14–20 |
| `seekTo` not frame-accurate | `MediaPlayer` seeks to sync frames | Acceptable for audio; use seek-and-correct if chapter precision is required |
| Support Library is frozen at 28.0.0 for API 14+ | Google’s final Support Library release | Pin all dependencies to 28.0.0; do not upgrade to AndroidX |
| Foreground service required for background playback | System kills background services | Promote to foreground in `onStartCommand()` and keep notification alive during playback |

This specification provides a complete, actionable blueprint for implementing a legacy-compatible M4B audiobook player in Java. Every architectural decision has been made with the constraints of API 16–28 and low-powered hardware in mind.