package com.audeyshelf.audiobook.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.audeyshelf.audiobook.R;
import com.audeyshelf.audiobook.media.ChapterExtractor;
import com.audeyshelf.audiobook.media.PlaybackListener;
import com.audeyshelf.audiobook.media.PlaybackService;
import com.audeyshelf.audiobook.model.Audiobook;
import com.audeyshelf.audiobook.model.Chapter;
import com.audeyshelf.audiobook.ui.adapter.ChapterAdapter;
import com.audeyshelf.audiobook.util.AudioMetadataHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Displays the chapter-by-chapter list of an audiobook.
 * Follows reference screenshot be6742db.png.
 */
public class BookDetailActivity extends AppCompatActivity implements PlaybackListener {
    public static final String EXTRA_AUDIOBOOK_PATH = "audiobook_path";

    private String targetFilePath;
    private Audiobook currentBook;
    private List<Chapter> originalChapters = new ArrayList<>();
    private List<Chapter> displayedChapters = new ArrayList<>();

    private ImageView ivBack;
    private TextView tvTitle;
    private ListView lvChapters;
    private ProgressBar pbLoading;
    private ChapterAdapter adapter;

    // Mini Player
    private LinearLayout layoutMiniPlayer;
    private ImageView ivMiniCover;
    private TextView tvMiniTitle;
    private TextView tvMiniSubtitle;
    private ImageView ivMiniPlayPause;

    private PlaybackService.PlaybackBinder binder;
    private boolean isBound = false;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = (PlaybackService.PlaybackBinder) service;
            isBound = true;
            binder.addListener(BookDetailActivity.this);
            updateMiniPlayer();
            updateCurrentPlayingChapterHighlight();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_book_detail);

        targetFilePath = getIntent().getStringExtra(EXTRA_AUDIOBOOK_PATH);
        if (targetFilePath == null) {
            finish();
            return;
        }

        initViews();
        setupListeners();
        loadAudiobookData();
    }

    private void initViews() {
        ivBack = findViewById(R.id.ivDetailBack);
        tvTitle = findViewById(R.id.tvDetailTitle);
        lvChapters = findViewById(R.id.lvDetailChapters);
        pbLoading = findViewById(R.id.pbDetailLoading);

        // Mini player
        layoutMiniPlayer = findViewById(R.id.layoutMiniPlayer);
        ivMiniCover = findViewById(R.id.ivMiniCover);
        tvMiniTitle = findViewById(R.id.tvMiniTitle);
        tvMiniSubtitle = findViewById(R.id.tvMiniSubtitle);
        ivMiniPlayPause = findViewById(R.id.ivMiniPlayPause);
        ivMiniPlayPause.setColorFilter(ContextCompat.getColor(this, R.color.mini_player_control), PorterDuff.Mode.SRC_IN);

        adapter = new ChapterAdapter(this, new ArrayList<>());
        lvChapters.setAdapter(adapter);
    }

    private void setupListeners() {
        ivBack.setOnClickListener(v -> finish());

        lvChapters.setOnItemClickListener((parent, view, position, id) -> {
            Chapter chapter = adapter.getItem(position);
            if (chapter != null && binder != null) {
                Audiobook activeBook = binder.getCurrentAudiobook();
                if (activeBook == null || !targetFilePath.equals(activeBook.getFilePath())) {
                    binder.loadAudiobook(targetFilePath, false);
                }
                binder.seekToChapter(chapter.getIndex());
                updateCurrentPlayingChapterHighlight();
                updateMiniPlayer();
            }
        });

        layoutMiniPlayer.setOnClickListener(v -> {
            if (binder != null && binder.getCurrentAudiobook() != null) {
                Intent intent = new Intent(this, PlayerActivity.class);
                intent.putExtra(PlayerActivity.EXTRA_AUDIOBOOK_PATH, binder.getCurrentAudiobook().getFilePath());
                intent.putExtra(PlayerActivity.EXTRA_START_PLAY, false);
                startActivity(intent);
            }
        });

        ivMiniPlayPause.setOnClickListener(v -> {
            if (binder != null) {
                binder.togglePlayPause();
            }
        });
    }

    private void loadAudiobookData() {
        pbLoading.setVisibility(View.VISIBLE);
        new Thread(() -> {
            File file = new File(targetFilePath);
            currentBook = AudioMetadataHelper.extractAudiobookInfo(file);
            List<Chapter> chapters = ChapterExtractor.extractChapters(
                    BookDetailActivity.this,
                    file,
                    currentBook.getDurationMs()
            );
            originalChapters = new ArrayList<>(chapters);
            displayedChapters = new ArrayList<>(chapters);

            runOnUiThread(() -> {
                pbLoading.setVisibility(View.GONE);
                tvTitle.setText(currentBook.getTitle());
                adapter.setChapters(displayedChapters, -1);
                updateCurrentPlayingChapterHighlight();
            });
        }).start();
    }

    private void updateCurrentPlayingChapterHighlight() {
        if (binder == null) return;
        Audiobook active = binder.getCurrentAudiobook();
        if (active != null && targetFilePath.equals(active.getFilePath())) {
            int activeIndex = binder.getCurrentChapterIndex();
            // find position in displayedChapters
            int foundPos = -1;
            for (int i = 0; i < displayedChapters.size(); i++) {
                if (displayedChapters.get(i).getIndex() == activeIndex) {
                    foundPos = i;
                    break;
                }
            }
            adapter.setCurrentChapterIndex(foundPos);
        } else {
            adapter.setCurrentChapterIndex(-1);
        }
    }

    private void updateMiniPlayer() {
        if (binder == null) {
            layoutMiniPlayer.setVisibility(View.GONE);
            return;
        }

        Audiobook book = binder.getCurrentAudiobook();
        if (book == null) {
            layoutMiniPlayer.setVisibility(View.GONE);
            return;
        }

        layoutMiniPlayer.setVisibility(View.VISIBLE);

        Chapter ch = binder.getCurrentChapter();
        if (ch != null && ch.getTitle() != null && !ch.getTitle().isEmpty()) {
            tvMiniTitle.setText(ch.getTitle());
            tvMiniSubtitle.setText(book.getTitle());
        } else {
            tvMiniTitle.setText(book.getTitle());
            tvMiniSubtitle.setText(book.getAuthor());
        }

        ivMiniPlayPause.setImageResource(binder.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);

        new Thread(() -> {
            Bitmap cover = AudioMetadataHelper.loadCoverArt(book.getFilePath(), 100, 100);
            runOnUiThread(() -> {
                if (cover != null) {
                    ivMiniCover.setImageBitmap(cover);
                } else {
                    ivMiniCover.setImageResource(R.drawable.ic_book_placeholder);
                }
            });
        }).start();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent serviceIntent = new Intent(this, PlaybackService.class);
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isBound) {
            updateMiniPlayer();
            updateCurrentPlayingChapterHighlight();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
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
        runOnUiThread(this::updateMiniPlayer);
    }

    @Override
    public void onProgressUpdate(long currentPositionMs, long durationMs) {
    }

    @Override
    public void onChapterChanged(int chapterIndex, Chapter chapter) {
        runOnUiThread(() -> {
            updateMiniPlayer();
            updateCurrentPlayingChapterHighlight();
        });
    }

    @Override
    public void onAudiobookLoaded(Audiobook audiobook, List<Chapter> chapters) {
        runOnUiThread(() -> {
            updateMiniPlayer();
            updateCurrentPlayingChapterHighlight();
        });
    }

    @Override
    public void onError(String message) {
    }
}
