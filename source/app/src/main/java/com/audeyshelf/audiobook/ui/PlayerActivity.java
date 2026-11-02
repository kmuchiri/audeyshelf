package com.audeyshelf.audiobook.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.audeyshelf.audiobook.R;
import com.audeyshelf.audiobook.data.BookmarkRepository;
import com.audeyshelf.audiobook.data.ResumeManager;
import com.audeyshelf.audiobook.data.SettingsManager;
import com.audeyshelf.audiobook.media.PlaybackListener;
import com.audeyshelf.audiobook.media.PlaybackService;
import com.audeyshelf.audiobook.model.Audiobook;
import com.audeyshelf.audiobook.model.Bookmark;
import com.audeyshelf.audiobook.model.Chapter;
import com.audeyshelf.audiobook.ui.adapter.BookmarkAdapter;
import com.audeyshelf.audiobook.ui.adapter.ChapterAdapter;
import com.audeyshelf.audiobook.util.AudioMetadataHelper;
import com.audeyshelf.audiobook.util.TimeFormatter;

import java.io.File;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PlayerActivity matching reference screenshots bdb68168.png and 9ad651e3.png.
 * Optimized for smaller screens with dynamic cover sizing and chapter list toggling.
 */
public class PlayerActivity extends AppCompatActivity implements PlaybackListener {
    public static final String EXTRA_AUDIOBOOK_PATH = "audiobook_path";
    public static final String EXTRA_START_PLAY = "start_play";

    private String targetFilePath;
    private boolean startPlayingRequested = false;

    private PlaybackService.PlaybackBinder binder;
    private boolean isBound = false;

    // View Containers
    private View layoutCoverView;
    private View layoutChaptersView;

    // Cover Mode Views
    private ImageView ivCover;
    private TextView tvChapterTitle;
    private TextView tvBookSubtitle;
    private TextView tvAuthor;

    // Chapter List Mode Views
    private ListView lvChapters;
    private ChapterAdapter chapterAdapter;

    // Bottom Controls
    private SeekBar sbProgress;
    private TextView tvCurrentTime;
    private TextView tvRemainingTime;
    private View layoutTimestamps;

    // Controls - Secondary Row (above seekbar)
    private View layoutSleepTimer;
    private ImageView ivSleepTimer;
    private TextView tvSleepTimer;
    private ImageView ivBookmark;
    private ImageView ivChapters;
    private ImageView ivOptions;

    // Controls - Main Playback Row
    private ImageView ivPrevChapter;
    private ImageView ivRewind;
    private ImageView ivPlayPause;
    private ImageView ivForward;
    private ImageView ivNextChapter;

    private BookmarkRepository bookmarkRepository;
    private SettingsManager settingsManager;
    private boolean isUserSeeking = false;
    private boolean showTotalTime = false;
    private boolean isRecreating = false;
    private AlertDialog playerOptionsDialog;

    private boolean hasPhysicalMenuKey() {
        boolean hasPermanentMenuKey = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            hasPermanentMenuKey = ViewConfiguration.get(this).hasPermanentMenuKey();
        } else {
            hasPermanentMenuKey = true;
        }
        boolean hasMenuKey = KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_MENU);
        return hasPermanentMenuKey || hasMenuKey;
    }

    private boolean isSameFile(String path1, String path2) {
        if (path1 == null || path2 == null) return false;
        if (path1.equals(path2)) return true;
        try {
            return new File(path1).getCanonicalPath().equals(new File(path2).getCanonicalPath());
        } catch (Exception e) {
            return new File(path1).getAbsolutePath().equals(new File(path2).getAbsolutePath());
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = (PlaybackService.PlaybackBinder) service;
            isBound = true;
            binder.addListener(PlayerActivity.this);
            android.util.Log.d("PlayerActivity", "onServiceConnected targetFilePath=" + targetFilePath + " current=" + binder.getCurrentAudiobook());

            Audiobook current = binder.getCurrentAudiobook();
            if (current != null && (targetFilePath == null || isSameFile(targetFilePath, current.getFilePath()) || isRecreating)) {
                targetFilePath = current.getFilePath();
                updateUIFromBinder();
                if (startPlayingRequested && !binder.isPlaying()) {
                    binder.play();
                }
                startPlayingRequested = false;
            } else if (targetFilePath != null) {
                binder.loadAudiobook(targetFilePath, startPlayingRequested);
                startPlayingRequested = false;
            } else {
                ResumeManager rm = new ResumeManager(PlayerActivity.this);
                String lastPath = rm.getLastPlayedAudiobookPath();
                if (lastPath != null) {
                    binder.loadAudiobook(lastPath, false);
                } else {
                    updateUIFromBinder();
                }
                startPlayingRequested = false;
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
            isBound = false;
        }
    };

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.hasExtra(EXTRA_AUDIOBOOK_PATH)) {
            targetFilePath = intent.getStringExtra(EXTRA_AUDIOBOOK_PATH);
            startPlayingRequested = intent.getBooleanExtra(EXTRA_START_PLAY, false);
            if (binder != null && targetFilePath != null) {
                Audiobook current = binder.getCurrentAudiobook();
                if (current == null || !isSameFile(targetFilePath, current.getFilePath())) {
                    binder.loadAudiobook(targetFilePath, startPlayingRequested);
                } else {
                    updateUIFromBinder();
                    if (startPlayingRequested && !binder.isPlaying()) {
                        binder.play();
                    }
                }
                startPlayingRequested = false;
            }
        }
    }

    private static final String KEY_SHOW_CHAPTERS = "show_chapters";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settingsManager = new SettingsManager(this);
        bookmarkRepository = new BookmarkRepository(this);

        if (savedInstanceState != null) {
            isRecreating = true;
            startPlayingRequested = false;
        } else {
            startPlayingRequested = getIntent().getBooleanExtra(EXTRA_START_PLAY, false);
        }
        targetFilePath = getIntent().getStringExtra(EXTRA_AUDIOBOOK_PATH);
        android.util.Log.d("PlayerActivity", "onCreate targetFilePath=" + targetFilePath + " isRecreating=" + isRecreating);

        setContentView(R.layout.activity_player);

        initViews();
        setupListeners();

        if (savedInstanceState != null && savedInstanceState.getBoolean(KEY_SHOW_CHAPTERS, false)) {
            layoutCoverView.setVisibility(View.GONE);
            layoutChaptersView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        boolean showingChapters = (layoutChaptersView != null && layoutChaptersView.getVisibility() == View.VISIBLE);
        outState.putBoolean(KEY_SHOW_CHAPTERS, showingChapters);
    }

    private void initViews() {
        layoutCoverView = findViewById(R.id.layoutPlayerCoverView);
        layoutChaptersView = findViewById(R.id.layoutPlayerChaptersView);

        ivCover = findViewById(R.id.ivPlayerCover);
        tvChapterTitle = findViewById(R.id.tvPlayerChapterTitle);
        tvBookSubtitle = findViewById(R.id.tvPlayerBookSubtitle);
        tvAuthor = findViewById(R.id.tvPlayerAuthor);

        lvChapters = findViewById(R.id.lvPlayerChapters);
        chapterAdapter = new ChapterAdapter(this, new ArrayList<>());
        lvChapters.setAdapter(chapterAdapter);

        sbProgress = findViewById(R.id.sbPlayerProgress);
        tvCurrentTime = findViewById(R.id.tvPlayerCurrentTime);
        tvRemainingTime = findViewById(R.id.tvPlayerRemainingTime);
        layoutTimestamps = findViewById(R.id.layoutTimestamps);

        layoutSleepTimer = findViewById(R.id.layoutPlayerSleepTimer);
        ivSleepTimer = findViewById(R.id.ivPlayerSleepTimer);
        tvSleepTimer = findViewById(R.id.tvPlayerSleepTimer);
        ivBookmark = findViewById(R.id.ivPlayerBookmark);
        ivChapters = findViewById(R.id.ivPlayerChapters);
        ivOptions = findViewById(R.id.ivPlayerOptions);

        ivPrevChapter = findViewById(R.id.ivPlayerPrevChapter);
        ivRewind = findViewById(R.id.ivPlayerRewind);
        ivPlayPause = findViewById(R.id.ivPlayerPlayPause);
        ivForward = findViewById(R.id.ivPlayerForward);
        ivNextChapter = findViewById(R.id.ivPlayerNextChapter);

        if (tvChapterTitle != null) {
            tvChapterTitle.setSelected(true);
        }

        adjustCoverSize();
        applyControlTints();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tvChapterTitle != null) {
            tvChapterTitle.setSelected(true);
        }
        updateSleepTimerUI();
    }

    private void adjustCoverSize() {
        if (ivCover == null) return;
        boolean isLandscape = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        if (isLandscape) {
            android.view.ViewGroup.LayoutParams lp = ivCover.getLayoutParams();
            lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
            ivCover.setLayoutParams(lp);
            return;
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenWidth = dm.widthPixels;
        int screenHeight = dm.heightPixels;
        int paddingPx = (int) (32 * dm.density);
        int maxDisplayPx = getResources().getDimensionPixelSize(R.dimen.cover_max_display_size);
        int targetSize = Math.min(screenWidth - paddingPx, maxDisplayPx);
        int maxAvailableHeight = (int) (screenHeight * 0.50f);
        targetSize = Math.min(targetSize, maxAvailableHeight);
        if (targetSize > 0) {
            android.view.ViewGroup.LayoutParams lp = ivCover.getLayoutParams();
            lp.width = targetSize;
            lp.height = targetSize;
            ivCover.setLayoutParams(lp);
        }
    }

    private void applyControlTints() {
        int color = ContextCompat.getColor(this, R.color.player_control);
        if (ivPrevChapter != null) ivPrevChapter.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        if (ivRewind != null) ivRewind.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        if (ivPlayPause != null) ivPlayPause.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        if (ivForward != null) ivForward.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        if (ivNextChapter != null) ivNextChapter.setColorFilter(color, PorterDuff.Mode.SRC_IN);

        if (ivBookmark != null) ivBookmark.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        if (ivChapters != null) ivChapters.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        if (ivOptions != null) ivOptions.setColorFilter(color, PorterDuff.Mode.SRC_IN);

        updateSleepTimerUI();
    }

    private void setupListeners() {
        // Secondary row controls (above seek bar)
        View.OnClickListener sleepTimerClickListener = v -> showSleepTimerDialog();
        if (layoutSleepTimer != null) layoutSleepTimer.setOnClickListener(sleepTimerClickListener);
        if (ivSleepTimer != null) ivSleepTimer.setOnClickListener(sleepTimerClickListener);
        if (tvSleepTimer != null) tvSleepTimer.setOnClickListener(sleepTimerClickListener);

        if (ivBookmark != null) {
            ivBookmark.setOnClickListener(v -> showBookmarkActionDialog());
        }

        if (ivChapters != null) {
            ivChapters.setOnClickListener(v -> toggleChapterView());
        }

        if (ivOptions != null) {
            ivOptions.setVisibility(View.GONE);
        }

        // Chapter item click in chapter list view
        lvChapters.setOnItemClickListener((parent, view, position, id) -> {
            if (binder != null) {
                Chapter ch = chapterAdapter.getItem(position);
                if (ch != null) {
                    binder.seekToChapter(ch.getIndex());
                    chapterAdapter.setCurrentChapterIndex(position);
                    // Switch back to Cover view after selection
                    layoutChaptersView.setVisibility(View.GONE);
                    layoutCoverView.setVisibility(View.VISIBLE);
                }
            }
        });

        // Main playback row controls
        if (ivPrevChapter != null) {
            ivPrevChapter.setOnClickListener(v -> {
                if (binder != null) {
                    binder.previousChapter();
                }
            });
        }

        if (ivRewind != null) {
            ivRewind.setOnClickListener(v -> {
                if (binder != null) {
                    binder.skipBackward(settingsManager.getSkipDurationSeconds());
                }
            });
        }

        if (ivPlayPause != null) {
            ivPlayPause.setOnClickListener(v -> {
                if (binder != null) {
                    binder.togglePlayPause();
                }
            });
        }

        if (ivForward != null) {
            ivForward.setOnClickListener(v -> {
                if (binder != null) {
                    binder.skipForward(settingsManager.getSkipDurationSeconds());
                }
            });
        }

        if (ivNextChapter != null) {
            ivNextChapter.setOnClickListener(v -> {
                if (binder != null) {
                    binder.nextChapter();
                }
            });
        }

        // Toggle Chapter-relative time vs Total Book time
        layoutTimestamps.setOnClickListener(v -> {
            showTotalTime = !showTotalTime;
            Toast.makeText(this, showTotalTime ? "Showing Total Book Time" : "Showing Chapter Time", Toast.LENGTH_SHORT).show();
            if (binder != null) {
                updateProgressUI(binder.getCurrentPosition(), binder.getDuration());
            }
        });

        // Long press cover to open Bookmarks / Speed / Options
        ivCover.setOnLongClickListener(v -> {
            showPlayerOptionsMenu();
            return true;
        });

        // Scrubbing SeekBar
        sbProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && binder != null) {
                    Chapter ch = binder.getCurrentChapter();
                    if (!showTotalTime && ch != null && ch.getDurationMs() > 0) {
                        tvCurrentTime.setText(TimeFormatter.formatMs(progress));
                    } else {
                        tvCurrentTime.setText(TimeFormatter.formatMs(progress));
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isUserSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (binder != null) {
                    Chapter ch = binder.getCurrentChapter();
                    long targetMs;
                    if (!showTotalTime && ch != null && ch.getDurationMs() > 0) {
                        targetMs = ch.getStartMs() + seekBar.getProgress();
                    } else {
                        targetMs = seekBar.getProgress();
                    }
                    binder.seekTo(targetMs);
                }
                isUserSeeking = false;
            }
        });
    }

    private void toggleChapterView() {
        if (layoutChaptersView.getVisibility() == View.VISIBLE) {
            layoutChaptersView.setVisibility(View.GONE);
            layoutCoverView.setVisibility(View.VISIBLE);
        } else {
            layoutCoverView.setVisibility(View.GONE);
            layoutChaptersView.setVisibility(View.VISIBLE);
            if (binder != null) {
                int activeIndex = binder.getCurrentChapterIndex();
                chapterAdapter.setCurrentChapterIndex(activeIndex);
                if (activeIndex >= 0 && activeIndex < chapterAdapter.getCount()) {
                    lvChapters.setSelection(activeIndex);
                }
            }
        }
    }

    private void updateUIFromBinder() {
        if (binder == null) return;

        Audiobook book = binder.getCurrentAudiobook();
        Chapter ch = binder.getCurrentChapter();

        if (book != null) {
            if (tvAuthor != null) {
                // Landscape: title of the book and author on two separate lines
                if (ch != null && ch.getTitle() != null && !ch.getTitle().isEmpty() && !ch.getTitle().equals(book.getTitle())) {
                    tvChapterTitle.setText(ch.getTitle());
                    tvBookSubtitle.setText(book.getTitle());
                    tvBookSubtitle.setVisibility(View.VISIBLE);
                    tvAuthor.setText(book.getAuthor());
                    tvAuthor.setVisibility(View.VISIBLE);
                } else {
                    tvChapterTitle.setText(book.getTitle());
                    tvBookSubtitle.setVisibility(View.GONE);
                    tvAuthor.setText(book.getAuthor());
                    tvAuthor.setVisibility(View.VISIBLE);
                }
            } else {
                // Portrait: combined on one line with bullet
                tvBookSubtitle.setText(book.getTitle() + " • " + book.getAuthor());
                tvBookSubtitle.setVisibility(View.VISIBLE);
                if (ch != null && ch.getTitle() != null && !ch.getTitle().isEmpty()) {
                    tvChapterTitle.setText(ch.getTitle());
                } else {
                    tvChapterTitle.setText(book.getTitle());
                }
                tvChapterTitle.setSelected(true);
            }

            // Load cover art rendered based on screen resolution
            new Thread(() -> {
                DisplayMetrics dm = getResources().getDisplayMetrics();
                int screenRes = Math.max(dm.widthPixels, dm.heightPixels);
                Bitmap cover = AudioMetadataHelper.loadCoverArt(book.getFilePath(), screenRes, screenRes);
                runOnUiThread(() -> {
                    if (cover != null) {
                        ivCover.setImageBitmap(cover);
                    } else {
                        ivCover.setImageResource(R.drawable.ic_book_placeholder);
                    }
                });
            }).start();
        }

        List<Chapter> chapters = binder.getChapters();
        if (chapters != null) {
            chapterAdapter.setChapters(chapters, binder.getCurrentChapterIndex());
        }

        if (tvAuthor == null) {
            if (ch != null && ch.getTitle() != null && !ch.getTitle().isEmpty()) {
                tvChapterTitle.setText(ch.getTitle());
            } else if (book != null) {
                tvChapterTitle.setText(book.getTitle());
            }
            tvChapterTitle.setSelected(true);
        }

        ivPlayPause.setImageResource(binder.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        ivPlayPause.setColorFilter(ContextCompat.getColor(this, R.color.player_control), PorterDuff.Mode.SRC_IN);
        updateProgressUI(binder.getCurrentPosition(), binder.getDuration());
        updateSleepTimerUI();
    }

    private void updateProgressUI(long currentPos, long totalDuration) {
        if (isUserSeeking || binder == null) return;

        Chapter ch = binder.getCurrentChapter();
        if (!showTotalTime && ch != null && ch.getDurationMs() > 0) {
            // Chapter-by-chapter time mode
            long chElapsed = Math.max(0, currentPos - ch.getStartMs());
            long chDur = ch.getDurationMs();

            sbProgress.setMax((int) chDur);
            sbProgress.setProgress((int) Math.min(chElapsed, chDur));

            tvCurrentTime.setText(TimeFormatter.formatMs(chElapsed));
            tvRemainingTime.setText(TimeFormatter.formatMs(chDur));
        } else {
            // Total book time mode
            sbProgress.setMax((int) totalDuration);
            sbProgress.setProgress((int) currentPos);

            tvCurrentTime.setText(TimeFormatter.formatMs(currentPos));
            tvRemainingTime.setText(TimeFormatter.formatMs(totalDuration));
        }
    }

    private final Handler sleepTimerHandler = new Handler(Looper.getMainLooper());
    private final Runnable sleepTimerRunnable = new Runnable() {
        @Override
        public void run() {
            updateSleepTimerUI();
            if (binder != null && binder.isSleepTimerActive()) {
                sleepTimerHandler.postDelayed(this, 1000);
            }
        }
    };

    private void updateSleepTimerUI() {
        sleepTimerHandler.removeCallbacks(sleepTimerRunnable);
        if (binder != null && binder.isSleepTimerActive()) {
            int remainingMin = binder.getRemainingSleepMinutes();
            String remainingText = remainingMin + " min";
            if (tvSleepTimer != null) {
                tvSleepTimer.setText(remainingText);
                tvSleepTimer.setVisibility(View.VISIBLE);
            }
            if (ivSleepTimer != null) {
                ivSleepTimer.setVisibility(View.GONE);
            }
            sleepTimerHandler.postDelayed(sleepTimerRunnable, 1000);
        } else {
            if (tvSleepTimer != null) {
                tvSleepTimer.setVisibility(View.GONE);
            }
            if (ivSleepTimer != null) {
                ivSleepTimer.setVisibility(View.VISIBLE);
                ivSleepTimer.setColorFilter(ContextCompat.getColor(this, R.color.player_control), PorterDuff.Mode.SRC_IN);
            }
        }
    }

    private void showSleepTimerDialog() {
        if (binder == null) return;

        final String[] options = new String[]{"Off", "5 minutes", "15 minutes", "30 minutes", "45 minutes", "60 minutes"};
        final int[] minutes = new int[]{0, 5, 15, 30, 45, 60};

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Sleep Timer");
        builder.setItems(options, (dialog, which) -> {
            int selectedMin = minutes[which];
            if (selectedMin == 0) {
                binder.cancelSleepTimer();
                Toast.makeText(PlayerActivity.this, "Sleep timer turned off", Toast.LENGTH_SHORT).show();
            } else {
                binder.startSleepTimer(selectedMin);
                Toast.makeText(PlayerActivity.this, "Sleep timer set for " + selectedMin + " minutes", Toast.LENGTH_SHORT).show();
            }
            updateSleepTimerUI();
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }

    private void showBookmarkActionDialog() {
        final String[] options = new String[]{"Add Bookmark", "View Bookmarks"};
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.bookmarks);
        builder.setItems(options, (dialog, which) -> {
            if (which == 0) {
                showAddBookmarkDialog();
            } else {
                showBookmarksDialog();
            }
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }

    private void showPlayerOptionsMenu() {
        List<String> items = new ArrayList<>();
        final boolean canControlSpeed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
        if (canControlSpeed) {
            float currentSpeed = (binder != null) ? binder.getPlaybackSpeed() : 1.0f;
            items.add("Playback Speed (" + String.format(Locale.US, "%.1fx", currentSpeed) + ")");
        }
        items.add("Add Bookmark");
        items.add("View Bookmarks");

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Options");
        builder.setItems(items.toArray(new String[0]), (dialog, which) -> {
            int index = 0;
            if (canControlSpeed) {
                if (which == index++) {
                    showSpeedDialog();
                    return;
                }
            }
            if (which == index++) {
                showAddBookmarkDialog();
            } else if (which == index) {
                showBookmarksDialog();
            }
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        playerOptionsDialog = builder.create();
        playerOptionsDialog.show();
    }

    private void showAddBookmarkDialog() {
        if (binder == null) return;
        final long currentPos = binder.getCurrentPosition();
        Chapter currentChapter = binder.getCurrentChapter();
        final String chapterTitle = currentChapter != null ? currentChapter.getTitle() : "";

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.add_bookmark);

        final EditText etNote = new EditText(this);
        etNote.setHint("Bookmark note (optional)");
        builder.setView(etNote);

        builder.setPositiveButton(R.string.save, (dialog, which) -> {
            String note = etNote.getText().toString().trim();
            Audiobook book = binder.getCurrentAudiobook();
            if (book != null) {
                String label = note.isEmpty() ? (chapterTitle.isEmpty() ? "Bookmark" : chapterTitle) : note;
                Bookmark b = new Bookmark(book.getFilePath(), currentPos, label);
                bookmarkRepository.addBookmark(b, newId -> {
                    Toast.makeText(PlayerActivity.this, R.string.bookmark_added, Toast.LENGTH_SHORT).show();
                });
            }
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }

    private void showBookmarksDialog() {
        if (binder == null || binder.getCurrentAudiobook() == null) return;
        String path = binder.getCurrentAudiobook().getFilePath();
        bookmarkRepository.getBookmarks(path, bookmarks -> {
            if (bookmarks.isEmpty()) {
                Toast.makeText(PlayerActivity.this, R.string.no_bookmarks, Toast.LENGTH_SHORT).show();
                return;
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(PlayerActivity.this);
            builder.setTitle(R.string.bookmarks);

            final BookmarkAdapter adapter = new BookmarkAdapter(PlayerActivity.this, bookmarks, bookmark -> {
                bookmarkRepository.deleteBookmark(bookmark.getId(), success -> {
                    Toast.makeText(PlayerActivity.this, R.string.bookmark_deleted, Toast.LENGTH_SHORT).show();
                });
            });

            ListView listView = new ListView(PlayerActivity.this);
            listView.setAdapter(adapter);
            listView.setOnItemClickListener((parent, view, position, id) -> {
                Bookmark b = adapter.getItem(position);
                if (b != null && binder != null) {
                    binder.seekTo(b.getPositionMs());
                }
            });
            builder.setView(listView);

            builder.setPositiveButton(android.R.string.ok, null);
            builder.show();
        });
    }

    private void showSpeedDialog() {
        if (binder == null) return;

        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.dialog_playback_speed, null);

        TextView tvSpeedValue = dialogView.findViewById(R.id.tvSpeedValue);
        Button btnMinus = dialogView.findViewById(R.id.btnSpeedMinus);
        Button btnPlus = dialogView.findViewById(R.id.btnSpeedPlus);
        Button btnReset = dialogView.findViewById(R.id.btnSpeedReset);
        Button btnDone = dialogView.findViewById(R.id.btnSpeedDone);

        final float[] speedHolder = new float[]{ binder.getPlaybackSpeed() };
        tvSpeedValue.setText(String.format(Locale.US, "%.1fx", speedHolder[0]));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        btnMinus.setOnClickListener(v -> {
            float newSpeed = Math.round((speedHolder[0] - 0.1f) * 10.0f) / 10.0f;
            if (newSpeed < 0.5f) newSpeed = 0.5f;
            speedHolder[0] = newSpeed;
            tvSpeedValue.setText(String.format(Locale.US, "%.1fx", newSpeed));
            binder.setPlaybackSpeed(newSpeed);
        });

        btnPlus.setOnClickListener(v -> {
            float newSpeed = Math.round((speedHolder[0] + 0.1f) * 10.0f) / 10.0f;
            if (newSpeed > 3.0f) newSpeed = 3.0f;
            speedHolder[0] = newSpeed;
            tvSpeedValue.setText(String.format(Locale.US, "%.1fx", newSpeed));
            binder.setPlaybackSpeed(newSpeed);
        });

        btnReset.setOnClickListener(v -> {
            speedHolder[0] = 1.0f;
            tvSpeedValue.setText("1.0x");
            binder.setPlaybackSpeed(1.0f);
        });

        btnDone.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    @Override
    public void onBackPressed() {
        if (layoutChaptersView.getVisibility() == View.VISIBLE) {
            layoutChaptersView.setVisibility(View.GONE);
            layoutCoverView.setVisibility(View.VISIBLE);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (playerOptionsDialog != null && playerOptionsDialog.isShowing()) {
                playerOptionsDialog.dismiss();
            } else {
                showPlayerOptionsMenu();
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent serviceIntent = new Intent(this, PlaybackService.class);
        startService(serviceIntent);
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (playerOptionsDialog != null && playerOptionsDialog.isShowing()) {
            playerOptionsDialog.dismiss();
        }
        sleepTimerHandler.removeCallbacks(sleepTimerRunnable);
        if (isBound) {
            if (binder != null) {
                binder.removeListener(this);
            }
            unbindService(connection);
            isBound = false;
        }
    }

    // PlaybackListener callbacks
    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        runOnUiThread(() -> {
            ivPlayPause.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
            ivPlayPause.setColorFilter(ContextCompat.getColor(this, R.color.player_control), PorterDuff.Mode.SRC_IN);
            updateSleepTimerUI();
        });
    }

    @Override
    public void onProgressUpdate(long currentPositionMs, long durationMs) {
        runOnUiThread(() -> {
            updateProgressUI(currentPositionMs, durationMs);
            updateSleepTimerUI();
        });
    }

    @Override
    public void onChapterChanged(int chapterIndex, Chapter chapter) {
        runOnUiThread(() -> {
            if (binder != null) {
                updateUIFromBinder();
            } else if (chapter != null && chapter.getTitle() != null && !chapter.getTitle().isEmpty()) {
                tvChapterTitle.setText(chapter.getTitle());
                tvChapterTitle.setSelected(true);
            }
            chapterAdapter.setCurrentChapterIndex(chapterIndex);
            if (binder != null) {
                updateProgressUI(binder.getCurrentPosition(), binder.getDuration());
            }
        });
    }

    @Override
    public void onAudiobookLoaded(Audiobook audiobook, List<Chapter> chapters) {
        runOnUiThread(this::updateUIFromBinder);
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }
}
