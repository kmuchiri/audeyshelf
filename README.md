# Audeyshelf

An offline M4B and Audiobookshelf audiobook player optimized for legacy and low-power Android devices (Android 4.1 Jelly Bean / API 16 through Android 9 Pie / API 28).

Audeyshelf prioritizes minimal memory footprint, low CPU usage, resilient background playback, chapter extraction, persistent bookmarks, and automatic playback progress resume.

---

## Features

- **Audiobook Library (`LibraryActivity`)**:
  - Automatically indexes `.m4b`, `.m4a`, and `.mp3` files from your chosen root directory.
  - Server browser with 3-column thumbnail grid view for Audiobookshelf items.
  - Classic Material Design 1 aesthetics with sharp rectangular geometry, avoiding modern bubbly/rounded corners.
  - Quick real-time title and author search filter.
  - "Continue Listening" hero banner with current book progress and one-tap resume.
  - Persistent bottom mini-player bar for seamless playback control while browsing.

- **Audiobook Player (`PlayerActivity`)**:
  - Scrubbing seek bar with current position and time remaining.
  - Responsive transport controls: Play/Pause, Skip Back 30s, Skip Forward 30s, Previous Chapter, Next Chapter.
  - Variable playback speed control (0.75x–2.0x) on Android 6.0+ (API 23+), cleanly hidden on older platforms.
  - Memory-efficient cover art rendering with downsampling (`inSampleSize`) and LRU heap caching.
  - Chapter drawer with active chapter indicator and direct chapter jumping.
  - Bookmarks drawer with timestamped bookmark creation, notes, and one-tap seek.

- **Settings (`SettingsActivity`)**:
  - **Root Folder Picker**: In-app filesystem directory picker allowing users to browse and select any directory on internal or SD storage (API 16+ compatible).
  - **Theme Support**: Seamless toggle between **Light Mode**, **Dark Mode ("Stak Mode")**, and **Follow System**.
  - **Skip Duration**: Configurable skip duration (10s, 15s, 30s, 60s).

- **Audio Engine & Background Resilience (`PlaybackService`)**:
  - `MediaPlayer` running in an Android foreground service with a persistent notification.
  - Audio focus management for handling phone calls, notifications, and transient ducking.
  - `MediaSessionCompat` for lock-screen media controls, Bluetooth headsets, and headphone unplugging detection (`ACTION_AUDIO_BECOMING_NOISY`).
  - `PowerManager.PARTIAL_WAKE_LOCK` to ensure uninterrupted playback with screen off.

- **Storage & Metadata**:
  - **M4B Chapter Parsing**: Extracts both QuickTime chapter tracks (`tref`/`chap`) and Nero `chpl` user data boxes using `mp4parser` (pure Java, no native binaries).
  - **Chapter Cache**: Parsed chapters are cached in JSON and validated against file modification times.
  - **Resume Persistence**: Stored via `SharedPreferences.apply()` every 30 seconds and on pause.
  - **Bookmarks DB**: Indexed SQLite database (`audiobook.db`).

- **Ultra-lightweight APK**:
  - Minified release APK size is **~1.1 MB** (well under the 2 MB target in the specification).

---

## Building the Project

### Prerequisites
- JDK 11 or higher
- Android SDK installed (`platforms/android-34` or compatible)

### Build Debug APK
```bash
./gradlew :app:assembleDebug
```
The output APK will be in `app/build/outputs/apk/debug/app-debug.apk`.

### Run Unit Tests
```bash
./gradlew test
```

### Build Minified Release APK
```bash
./gradlew :app:assembleRelease
```
The output APK will be in `app/build/outputs/apk/release/app-release-unsigned.apk`.

---

## Architecture Overview

```
┌────────────────────────────────────────────────────────┐
│ UI Layer (AppCompat DayNight)                          │
│ - LibraryActivity (Search, Continue Card, Mini Player) │
│ - PlayerActivity (Scrub bar, Chapters, Bookmarks)      │
│ - SettingsActivity (Theme mode, Root Folder Picker)    │
│ - FolderPickerDialog (API 16+ Filesystem browser)      │
└───────────────────────────┬────────────────────────────┘
                            │ binds / local Binder
┌───────────────────────────▼────────────────────────────┐
│ Playback Service (Foreground Service)                  │
│ - MediaPlayer + AudioFocus + Partial WakeLock          │
│ - MediaSessionCompat (Lock screen & Bluetooth)         │
│ - NotificationCompat.MediaStyle                        │
└───────────────────────────┬────────────────────────────┘
                            │ reads / writes
┌───────────────────────────▼────────────────────────────┐
│ Data Layer                                             │
│ - ResumeManager (SharedPreferences apply())            │
│ - BookmarkRepository (SQLiteOpenHelper + Executor)     │
│ - ChapterCache (JSON cache with timestamp validation)  │
│ - ChapterExtractor (QuickTime / Nero chpl via isoparser│
└────────────────────────────────────────────────────────┘
```
