package com.audeyshelf.audiobook.ui;

import android.Manifest;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import androidx.appcompat.widget.PopupMenu;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.audeyshelf.audiobook.R;
import com.audeyshelf.audiobook.data.ResumeManager;
import com.audeyshelf.audiobook.data.SettingsManager;
import com.audeyshelf.audiobook.media.PlaybackListener;
import com.audeyshelf.audiobook.media.PlaybackService;
import com.audeyshelf.audiobook.model.Audiobook;
import com.audeyshelf.audiobook.model.Chapter;
import com.audeyshelf.audiobook.net.AudiobookshelfClient;
import com.audeyshelf.audiobook.ui.adapter.AudiobookAdapter;
import com.audeyshelf.audiobook.ui.adapter.ServerAudiobookGridAdapter;
import com.audeyshelf.audiobook.util.AudioMetadataHelper;
import com.audeyshelf.audiobook.util.AudiobookScanner;
import com.audeyshelf.audiobook.util.ImageCache;
import com.audeyshelf.audiobook.util.StorageFileHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;

/**
 * LibraryActivity featuring a clean 3-column GridView of audiobooks, Castaway-inspired header,
 * and a persistent mini-player.
 * Follows spec.md Section 8.1 and references.
 */
public class LibraryActivity extends AppCompatActivity implements PlaybackListener {
    private static final int REQUEST_STORAGE_PERMISSION = 101;
    private static final int REQUEST_SETTINGS = 102;
    private static final int REQUEST_CODE_SAF_DELETE = 104;
    private static final int REQUEST_CODE_SAF_DOWNLOAD = 105;

    private Audiobook pendingDeleteBook;
    private Dialog pendingInfoDialog;
    private Runnable pendingUpdateButtonStates;

    private Audiobook pendingDownloadBook;
    private File pendingDownloadTargetDir;
    private Dialog pendingDownloadDialog;
    private ProgressBar pendingDownloadProgressBar;
    private TextView pendingDownloadStatusTextView;
    private View pendingDownloadProgressLayout;
    private Button pendingDownloadButton;
    private Runnable pendingDownloadUpdateButtonStates;

    private static final String KEY_CURRENT_VIEW_MODE = "key_current_view_mode";
    private static final String KEY_SERVER_AUDIOBOOKS = "key_server_audiobooks";

    public static final int VIEW_MODE_LIBRARY = 0;
    public static final int VIEW_MODE_SERVER = 1;
    private int currentViewMode = VIEW_MODE_LIBRARY;

    private SettingsManager settingsManager;
    private ResumeManager resumeManager;
    private AudiobookScanner scanner;
    private AudiobookshelfClient absClient;

    private Dialog bottomMenuDialog;

    private DrawerLayout drawerLayout;
    private ImageView ivNavDrawer;
    private TextView tvHeaderTitle;
    private ImageView ivHeaderRefresh;

    // Drawer Elements
    private View navItemLibrary;
    private View navItemServer;
    private View navItemServerSettings;
    private ImageView ivNavLibraryIcon;
    private ImageView ivNavServerIcon;
    private TextView tvNavLibraryTitle;
    private TextView tvNavServerTitle;
    private TextView tvNavLibraryCount;
    private TextView tvNavServerBadge;
    private TextView tvDrawerServerStatus;

    private ListView lvAudiobooks;
    private GridView gvServerAudiobooks;
    private AudiobookAdapter adapter;
    private ServerAudiobookGridAdapter serverGridAdapter;
    private List<Audiobook> allAudiobooks = new ArrayList<>();
    private List<Audiobook> serverAudiobooks = new ArrayList<>();

    private ImageView ivHeaderMenu;
    private ProgressBar pbLoading;
    private LinearLayout layoutEmptyState;
    private TextView tvCurrentFolderEmpty;
    private Button btnEmptyChooseFolder;

    // Server Empty / Disconnected State
    private LinearLayout layoutServerEmptyState;
    private ImageView ivServerEmptyIcon;
    private TextView tvServerEmptyTitle;
    private TextView tvServerEmptyDesc;
    private Button btnServerEmptyAction;

    // Mini Player
    private LinearLayout layoutMiniPlayer;
    private ImageView ivMiniCover;
    private TextView tvMiniTitle;
    private TextView tvMiniSubtitle;
    private ImageView ivMiniPlayPause;

    // Floating Play Button
    private ImageButton fabPlay;

    private PlaybackService.PlaybackBinder binder;
    private boolean isBound = false;
    private Audiobook pendingAudiobookToPlay;
    private Audiobook activeAudiobook;
    private long lastProgressNotifyTime = 0;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = (PlaybackService.PlaybackBinder) service;
            isBound = true;
            binder.addListener(LibraryActivity.this);
            if (pendingAudiobookToPlay != null) {
                Audiobook toPlay = pendingAudiobookToPlay;
                pendingAudiobookToPlay = null;
                resumeOrStartBook(toPlay);
            } else {
                updateMiniPlayer();
            }
            updateFabPlayState();
            updateFabVisibility();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
            isBound = false;
            updateFabPlayState();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settingsManager = new SettingsManager(this);
        resumeManager = new ResumeManager(this);
        scanner = new AudiobookScanner(this);
        absClient = new AudiobookshelfClient();

        setContentView(R.layout.activity_library);

        if (savedInstanceState != null) {
            currentViewMode = savedInstanceState.getInt(KEY_CURRENT_VIEW_MODE, VIEW_MODE_LIBRARY);
            try {
                @SuppressWarnings("unchecked")
                ArrayList<Audiobook> savedServer = (ArrayList<Audiobook>) savedInstanceState.getSerializable(KEY_SERVER_AUDIOBOOKS);
                if (savedServer != null) {
                    serverAudiobooks = savedServer;
                }
            } catch (Exception ignored) {}
        }

        initViews();
        setupListeners();
        updateGridConfiguration();
        switchToViewMode(currentViewMode);
        updateFabPlayState();
        updateFabVisibility();
        checkPermissionsAndScan();
    }

    private void initViews() {
        drawerLayout = findViewById(R.id.drawerLayout);
        ivNavDrawer = findViewById(R.id.ivNavDrawer);
        tvHeaderTitle = findViewById(R.id.tvHeaderTitle);
        ivHeaderRefresh = findViewById(R.id.ivHeaderRefresh);
        ivHeaderMenu = findViewById(R.id.ivHeaderMenu);
        lvAudiobooks = findViewById(R.id.lvAudiobooks);
        gvServerAudiobooks = findViewById(R.id.gvServerAudiobooks);
        pbLoading = findViewById(R.id.pbLoading);
        layoutEmptyState = findViewById(R.id.layoutEmptyState);
        tvCurrentFolderEmpty = findViewById(R.id.tvCurrentFolderEmpty);
        btnEmptyChooseFolder = findViewById(R.id.btnEmptyChooseFolder);

        // Server Empty State
        layoutServerEmptyState = findViewById(R.id.layoutServerEmptyState);
        ivServerEmptyIcon = findViewById(R.id.ivServerEmptyIcon);
        tvServerEmptyTitle = findViewById(R.id.tvServerEmptyTitle);
        tvServerEmptyDesc = findViewById(R.id.tvServerEmptyDesc);
        btnServerEmptyAction = findViewById(R.id.btnServerEmptyAction);

        // Drawer Elements
        navItemLibrary = findViewById(R.id.navItemLibrary);
        navItemServer = findViewById(R.id.navItemServer);
        navItemServerSettings = findViewById(R.id.navItemServerSettings);
        ivNavLibraryIcon = findViewById(R.id.ivNavLibraryIcon);
        ivNavServerIcon = findViewById(R.id.ivNavServerIcon);
        tvNavLibraryTitle = findViewById(R.id.tvNavLibraryTitle);
        tvNavServerTitle = findViewById(R.id.tvNavServerTitle);
        tvNavLibraryCount = findViewById(R.id.tvNavLibraryCount);
        tvNavServerBadge = findViewById(R.id.tvNavServerBadge);
        tvDrawerServerStatus = findViewById(R.id.tvDrawerServerStatus);

        updateDrawerServerStatus();
        updateDrawerSelection();

        // Mini player
        layoutMiniPlayer = findViewById(R.id.layoutMiniPlayer);
        ivMiniCover = findViewById(R.id.ivMiniCover);
        tvMiniTitle = findViewById(R.id.tvMiniTitle);
        tvMiniSubtitle = findViewById(R.id.tvMiniSubtitle);
        ivMiniPlayPause = findViewById(R.id.ivMiniPlayPause);
        ivMiniPlayPause.setColorFilter(ContextCompat.getColor(this, R.color.mini_player_control), PorterDuff.Mode.SRC_IN);

        fabPlay = findViewById(R.id.fabPlay);
        if (fabPlay != null) {
            fabPlay.setColorFilter(ContextCompat.getColor(this, R.color.text_on_primary), PorterDuff.Mode.SRC_IN);
        }

        adapter = new AudiobookAdapter(this, new ArrayList<>());
        adapter.setOnAudiobookListener(new AudiobookAdapter.OnAudiobookListener() {
            @Override
            public void onItemClick(Audiobook audiobook) {
                if (currentViewMode == VIEW_MODE_SERVER && !audiobook.isOnDevice()) {
                    Toast.makeText(LibraryActivity.this, R.string.book_not_on_device_toast, Toast.LENGTH_SHORT).show();
                } else {
                    resumeOrStartBook(audiobook);
                }
            }

            @Override
            public void onChapterClick(Audiobook audiobook) {
                if (audiobook.isOnDevice() && audiobook.getFilePath() != null && !audiobook.getFilePath().isEmpty()) {
                    openBookDetail(audiobook.getFilePath());
                } else {
                    Toast.makeText(LibraryActivity.this, R.string.book_not_on_device_toast, Toast.LENGTH_SHORT).show();
                }
            }
        });
        adapter.setLivePositionProvider(path -> {
            if (binder != null && binder.getCurrentAudiobook() != null &&
                    path.equals(binder.getCurrentAudiobook().getFilePath())) {
                return binder.getCurrentPosition();
            }
            return -1L;
        });
        lvAudiobooks.setAdapter(adapter);

        serverGridAdapter = new ServerAudiobookGridAdapter(this, serverAudiobooks);
        serverGridAdapter.setOnAudiobookClickListener(this::showAudiobookInfoDialog);
        if (gvServerAudiobooks != null) {
            gvServerAudiobooks.setAdapter(serverGridAdapter);
        }
    }

    private void setupListeners() {
        if (ivNavDrawer != null) {
            ivNavDrawer.setOnClickListener(v -> {
                if (drawerLayout != null) {
                    if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        drawerLayout.closeDrawer(GravityCompat.START);
                    } else {
                        updateDrawerServerStatus();
                        drawerLayout.openDrawer(GravityCompat.START);
                    }
                }
            });
        }

        if (ivHeaderRefresh != null) {
            ivHeaderRefresh.setOnClickListener(v -> {
                if (currentViewMode == VIEW_MODE_SERVER) {
                    loadServerBooks(true);
                } else {
                    scanAudiobooks();
                }
            });
        }

        if (navItemLibrary != null) {
            navItemLibrary.setOnClickListener(v -> {
                if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.START);
                switchToViewMode(VIEW_MODE_LIBRARY);
            });
        }

        if (navItemServer != null) {
            navItemServer.setOnClickListener(v -> {
                if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.START);
                switchToViewMode(VIEW_MODE_SERVER);
            });
        }

        if (navItemServerSettings != null) {
            navItemServerSettings.setOnClickListener(v -> {
                if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.START);
                showServerConnectDialog();
            });
        }

        if (btnServerEmptyAction != null) {
            btnServerEmptyAction.setOnClickListener(v -> {
                if (!settingsManager.isServerConfigured()) {
                    showServerConnectDialog();
                } else {
                    loadServerBooks(true);
                }
            });
        }
        if (ivHeaderMenu != null) {
            if (hasPhysicalMenuKey()) {
                ivHeaderMenu.setVisibility(View.GONE);
            } else {
                ivHeaderMenu.setVisibility(View.VISIBLE);
                ivHeaderMenu.setOnClickListener(this::showOptionsMenu);
            }
        }

        btnEmptyChooseFolder.setOnClickListener(v -> showFolderPicker());

        View.OnClickListener miniClickListener = v -> {
            if (binder != null && binder.getCurrentAudiobook() != null) {
                openPlayer(binder.getCurrentAudiobook().getFilePath(), false);
            } else if (activeAudiobook != null) {
                openPlayer(activeAudiobook.getFilePath(), false);
            } else if (pendingAudiobookToPlay != null) {
                openPlayer(pendingAudiobookToPlay.getFilePath(), false);
            }
        };
        layoutMiniPlayer.setOnClickListener(miniClickListener);
        ivMiniCover.setOnClickListener(miniClickListener);
        tvMiniTitle.setOnClickListener(miniClickListener);
        tvMiniSubtitle.setOnClickListener(miniClickListener);

        ivMiniPlayPause.setOnClickListener(v -> {
            if (binder != null && binder.getCurrentAudiobook() != null) {
                binder.togglePlayPause();
            } else if (activeAudiobook != null) {
                resumeOrStartBook(activeAudiobook);
            }
        });

        if (fabPlay != null) {
            fabPlay.setOnClickListener(v -> resumeLastPlayedBook());
        }
    }

    private void resumeOrStartBook(Audiobook book) {
        if (book == null) return;
        activeAudiobook = book;

        if (book.getServerItemId() != null && !book.getServerItemId().isEmpty() && resumeManager != null && book.getFilePath() != null) {
            resumeManager.saveServerItemId(book.getFilePath(), book.getServerItemId());
        }

        // Reveal mini player immediately for instant UI feedback
        showMiniPlayerImmediately(book, true);

        if (binder != null) {
            Audiobook current = binder.getCurrentAudiobook();
            if (current != null && book.getFilePath().equals(current.getFilePath())) {
                if (!binder.isPlaying()) {
                    binder.play();
                }
            } else {
                binder.loadAudiobook(book.getFilePath(), true);
            }
            adapter.notifyDataSetChanged();
            updateFabPlayState();
        } else {
            pendingAudiobookToPlay = book;
            Intent serviceIntent = new Intent(this, PlaybackService.class);
            startService(serviceIntent);
            bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);
            updateFabPlayState();
        }
    }

    private void showMiniPlayerImmediately(Audiobook book, boolean isPlaying) {
        layoutMiniPlayer.setVisibility(View.VISIBLE);
        tvMiniTitle.setText(book.getTitle());
        tvMiniSubtitle.setText(book.getAuthor() != null ? book.getAuthor() : "");
        ivMiniPlayPause.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        updateFabPlayState();
        new Thread(() -> {
            Bitmap cover = AudioMetadataHelper.loadCoverArt(book.getFilePath(), 100, 100);
            if (cover != null) {
                runOnUiThread(() -> ivMiniCover.setImageBitmap(cover));
            } else {
                runOnUiThread(() -> ivMiniCover.setImageResource(R.drawable.ic_book_placeholder));
            }
        }).start();
    }

    private void resumeLastPlayedBook() {
        if (binder != null) {
            Audiobook current = binder.getCurrentAudiobook();
            if (current != null) {
                if (binder.isPlaying()) {
                    binder.pause();
                } else {
                    binder.play();
                }
                updateFabPlayState();
                return;
            }
        }

        if (activeAudiobook != null) {
            resumeOrStartBook(activeAudiobook);
            updateFabPlayState();
            return;
        }

        String lastPath = resumeManager != null ? resumeManager.getLastPlayedAudiobookPath() : null;
        Audiobook targetBook = null;
        if (lastPath != null) {
            targetBook = findAudiobookByPath(lastPath);
            if (targetBook == null) {
                File file = new File(lastPath);
                if (file.exists()) {
                    targetBook = AudioMetadataHelper.extractAudiobookInfo(file);
                }
            }
        }

        if (targetBook == null && allAudiobooks != null && !allAudiobooks.isEmpty()) {
            targetBook = allAudiobooks.get(0);
        }

        if (targetBook != null) {
            resumeOrStartBook(targetBook);
        } else {
            Toast.makeText(this, "No audiobooks found to play", Toast.LENGTH_SHORT).show();
        }
        updateFabPlayState();
    }

    private Audiobook findAudiobookByPath(String path) {
        if (path == null || allAudiobooks == null) return null;
        for (Audiobook book : allAudiobooks) {
            if (path.equals(book.getFilePath())) {
                return book;
            }
        }
        return null;
    }

    private void updateFabPlayState() {
        if (fabPlay == null) return;
        boolean isPlaying = (binder != null && binder.isPlaying());
        fabPlay.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private void updateFabVisibility() {
        if (fabPlay == null) return;
        String lastPath = resumeManager != null ? resumeManager.getLastPlayedAudiobookPath() : null;
        boolean hasBooks = (allAudiobooks != null && !allAudiobooks.isEmpty())
                || (lastPath != null && new File(lastPath).exists())
                || (binder != null && binder.getCurrentAudiobook() != null);
        fabPlay.setVisibility(hasBooks ? View.VISIBLE : View.GONE);
    }

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

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (bottomMenuDialog != null && bottomMenuDialog.isShowing()) {
                bottomMenuDialog.dismiss();
            } else {
                showBottomOptionsMenu();
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void showOptionsMenu(View anchor) {
        if (anchor != null && anchor.getId() == R.id.ivHeaderMenu) {
            PopupMenu popup = new PopupMenu(this, anchor);
            popup.getMenuInflater().inflate(R.menu.menu_library, popup.getMenu());
            popup.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == R.id.action_stop_playback) {
                    stopPlaybackAndDismissMiniPlayer();
                    return true;
                } else if (id == R.id.action_settings) {
                    openSettings();
                    return true;
                } else if (id == R.id.action_rescan) {
                    Toast.makeText(this, "Scanning for audiobooks...", Toast.LENGTH_SHORT).show();
                    scanAudiobooks();
                    return true;
                }
                return false;
            });
            popup.show();
        } else {
            showBottomOptionsMenu();
        }
    }

    private void showBottomOptionsMenu() {
        if (isFinishing()) {
            return;
        }
        if (bottomMenuDialog != null && bottomMenuDialog.isShowing()) {
            bottomMenuDialog.dismiss();
            return;
        }

        bottomMenuDialog = new Dialog(this, R.style.Theme_Ember_BottomDialog);
        View menuView = LayoutInflater.from(this).inflate(R.layout.dialog_bottom_menu, null);
        bottomMenuDialog.setContentView(menuView);

        View menuItemStopPlayback = menuView.findViewById(R.id.menuItemStopPlayback);
        View menuItemRescan = menuView.findViewById(R.id.menuItemRescan);
        View menuItemSettings = menuView.findViewById(R.id.menuItemSettings);

        if (menuItemStopPlayback != null) {
            menuItemStopPlayback.setOnClickListener(v -> {
                bottomMenuDialog.dismiss();
                stopPlaybackAndDismissMiniPlayer();
            });
        }

        if (menuItemRescan != null) {
            menuItemRescan.setOnClickListener(v -> {
                bottomMenuDialog.dismiss();
                Toast.makeText(this, "Scanning for audiobooks...", Toast.LENGTH_SHORT).show();
                scanAudiobooks();
            });
        }

        if (menuItemSettings != null) {
            menuItemSettings.setOnClickListener(v -> {
                bottomMenuDialog.dismiss();
                openSettings();
            });
        }

        bottomMenuDialog.setOnKeyListener((dialog, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_MENU && event.getAction() == KeyEvent.ACTION_UP) {
                dialog.dismiss();
                return true;
            }
            return false;
        });

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenWidth = dm.widthPixels;
        int horizontalMargin = (int) (16 * dm.density);
        int menuWidth = Math.min(screenWidth - (horizontalMargin * 2), (int) (320 * dm.density));

        Window window = bottomMenuDialog.getWindow();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            if (window.getDecorView() != null) {
                window.getDecorView().setPadding(0, 0, 0, 0);
            }
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = menuWidth;
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            params.y = 0;
            params.dimAmount = 0.0f;
            window.setAttributes(params);
        }

        bottomMenuDialog.show();

        if (window != null) {
            window.setLayout(menuWidth, WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    @Override
    public void openOptionsMenu() {
        showBottomOptionsMenu();
    }

    private void openSettings() {
        Intent intent = new Intent(LibraryActivity.this, SettingsActivity.class);
        startActivityForResult(intent, REQUEST_SETTINGS);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_library, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_stop_playback) {
            stopPlaybackAndDismissMiniPlayer();
            return true;
        } else if (id == R.id.action_settings) {
            openSettings();
            return true;
        } else if (id == R.id.action_rescan) {
            Toast.makeText(this, "Scanning for audiobooks...", Toast.LENGTH_SHORT).show();
            scanAudiobooks();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showFolderPicker() {
        FolderPickerDialog.show(this, settingsManager.getRootFolder(), folder -> {
            if (folder != null) {
                settingsManager.setRootFolder(folder.getAbsolutePath());
                scanAudiobooks();
            }
        });
    }

    private boolean hasStorageWritePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                    },
                    REQUEST_STORAGE_PERMISSION
            );
        }
    }

    private boolean deleteRecursive(File fileOrDir) {
        if (fileOrDir == null || !fileOrDir.exists()) return false;
        if (fileOrDir.isDirectory()) {
            File[] files = fileOrDir.listFiles();
            if (files != null) {
                for (File child : files) {
                    deleteRecursive(child);
                }
            }
        }
        return fileOrDir.delete();
    }

    private void checkPermissionsAndScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean hasRead = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            boolean hasWrite = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            if (!hasRead || !hasWrite) {
                requestStoragePermissions();
                return;
            }
        }
        scanAudiobooks();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                scanAudiobooks();
            } else {
                Toast.makeText(this, R.string.storage_permission_required, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void scanAudiobooks() {
        pbLoading.setVisibility(View.VISIBLE);
        layoutEmptyState.setVisibility(View.GONE);

        String rootDir = settingsManager.getRootFolder();
        android.util.Log.d("LibraryActivity", "Scanning rootDir: " + rootDir);
        scanner.scanDirectory(rootDir, new AudiobookScanner.ScanCallback() {
            @Override
            public void onProgress(int count) {
                android.util.Log.d("LibraryActivity", "Scan progress: " + count);
            }

            @Override
            public void onScanComplete(List<Audiobook> audiobooks) {
                android.util.Log.d("LibraryActivity", "Scan complete: " + audiobooks.size());
                pbLoading.setVisibility(View.GONE);
                allAudiobooks = audiobooks != null ? audiobooks : new ArrayList<>();
                updateDrawerServerStatus();

                if (currentViewMode == VIEW_MODE_LIBRARY) {
                    adapter.setAudiobooks(allAudiobooks);
                    if (allAudiobooks.isEmpty()) {
                        layoutEmptyState.setVisibility(View.VISIBLE);
                        tvCurrentFolderEmpty.setText(rootDir);
                        if (lvAudiobooks != null) lvAudiobooks.setVisibility(View.GONE);
                    } else {
                        layoutEmptyState.setVisibility(View.GONE);
                        if (lvAudiobooks != null) lvAudiobooks.setVisibility(View.VISIBLE);
                    }
                    updateFabVisibility();
                } else if (currentViewMode == VIEW_MODE_SERVER) {
                    matchServerBooksWithLocal(serverAudiobooks);
                    if (serverGridAdapter != null) {
                        serverGridAdapter.setAudiobooks(serverAudiobooks);
                    }
                }

                if (settingsManager != null && settingsManager.isServerConfigured()) {
                    syncServerCatalogInBackground();
                }
            }
        });
    }

    private void openBookDetail(String filePath) {
        Intent intent = new Intent(this, BookDetailActivity.class);
        intent.putExtra(BookDetailActivity.EXTRA_AUDIOBOOK_PATH, filePath);
        startActivity(intent);
    }

    private void openPlayer(String filePath, boolean startPlaying) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_AUDIOBOOK_PATH, filePath);
        intent.putExtra(PlayerActivity.EXTRA_START_PLAY, startPlaying);
        startActivity(intent);
    }

    private void stopPlaybackAndDismissMiniPlayer() {
        if (binder != null) {
            binder.stopPlayback();
        } else {
            Intent stopIntent = new Intent(this, PlaybackService.class);
            stopIntent.setAction(PlaybackService.ACTION_STOP);
            startService(stopIntent);
        }
        activeAudiobook = null;
        pendingAudiobookToPlay = null;
        layoutMiniPlayer.setVisibility(View.GONE);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateFabPlayState();
        updateFabVisibility();
    }

    private void updateMiniPlayer() {
        updateFabPlayState();
        updateFabVisibility();

        if (binder == null) {
            if (activeAudiobook == null) {
                layoutMiniPlayer.setVisibility(View.GONE);
            }
            return;
        }

        Audiobook book = binder.getCurrentAudiobook();
        if (book == null) {
            activeAudiobook = null;
            layoutMiniPlayer.setVisibility(View.GONE);
            return;
        }
        activeAudiobook = book;

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

        // Load mini cover
        new Thread(() -> {
            Bitmap cover = AudioMetadataHelper.loadCoverArt(book.getFilePath(), 100, 100);
            if (cover != null) {
                runOnUiThread(() -> ivMiniCover.setImageBitmap(cover));
            } else {
                runOnUiThread(() -> ivMiniCover.setImageResource(R.drawable.ic_book_placeholder));
            }
        }).start();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent serviceIntent = new Intent(this, PlaybackService.class);
        startService(serviceIntent);
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isBound) {
            updateMiniPlayer();
        }
        updateFabPlayState();
        updateFabVisibility();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (settingsManager != null && settingsManager.isServerConfigured()) {
            syncServerCatalogInBackground();
        }
    }

    public void syncServerCatalogInBackground() {
        if (settingsManager == null || !settingsManager.isServerConfigured() || absClient == null) {
            return;
        }

        final String serverUrl = settingsManager.getServerUrl();
        final String token = settingsManager.getServerToken();
        String libraryId = settingsManager.getServerLibraryId();

        if (libraryId == null || libraryId.isEmpty()) {
            absClient.getLibraries(serverUrl, token, new AudiobookshelfClient.LibrariesCallback() {
                @Override
                public void onSuccess(List<AudiobookshelfClient.LibraryInfo> libraries) {
                    if (libraries != null && !libraries.isEmpty()) {
                        settingsManager.setServerLibraryId(libraries.get(0).id);
                        settingsManager.setServerLibraryName(libraries.get(0).name);
                        syncServerCatalogInBackground();
                    }
                }

                @Override
                public void onError(String message) {
                    Log.w("LibraryActivity", "Background library fetch failed: " + message);
                }
            });
            return;
        }

        absClient.getLibraryBooks(serverUrl, token, libraryId, new AudiobookshelfClient.BooksCallback() {
            @Override
            public void onSuccess(List<Audiobook> books) {
                if (books != null) {
                    serverAudiobooks = books;
                    matchServerBooksWithLocal(serverAudiobooks);

                    if (tvNavServerBadge != null) {
                        tvNavServerBadge.setText(String.valueOf(serverAudiobooks.size()));
                    }

                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                    if (serverGridAdapter != null) {
                        serverGridAdapter.setAudiobooks(serverAudiobooks);
                    }
                    updateDrawerServerStatus();
                }
            }

            @Override
            public void onError(String message) {
                Log.w("LibraryActivity", "Background server books fetch failed: " + message);
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (bottomMenuDialog != null && bottomMenuDialog.isShowing()) {
            bottomMenuDialog.dismiss();
        }
        if (isBound) {
            if (binder != null) {
                binder.removeListener(this);
            }
            unbindService(connection);
            isBound = false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SETTINGS) {
            scanAudiobooks();
        } else if (requestCode == REQUEST_CODE_SAF_DELETE) {
            if (resultCode == RESULT_OK && data != null) {
                android.net.Uri treeUri = data.getData();
                if (treeUri != null) {
                    final int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    try {
                        getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
                    } catch (Exception ignored) {}
                    settingsManager.setSdCardTreeUri(treeUri.toString());

                    if (pendingDeleteBook != null) {
                        Audiobook targetBook = pendingDeleteBook;
                        Dialog targetDialog = pendingInfoDialog;
                        Runnable targetRunnable = pendingUpdateButtonStates;
                        pendingDeleteBook = null;
                        pendingInfoDialog = null;
                        pendingUpdateButtonStates = null;
                        removeAudiobookFromDevice(targetBook, targetDialog, targetRunnable);
                        return;
                    }
                }
            }
            pendingDeleteBook = null;
            pendingInfoDialog = null;
            pendingUpdateButtonStates = null;
        } else if (requestCode == REQUEST_CODE_SAF_DOWNLOAD) {
            if (resultCode == RESULT_OK && data != null) {
                android.net.Uri treeUri = data.getData();
                if (treeUri != null) {
                    final int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    try {
                        getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
                    } catch (Exception ignored) {}
                    settingsManager.setSdCardTreeUri(treeUri.toString());

                    if (pendingDownloadBook != null) {
                        File dest = pendingDownloadTargetDir != null ? pendingDownloadTargetDir : new File(settingsManager.getRootFolder());
                        executeAudiobookDownload(pendingDownloadBook, dest, pendingDownloadDialog,
                                pendingDownloadProgressBar, pendingDownloadStatusTextView,
                                pendingDownloadProgressLayout, pendingDownloadButton,
                                pendingDownloadUpdateButtonStates);
                    }
                    clearPendingDownload();
                    return;
                }
            }
            clearPendingDownload();
        }
    }

    private void clearPendingDownload() {
        pendingDownloadBook = null;
        pendingDownloadTargetDir = null;
        pendingDownloadDialog = null;
        pendingDownloadProgressBar = null;
        pendingDownloadStatusTextView = null;
        pendingDownloadProgressLayout = null;
        pendingDownloadButton = null;
        pendingDownloadUpdateButtonStates = null;
    }

    // PlaybackListener callbacks
    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        runOnUiThread(() -> {
            updateMiniPlayer();
            updateFabPlayState();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        });
    }

    @Override
    public void onProgressUpdate(long currentPositionMs, long durationMs) {
        long now = System.currentTimeMillis();
        if (now - lastProgressNotifyTime >= 5000) {
            lastProgressNotifyTime = now;
            runOnUiThread(() -> {
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            });
        }
    }

    @Override
    public void onChapterChanged(int chapterIndex, Chapter chapter) {
        runOnUiThread(this::updateMiniPlayer);
    }

    @Override
    public void onAudiobookLoaded(Audiobook audiobook, List<Chapter> chapters) {
        runOnUiThread(() -> {
            updateMiniPlayer();
            updateFabPlayState();
            updateFabVisibility();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        });
    }

    @Override
    public void onError(String message) {
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_CURRENT_VIEW_MODE, currentViewMode);
        if (serverAudiobooks != null && !serverAudiobooks.isEmpty()) {
            ArrayList<Audiobook> toSave = new ArrayList<>(
                    serverAudiobooks.subList(0, Math.min(serverAudiobooks.size(), 200)));
            outState.putSerializable(KEY_SERVER_AUDIOBOOKS, toSave);
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateGridConfiguration();
    }

    private void updateGridConfiguration() {
        if (gvServerAudiobooks == null) return;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float dpWidth = metrics.widthPixels / metrics.density;
        int columns = Math.max(2, (int) (dpWidth / 111));
        gvServerAudiobooks.setNumColumns(columns);
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
            return;
        }
        if (currentViewMode == VIEW_MODE_SERVER) {
            switchToViewMode(VIEW_MODE_LIBRARY);
            return;
        }
        super.onBackPressed();
    }

    private void switchToViewMode(int mode) {
        currentViewMode = mode;
        updateDrawerSelection();

        if (currentViewMode == VIEW_MODE_LIBRARY) {
            if (tvHeaderTitle != null) {
                tvHeaderTitle.setText(R.string.library_title);
            }
            if (ivHeaderRefresh != null) {
                ivHeaderRefresh.setVisibility(View.GONE);
            }
            if (layoutServerEmptyState != null) {
                layoutServerEmptyState.setVisibility(View.GONE);
            }
            if (gvServerAudiobooks != null) {
                gvServerAudiobooks.setVisibility(View.GONE);
            }
            if (adapter != null) {
                adapter.setServerViewMode(false);
                adapter.setAudiobooks(allAudiobooks);
            }
            if (allAudiobooks.isEmpty()) {
                if (layoutEmptyState != null) layoutEmptyState.setVisibility(View.VISIBLE);
                if (lvAudiobooks != null) lvAudiobooks.setVisibility(View.GONE);
            } else {
                if (layoutEmptyState != null) layoutEmptyState.setVisibility(View.GONE);
                if (lvAudiobooks != null) lvAudiobooks.setVisibility(View.VISIBLE);
            }
            updateFabVisibility();
        } else {
            // VIEW_MODE_SERVER
            String libName = settingsManager.getServerLibraryName();
            if (tvHeaderTitle != null) {
                tvHeaderTitle.setText(libName.isEmpty() ? getString(R.string.server_title) : libName);
            }
            if (ivHeaderRefresh != null) {
                ivHeaderRefresh.setVisibility(View.VISIBLE);
            }
            if (layoutEmptyState != null) {
                layoutEmptyState.setVisibility(View.GONE);
            }
            if (lvAudiobooks != null) {
                lvAudiobooks.setVisibility(View.GONE);
            }

            if (!settingsManager.isServerConfigured()) {
                if (gvServerAudiobooks != null) gvServerAudiobooks.setVisibility(View.GONE);
                if (pbLoading != null) pbLoading.setVisibility(View.GONE);
                if (layoutServerEmptyState != null) {
                    layoutServerEmptyState.setVisibility(View.VISIBLE);
                    if (tvServerEmptyTitle != null) tvServerEmptyTitle.setText(R.string.server_not_configured_title);
                    if (tvServerEmptyDesc != null) tvServerEmptyDesc.setText(R.string.server_not_configured_desc);
                    if (btnServerEmptyAction != null) btnServerEmptyAction.setText(R.string.server_connect_button);
                }
                if (fabPlay != null) fabPlay.setVisibility(View.GONE);
            } else {
                if (serverAudiobooks.isEmpty()) {
                    loadServerBooks(false);
                } else {
                    matchServerBooksWithLocal(serverAudiobooks);
                    if (serverGridAdapter != null) {
                        serverGridAdapter.setAudiobooks(serverAudiobooks);
                    }
                    if (gvServerAudiobooks != null) gvServerAudiobooks.setVisibility(View.VISIBLE);
                    if (layoutServerEmptyState != null) layoutServerEmptyState.setVisibility(View.GONE);
                }
            }
        }
    }

    private void loadServerBooks(boolean forceRefresh) {
        if (!settingsManager.isServerConfigured()) {
            switchToViewMode(VIEW_MODE_SERVER);
            return;
        }

        if (pbLoading != null) pbLoading.setVisibility(View.VISIBLE);
        if (lvAudiobooks != null) lvAudiobooks.setVisibility(View.GONE);
        if (gvServerAudiobooks != null) gvServerAudiobooks.setVisibility(View.GONE);
        if (layoutServerEmptyState != null) layoutServerEmptyState.setVisibility(View.GONE);

        final String serverUrl = settingsManager.getServerUrl();
        final String token = settingsManager.getServerToken();
        String libraryId = settingsManager.getServerLibraryId();

        if (libraryId == null || libraryId.isEmpty()) {
            absClient.getLibraries(serverUrl, token, new AudiobookshelfClient.LibrariesCallback() {
                @Override
                public void onSuccess(List<AudiobookshelfClient.LibraryInfo> libraries) {
                    if (libraries != null && !libraries.isEmpty()) {
                        settingsManager.setServerLibraryId(libraries.get(0).id);
                        settingsManager.setServerLibraryName(libraries.get(0).name);
                        loadServerBooks(forceRefresh);
                    } else {
                        if (pbLoading != null) pbLoading.setVisibility(View.GONE);
                        showServerEmpty("No libraries found on server", "Please check your server configuration.");
                    }
                }

                @Override
                public void onError(String message) {
                    if (pbLoading != null) pbLoading.setVisibility(View.GONE);
                    showServerEmpty("Connection Error", message);
                }
            });
            return;
        }

        absClient.getLibraryBooks(serverUrl, token, libraryId, new AudiobookshelfClient.BooksCallback() {
            @Override
            public void onSuccess(List<Audiobook> books) {
                if (pbLoading != null) pbLoading.setVisibility(View.GONE);
                serverAudiobooks = books != null ? books : new ArrayList<>();
                matchServerBooksWithLocal(serverAudiobooks);

                if (tvNavServerBadge != null) {
                    tvNavServerBadge.setText(String.valueOf(serverAudiobooks.size()));
                }

                if (currentViewMode == VIEW_MODE_SERVER) {
                    if (serverAudiobooks.isEmpty()) {
                        showServerEmpty(getString(R.string.no_server_books_found), "The selected library is currently empty.");
                    } else {
                        if (gvServerAudiobooks != null) gvServerAudiobooks.setVisibility(View.VISIBLE);
                        if (layoutServerEmptyState != null) layoutServerEmptyState.setVisibility(View.GONE);
                        if (serverGridAdapter != null) {
                            serverGridAdapter.setAudiobooks(serverAudiobooks);
                        }
                    }
                }
            }

            @Override
            public void onError(String message) {
                if (pbLoading != null) pbLoading.setVisibility(View.GONE);
                if (currentViewMode == VIEW_MODE_SERVER) {
                    showServerEmpty("Error Loading Books", message);
                }
            }
        });
    }

    private void showServerEmpty(String title, String desc) {
        if (lvAudiobooks != null) lvAudiobooks.setVisibility(View.GONE);
        if (gvServerAudiobooks != null) gvServerAudiobooks.setVisibility(View.GONE);
        if (layoutServerEmptyState != null) {
            layoutServerEmptyState.setVisibility(View.VISIBLE);
            if (tvServerEmptyTitle != null) tvServerEmptyTitle.setText(title);
            if (tvServerEmptyDesc != null) tvServerEmptyDesc.setText(desc);
            if (btnServerEmptyAction != null) {
                btnServerEmptyAction.setText(settingsManager.isServerConfigured() ? R.string.refresh : R.string.server_connect_button);
            }
        }
    }

    private void matchServerBooksWithLocal(List<Audiobook> serverBooks) {
        if (serverBooks == null) return;
        for (Audiobook sb : serverBooks) {
            Audiobook matchedLocal = null;

            // Check if existing filePath is valid on disk
            if (sb.getFilePath() != null && !sb.getFilePath().trim().isEmpty()) {
                File f = new File(sb.getFilePath());
                if (f.exists() && f.isFile()) {
                    for (Audiobook lb : allAudiobooks) {
                        if (f.getAbsolutePath().equals(lb.getFilePath())) {
                            matchedLocal = lb;
                            break;
                        }
                    }
                }
            }

            if (matchedLocal == null) {
                for (Audiobook lb : allAudiobooks) {
                    if (isMatchingBook(sb, lb)) {
                        matchedLocal = lb;
                        break;
                    }
                }
            }

            if (matchedLocal != null) {
                sb.setOnDevice(true);
                sb.setFilePath(matchedLocal.getFilePath());
                matchedLocal.setServerItemId(sb.getServerItemId());

                if (resumeManager != null) {
                    resumeManager.saveServerItemId(matchedLocal.getFilePath(), sb.getServerItemId());
                    long serverMs = sb.getServerProgressMs();
                    long localMs = resumeManager.getResumePosition(matchedLocal.getFilePath());

                    if (serverMs > localMs + 2000L) {
                        // Server value is further along: update local time to match server
                        resumeManager.saveResumePosition(matchedLocal.getFilePath(), serverMs);
                        resumeManager.saveLastSyncedPosition(matchedLocal.getFilePath(), serverMs);
                        matchedLocal.setResumePositionMs(serverMs);
                        sb.setResumePositionMs(serverMs);
                    } else if (localMs > serverMs + 2000L && localMs > 0) {
                        // Local value is further along: update server
                        if (settingsManager != null && settingsManager.isServerConfigured() && absClient != null) {
                            absClient.updateItemProgress(settingsManager.getServerUrl(), settingsManager.getServerToken(), sb.getServerItemId(), localMs, matchedLocal.getDurationMs(), null);
                            sb.setServerProgressMs(localMs);
                            resumeManager.saveLastSyncedPosition(matchedLocal.getFilePath(), localMs);
                        }
                    }
                }
            } else {
                sb.setOnDevice(false);
            }
        }
    }

    public static String cleanTitleForMatching(String title) {
        if (title == null) return "";
        // Remove parenthesized or bracketed content: (Unabridged), [Audiobook], etc.
        String clean = title.replaceAll("\\s*[\\[\\(].*?[\\]\\)]", " ");
        // Split on subtitle delimiters: :, -, —, –, ;
        int colonIdx = clean.indexOf(':');
        if (colonIdx > 2) {
            clean = clean.substring(0, colonIdx);
        }
        int dashIdx = clean.indexOf(" - ");
        if (dashIdx > 2) {
            clean = clean.substring(0, dashIdx);
        }
        clean = clean.replace('_', ' ');
        return clean.toLowerCase().replaceAll("[^a-z0-9]", "").trim();
    }

    public static String normalizeText(String text) {
        if (text == null) return "";
        return text.replace('_', ' ').toLowerCase().replaceAll("[^a-z0-9]", "").trim();
    }

    public static boolean isMatchingBook(Audiobook serverBook, Audiobook localBook) {
        if (serverBook == null || localBook == null) return false;

        // 1. Direct path match
        if (serverBook.getFilePath() != null && !serverBook.getFilePath().trim().isEmpty() &&
                serverBook.getFilePath().equals(localBook.getFilePath())) {
            return true;
        }

        String sRawTitle = serverBook.getTitle();
        String lRawTitle = localBook.getTitle();
        String sNorm = normalizeText(sRawTitle);
        String lNorm = normalizeText(lRawTitle);

        if (!sNorm.isEmpty() && !lNorm.isEmpty()) {
            if (sNorm.equals(lNorm)) return true;
            if (sNorm.length() >= 5 && lNorm.contains(sNorm)) return true;
            if (lNorm.length() >= 5 && sNorm.contains(lNorm)) return true;
        }

        // 2. Clean title comparison (strips (unabridged), subtitles, etc.)
        String sClean = cleanTitleForMatching(sRawTitle);
        String lClean = cleanTitleForMatching(lRawTitle);

        if (!sClean.isEmpty() && !lClean.isEmpty()) {
            if (sClean.equals(lClean)) return true;
            if (sClean.length() >= 5 && lClean.contains(sClean)) return true;
            if (lClean.length() >= 5 && sClean.contains(lClean)) return true;
        }

        // 3. Filename and parent directory matching
        String localPath = localBook.getFilePath();
        if (localPath != null && !localPath.isEmpty()) {
            File file = new File(localPath);
            String fileName = file.getName();
            int lastDot = fileName.lastIndexOf('.');
            if (lastDot > 0) fileName = fileName.substring(0, lastDot);

            String fNorm = normalizeText(fileName);
            String fClean = cleanTitleForMatching(fileName);

            if (!sNorm.isEmpty() && !fNorm.isEmpty()) {
                if (sNorm.equals(fNorm)) return true;
                if (fNorm.contains(sNorm) && sNorm.length() >= 5) return true;
                if (sNorm.contains(fNorm) && fNorm.length() >= 5) return true;
            }

            if (!sClean.isEmpty() && !fClean.isEmpty()) {
                if (sClean.equals(fClean)) return true;
                if (fClean.contains(sClean) && sClean.length() >= 5) return true;
                if (sClean.contains(fClean) && fClean.length() >= 5) return true;
            }

            // Check parent directory name if not generic folder
            File parent = file.getParentFile();
            if (parent != null) {
                String parentName = parent.getName();
                if (!parentName.equalsIgnoreCase("audiobooks") &&
                    !parentName.equalsIgnoreCase("download") &&
                    !parentName.equalsIgnoreCase("downloads")) {
                    String pNorm = normalizeText(parentName);
                    String pClean = cleanTitleForMatching(parentName);
                    if (!sNorm.isEmpty() && !pNorm.isEmpty()) {
                        if (sNorm.equals(pNorm)) return true;
                        if (pNorm.contains(sNorm) && sNorm.length() >= 5) return true;
                        if (sNorm.contains(pNorm) && pNorm.length() >= 5) return true;
                    }
                    if (!sClean.isEmpty() && !pClean.isEmpty()) {
                        if (sClean.equals(pClean)) return true;
                        if (pClean.contains(sClean) && sClean.length() >= 5) return true;
                        if (sClean.contains(pClean) && pClean.length() >= 5) return true;
                    }
                }
            }
        }

        return false;
    }

    private void updateDrawerSelection() {
        if (navItemLibrary == null || navItemServer == null) return;
        if (currentViewMode == VIEW_MODE_LIBRARY) {
            navItemLibrary.setBackgroundResource(R.drawable.bg_nav_item_selected);
            navItemServer.setBackgroundResource(R.drawable.bg_card_clickable);
            if (ivNavLibraryIcon != null) ivNavLibraryIcon.setColorFilter(ContextCompat.getColor(this, R.color.accent));
            if (ivNavServerIcon != null) ivNavServerIcon.setColorFilter(ContextCompat.getColor(this, R.color.control_normal));
        } else {
            navItemLibrary.setBackgroundResource(R.drawable.bg_card_clickable);
            navItemServer.setBackgroundResource(R.drawable.bg_nav_item_selected);
            if (ivNavLibraryIcon != null) ivNavLibraryIcon.setColorFilter(ContextCompat.getColor(this, R.color.control_normal));
            if (ivNavServerIcon != null) ivNavServerIcon.setColorFilter(ContextCompat.getColor(this, R.color.accent));
        }
    }

    private void updateDrawerServerStatus() {
        if (tvDrawerServerStatus == null) return;
        if (settingsManager.isServerConfigured()) {
            String host = settingsManager.getServerUrl().replaceFirst("^https?://", "");
            tvDrawerServerStatus.setText(getString(R.string.nav_server_connected, host));
        } else {
            tvDrawerServerStatus.setText(R.string.nav_server_disconnected);
        }

        if (tvNavLibraryCount != null) {
            tvNavLibraryCount.setText(String.valueOf(allAudiobooks.size()));
        }
        if (tvNavServerBadge != null) {
            if (serverAudiobooks.isEmpty()) {
                tvNavServerBadge.setText(settingsManager.isServerConfigured() ? "Connected" : "");
            } else {
                tvNavServerBadge.setText(String.valueOf(serverAudiobooks.size()));
            }
        }
    }

    private void showServerConnectDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_server_connect);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        }

        EditText etUrl = dialog.findViewById(R.id.etServerUrl);
        EditText etUsername = dialog.findViewById(R.id.etServerUsername);
        EditText etPassword = dialog.findViewById(R.id.etServerPassword);
        TextView tvStatusMsg = dialog.findViewById(R.id.tvServerStatusMsg);
        ProgressBar pbConnecting = dialog.findViewById(R.id.pbServerConnecting);
        Button btnDisconnect = dialog.findViewById(R.id.btnServerDisconnect);
        Button btnCancel = dialog.findViewById(R.id.btnServerCancel);
        Button btnConnect = dialog.findViewById(R.id.btnServerConnect);

        etUrl.setText(settingsManager.getServerUrl());
        etUsername.setText(settingsManager.getServerUsername());

        if (settingsManager.isServerConfigured()) {
            btnDisconnect.setVisibility(View.VISIBLE);
            btnDisconnect.setOnClickListener(v -> {
                settingsManager.clearServerCredentials();
                serverAudiobooks.clear();
                updateDrawerServerStatus();
                dialog.dismiss();
                Toast.makeText(this, "Disconnected from server", Toast.LENGTH_SHORT).show();
                if (currentViewMode == VIEW_MODE_SERVER) {
                    switchToViewMode(VIEW_MODE_SERVER);
                }
            });
        }

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnConnect.setOnClickListener(v -> {
            String rawUrl = etUrl.getText().toString().trim();
            String username = etUsername.getText().toString().trim();
            String password = etPassword.getText().toString();

            if (rawUrl.isEmpty()) {
                tvStatusMsg.setText("Server URL is required");
                tvStatusMsg.setVisibility(View.VISIBLE);
                return;
            }
            if (username.isEmpty()) {
                tvStatusMsg.setText("Username is required");
                tvStatusMsg.setVisibility(View.VISIBLE);
                return;
            }

            final String url = AudiobookshelfClient.normalizeUrl(rawUrl);

            tvStatusMsg.setVisibility(View.GONE);
            pbConnecting.setVisibility(View.VISIBLE);
            btnConnect.setEnabled(false);

            absClient.login(url, username, password, new AudiobookshelfClient.AuthCallback() {
                @Override
                public void onSuccess(String token, String returnedUser, List<AudiobookshelfClient.LibraryInfo> libraries) {
                    pbConnecting.setVisibility(View.GONE);
                    btnConnect.setEnabled(true);
                    settingsManager.setServerUrl(url);
                    settingsManager.setServerUsername(returnedUser);
                    settingsManager.setServerToken(token);

                    if (libraries != null && !libraries.isEmpty()) {
                        settingsManager.setServerLibraryId(libraries.get(0).id);
                        settingsManager.setServerLibraryName(libraries.get(0).name);
                    }

                    updateDrawerServerStatus();
                    try {
                        if (dialog.isShowing() && !isFinishing()) {
                            dialog.dismiss();
                        }
                    } catch (Throwable ignored) {
                    }
                    Toast.makeText(LibraryActivity.this, "Connected successfully!", Toast.LENGTH_SHORT).show();

                    if (currentViewMode == VIEW_MODE_SERVER) {
                        loadServerBooks(true);
                    }
                }

                @Override
                public void onError(String message) {
                    pbConnecting.setVisibility(View.GONE);
                    btnConnect.setEnabled(true);
                    tvStatusMsg.setText(message);
                    tvStatusMsg.setVisibility(View.VISIBLE);
                }
            });
        });

        dialog.show();
    }

    private void showAudiobookInfoDialog(Audiobook book) {
        if (book == null || isFinishing()) return;

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_audiobook_info);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        }

        ImageView ivCover = dialog.findViewById(R.id.ivInfoCover);
        TextView tvTitle = dialog.findViewById(R.id.tvInfoTitle);
        TextView tvAuthor = dialog.findViewById(R.id.tvInfoAuthor);
        TextView tvPubDate = dialog.findViewById(R.id.tvInfoPubDate);
        TextView tvStatus = dialog.findViewById(R.id.tvInfoStatus);

        // Ensure on-device status and file path are fresh and accurate
        boolean onDeviceFound = false;
        if (book.getFilePath() != null && !book.getFilePath().trim().isEmpty() && new File(book.getFilePath()).exists()) {
            onDeviceFound = true;
            book.setOnDevice(true);
        } else {
            for (Audiobook lb : allAudiobooks) {
                if (isMatchingBook(book, lb)) {
                    book.setOnDevice(true);
                    book.setFilePath(lb.getFilePath());
                    onDeviceFound = true;
                    break;
                }
            }
        }
        if (!onDeviceFound && book.isServerItem()) {
            book.setOnDevice(false);
            book.setFilePath("");
        }

        View layoutProgress = dialog.findViewById(R.id.layoutInfoDownloadProgress);
        ProgressBar pbDownload = dialog.findViewById(R.id.pbInfoDownload);
        TextView tvDownloadStatus = dialog.findViewById(R.id.tvInfoDownloadStatus);

        Button btnPlay = dialog.findViewById(R.id.btnInfoPlay);
        Button btnRemove = dialog.findViewById(R.id.btnInfoRemove);
        Button btnDownload = dialog.findViewById(R.id.btnInfoDownload);
        Button btnClose = dialog.findViewById(R.id.btnInfoClose);

        tvTitle.setText(book.getTitle());
        tvAuthor.setText(book.getAuthor());

        String pubDate = book.getPublishedDate();
        if (pubDate == null || pubDate.isEmpty() || pubDate.equalsIgnoreCase("Unknown")) {
            tvPubDate.setText(book.isServerItem() ? "Publication Year: Fetching..." : "Publication Year: Not specified");
        } else {
            tvPubDate.setText("Publication Year: " + pubDate);
        }

        // Fetch detailed publication year from server
        if (book.isServerItem() && settingsManager.isServerConfigured()) {
            absClient.fetchItemDetails(settingsManager.getServerUrl(), settingsManager.getServerToken(), book.getServerItemId(), new AudiobookshelfClient.ItemDetailsCallback() {
                @Override
                public void onSuccess(JSONObject itemDetails, String publishedYear) {
                    if (publishedYear != null && !publishedYear.isEmpty()) {
                        book.setPublishedDate(publishedYear);
                        if (dialog.isShowing()) {
                            tvPubDate.setText("Publication Year: " + publishedYear);
                        }
                    } else if (dialog.isShowing() && (book.getPublishedDate() == null || book.getPublishedDate().isEmpty())) {
                        tvPubDate.setText("Publication Year: Not specified");
                    }
                }

                @Override
                public void onError(String message) {
                    if (dialog.isShowing() && (book.getPublishedDate() == null || book.getPublishedDate().isEmpty())) {
                        tvPubDate.setText("Publication Year: Not specified");
                    }
                }
            });
        }

        // Cover art loading
        ivCover.setImageResource(R.drawable.ic_book_placeholder);
        if (book.getCoverUrl() != null && !book.getCoverUrl().isEmpty()) {
            Bitmap cached = ImageCache.getInstance().get(book.getCoverUrl());
            if (cached != null) {
                ivCover.setImageBitmap(cached);
            }
        }

        Runnable updateButtonStates = () -> {
            if (book.isOnDevice()) {
                tvStatus.setText(R.string.status_on_device);
                tvStatus.setTextColor(Color.parseColor("#2E7D32"));
                tvStatus.setBackgroundResource(R.drawable.bg_status_badge_on_device);
                btnPlay.setVisibility(View.VISIBLE);
                btnRemove.setVisibility(View.VISIBLE);
                btnDownload.setVisibility(View.GONE);
                layoutProgress.setVisibility(View.GONE);
            } else {
                tvStatus.setText(R.string.status_not_on_device);
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                tvStatus.setBackgroundResource(R.drawable.bg_status_badge_server);
                btnPlay.setVisibility(View.GONE);
                btnRemove.setVisibility(View.GONE);
                btnDownload.setVisibility(View.VISIBLE);
            }
        };

        updateButtonStates.run();

        btnPlay.setOnClickListener(v -> {
            dialog.dismiss();
            resumeOrStartBook(book);
        });

        btnRemove.setOnClickListener(v -> {
            if (!hasStorageWritePermission()) {
                requestStoragePermissions();
                Toast.makeText(this, "Storage write permission is required to delete files", Toast.LENGTH_SHORT).show();
                return;
            }

            removeAudiobookFromDevice(book, dialog, updateButtonStates);
        });

        btnDownload.setOnClickListener(v -> {
            if (!hasStorageWritePermission()) {
                requestStoragePermissions();
                Toast.makeText(this, "Storage write permission is required to download files", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!settingsManager.isServerConfigured()) {
                Toast.makeText(this, "Server not configured", Toast.LENGTH_SHORT).show();
                return;
            }

            File targetDir = new File(settingsManager.getRootFolder());
            boolean isRemovable = StorageFileHelper.isOnRemovableStorage(targetDir);
            boolean isActuallyWritable = StorageFileHelper.isDirectoryActuallyWritable(targetDir);

            if (isActuallyWritable) {
                executeAudiobookDownload(book, targetDir, dialog, pbDownload, tvDownloadStatus, layoutProgress, btnDownload, updateButtonStates);
            } else if (isRemovable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                String treeUri = settingsManager.getSdCardTreeUri();
                if (treeUri != null && !treeUri.isEmpty()) {
                    executeAudiobookDownload(book, targetDir, dialog, pbDownload, tvDownloadStatus, layoutProgress, btnDownload, updateButtonStates);
                } else {
                    pendingDownloadBook = book;
                    pendingDownloadTargetDir = targetDir;
                    pendingDownloadDialog = dialog;
                    pendingDownloadProgressBar = pbDownload;
                    pendingDownloadStatusTextView = tvDownloadStatus;
                    pendingDownloadProgressLayout = layoutProgress;
                    pendingDownloadButton = btnDownload;
                    pendingDownloadUpdateButtonStates = updateButtonStates;

                    new AlertDialog.Builder(this)
                            .setTitle("SD Card Permission Required")
                            .setMessage("To download audiobooks directly to your external SD card (" + targetDir.getAbsolutePath() + "), Android requires you to grant SD card access.\n\nTap 'Select SD Card' to choose your SD card, or 'Internal Storage' to download without permissions.")
                            .setPositiveButton("Select SD Card", (d, which) -> {
                                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                                startActivityForResult(intent, REQUEST_CODE_SAF_DOWNLOAD);
                            })
                            .setNeutralButton("Internal Storage", (d, which) -> {
                                File internalDir = new File(Environment.getExternalStorageDirectory(), "Audiobooks");
                                if (!internalDir.exists()) internalDir.mkdirs();
                                executeAudiobookDownload(book, internalDir, dialog, pbDownload, tvDownloadStatus, layoutProgress, btnDownload, updateButtonStates);
                            })
                            .setNegativeButton(android.R.string.cancel, (d, which) -> clearPendingDownload())
                            .show();
                }
            } else {
                File fallbackDir = new File(Environment.getExternalStorageDirectory(), "Audiobooks");
                if (!fallbackDir.exists()) fallbackDir.mkdirs();
                executeAudiobookDownload(book, fallbackDir, dialog, pbDownload, tvDownloadStatus, layoutProgress, btnDownload, updateButtonStates);
            }
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void executeAudiobookDownload(Audiobook book, File targetDir, Dialog dialog, ProgressBar pbDownload, TextView tvDownloadStatus, View layoutProgress, Button btnDownload, Runnable updateButtonStates) {
        btnDownload.setEnabled(false);
        layoutProgress.setVisibility(View.VISIBLE);
        pbDownload.setProgress(0);
        tvDownloadStatus.setText("Starting download...");

        String treeUriStr = settingsManager.getSdCardTreeUri();
        absClient.downloadAudiobook(this, settingsManager.getServerUrl(), settingsManager.getServerToken(), book, targetDir, treeUriStr, new AudiobookshelfClient.DownloadCallback() {
            @Override
            public void onProgress(int percent, long bytesRead, long totalBytes) {
                pbDownload.setProgress(percent);
                tvDownloadStatus.setText("Downloading... " + percent + "%");
            }

            @Override
            public void onSuccess(File downloadedFile) {
                btnDownload.setEnabled(true);
                layoutProgress.setVisibility(View.GONE);
                book.setOnDevice(true);
                book.setFilePath(downloadedFile.getAbsolutePath());

                // Scan file metadata and add to local audiobooks
                Audiobook localBook = null;
                try {
                    localBook = AudioMetadataHelper.extractAudiobookInfo(downloadedFile);
                } catch (Exception ignored) {
                }
                if (localBook == null) {
                    localBook = new Audiobook(downloadedFile.getAbsolutePath(), book.getTitle(), book.getAuthor(), book.getDurationMs(), downloadedFile.length());
                }

                localBook.setServerItemId(book.getServerItemId());
                if (resumeManager != null) {
                    resumeManager.saveServerItemId(downloadedFile.getAbsolutePath(), book.getServerItemId());
                    if (book.getServerProgressMs() > 0) {
                        resumeManager.saveResumePosition(downloadedFile.getAbsolutePath(), book.getServerProgressMs());
                        resumeManager.saveLastSyncedPosition(downloadedFile.getAbsolutePath(), book.getServerProgressMs());
                        localBook.setResumePositionMs(book.getServerProgressMs());
                    }
                }

                // Check if already in allAudiobooks to avoid duplicates
                boolean exists = false;
                for (int i = 0; i < allAudiobooks.size(); i++) {
                    if (downloadedFile.getAbsolutePath().equals(allAudiobooks.get(i).getFilePath())) {
                        allAudiobooks.set(i, localBook);
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    allAudiobooks.add(localBook);
                }

                // Update serverAudiobooks and adapter
                for (Audiobook sb : serverAudiobooks) {
                    if ((sb.getServerItemId() != null && sb.getServerItemId().equals(book.getServerItemId()))
                            || isMatchingBook(sb, localBook)) {
                        sb.setOnDevice(true);
                        sb.setFilePath(downloadedFile.getAbsolutePath());
                    }
                }
                matchServerBooksWithLocal(serverAudiobooks);

                if (adapter != null) {
                    adapter.setAudiobooks(allAudiobooks);
                }
                if (serverGridAdapter != null) {
                    serverGridAdapter.setAudiobooks(serverAudiobooks);
                }
                updateDrawerServerStatus();
                if (updateButtonStates != null) updateButtonStates.run();
                Toast.makeText(LibraryActivity.this, "Download complete: " + book.getTitle(), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                btnDownload.setEnabled(true);
                layoutProgress.setVisibility(View.GONE);
                Toast.makeText(LibraryActivity.this, "Download failed: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void removeAudiobookFromDevice(Audiobook book, Dialog dialog, Runnable updateButtonStates) {
        if (book == null) return;
        String filePath = book.getFilePath();
        if (filePath == null || filePath.trim().isEmpty()) {
            for (Audiobook b : allAudiobooks) {
                if (isMatchingBook(book, b)) {
                    filePath = b.getFilePath();
                    book.setFilePath(filePath);
                    break;
                }
            }
        }

        if (filePath == null || filePath.trim().isEmpty()) {
            Toast.makeText(this, "No local file associated with this book", Toast.LENGTH_SHORT).show();
            return;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            onFileSuccessfullyRemoved(book, dialog, updateButtonStates);
            Toast.makeText(this, "Removed from device", Toast.LENGTH_SHORT).show();
            return;
        }

        String treeUriStr = settingsManager.getSdCardTreeUri();
        boolean deleted = StorageFileHelper.deleteAudiobookFile(this, file, treeUriStr);

        if (deleted) {
            onFileSuccessfullyRemoved(book, dialog, updateButtonStates);
            Toast.makeText(this, "Removed from device", Toast.LENGTH_SHORT).show();
        } else {
            if (StorageFileHelper.isOnRemovableStorage(file) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                pendingDeleteBook = book;
                pendingInfoDialog = dialog;
                pendingUpdateButtonStates = updateButtonStates;
                showSafPermissionPrompt();
            } else {
                Toast.makeText(this, "Could not delete file from device (read-only storage)", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showSafPermissionPrompt() {
        new AlertDialog.Builder(this)
                .setTitle("SD Card Permission Required")
                .setMessage("Android requires permission to modify files on your external SD card. In the next screen, please select your SD card or Audiobooks folder to grant permission.")
                .setPositiveButton("Select SD Card", (d, which) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        try {
                            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                            startActivityForResult(intent, REQUEST_CODE_SAF_DELETE);
                        } catch (Exception e) {
                            Toast.makeText(this, "Cannot open storage picker: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, (d, which) -> {
                    pendingDeleteBook = null;
                    pendingInfoDialog = null;
                    pendingUpdateButtonStates = null;
                })
                .show();
    }

    private void onFileSuccessfullyRemoved(Audiobook book, Dialog dialog, Runnable updateButtonStates) {
        String deletedPath = book.getFilePath();
        book.setOnDevice(false);
        book.setFilePath("");

        for (int i = 0; i < allAudiobooks.size(); i++) {
            Audiobook a = allAudiobooks.get(i);
            if ((deletedPath != null && !deletedPath.isEmpty() && deletedPath.equals(a.getFilePath()))
                    || isMatchingBook(book, a)) {
                allAudiobooks.remove(i);
                break;
            }
        }

        matchServerBooksWithLocal(serverAudiobooks);
        if (adapter != null) adapter.setAudiobooks(allAudiobooks);
        if (serverGridAdapter != null) serverGridAdapter.setAudiobooks(serverAudiobooks);
        updateDrawerServerStatus();
        if (updateButtonStates != null) updateButtonStates.run();
    }
}
