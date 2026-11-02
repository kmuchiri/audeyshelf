package com.audeyshelf.audiobook.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.audeyshelf.audiobook.R;
import com.audeyshelf.audiobook.data.SettingsManager;

import java.io.File;

/**
 * SettingsActivity matching reference screenshot 3a5fb321.png.
 * Provides Audiobook Folder Picker, Sleep Timer selector (5/15/30/45/60 min), and Light Mode toggle.
 */
public class SettingsActivity extends AppCompatActivity {
    public static final String EXTRA_FOLDER_CHANGED = "folder_changed";

    private SettingsManager settingsManager;
    private TextView tvFolderPath;
    private Button btnChangeFolder;
    private Button btnSave;
    private CheckBox cbLightMode;

    private CheckBox cbButtonControl;
    private View layoutButtonOptions;
    private View layoutSingleTap;
    private View layoutDoubleTap;
    private View layoutTripleTap;
    private TextView tvSingleTapAction;
    private TextView tvDoubleTapAction;
    private TextView tvTripleTapAction;

    private int selectedSleepMinutes = 5;
    private int selectedSingleTapAction;
    private int selectedDoubleTapAction;
    private int selectedTripleTapAction;
    private boolean folderChanged = false;

    // Timer views
    private View layout5, layout15, layout30, layout45, layout60;
    private View ind5, ind15, ind30, ind45, ind60;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settingsManager = new SettingsManager(this);
        setContentView(R.layout.activity_settings);

        ImageView ivBack = findViewById(R.id.ivSettingsBack);
        ivBack.setOnClickListener(v -> finishWithResult());

        tvFolderPath = findViewById(R.id.tvSettingsFolderPath);
        btnChangeFolder = findViewById(R.id.btnSettingsChangeFolder);
        btnSave = findViewById(R.id.btnSettingsSave);
        cbLightMode = findViewById(R.id.cbLightMode);

        cbButtonControl = findViewById(R.id.cbButtonControl);
        layoutButtonOptions = findViewById(R.id.layoutButtonOptions);
        layoutSingleTap = findViewById(R.id.layoutSingleTap);
        layoutDoubleTap = findViewById(R.id.layoutDoubleTap);
        layoutTripleTap = findViewById(R.id.layoutTripleTap);
        tvSingleTapAction = findViewById(R.id.tvSingleTapAction);
        tvDoubleTapAction = findViewById(R.id.tvDoubleTapAction);
        tvTripleTapAction = findViewById(R.id.tvTripleTapAction);

        layout5 = findViewById(R.id.layoutTimer5);
        layout15 = findViewById(R.id.layoutTimer15);
        layout30 = findViewById(R.id.layoutTimer30);
        layout45 = findViewById(R.id.layoutTimer45);
        layout60 = findViewById(R.id.layoutTimer60);

        ind5 = findViewById(R.id.indicatorTimer5);
        ind15 = findViewById(R.id.indicatorTimer15);
        ind30 = findViewById(R.id.indicatorTimer30);
        ind45 = findViewById(R.id.indicatorTimer45);
        ind60 = findViewById(R.id.indicatorTimer60);

        updateFolderDisplay();
        setupSleepTimer();
        setupLightMode();
        setupButtonControls();

        btnChangeFolder.setOnClickListener(v -> showFolderPicker());

        btnSave.setOnClickListener(v -> {
            settingsManager.setSleepTimerMinutes(selectedSleepMinutes);
            settingsManager.setButtonControlEnabled(cbButtonControl.isChecked());
            settingsManager.setButtonSingleTapAction(selectedSingleTapAction);
            settingsManager.setButtonDoubleTapAction(selectedDoubleTapAction);
            settingsManager.setButtonTripleTapAction(selectedTripleTapAction);
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
            finishWithResult();
        });
    }

    private void updateFolderDisplay() {
        String currentFolder = settingsManager.getRootFolder();
        tvFolderPath.setText(currentFolder);
    }

    private void showFolderPicker() {
        FolderPickerDialog.show(this, settingsManager.getRootFolder(), folder -> {
            if (folder != null) {
                settingsManager.setRootFolder(folder.getAbsolutePath());
                updateFolderDisplay();
                folderChanged = true;
                Toast.makeText(SettingsActivity.this,
                        getString(R.string.folder_selected, folder.getName()),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupSleepTimer() {
        selectedSleepMinutes = settingsManager.getSleepTimerMinutes();
        updateTimerIndicators(selectedSleepMinutes);

        layout5.setOnClickListener(v -> {
            selectedSleepMinutes = 5;
            updateTimerIndicators(5);
        });

        layout15.setOnClickListener(v -> {
            selectedSleepMinutes = 15;
            updateTimerIndicators(15);
        });

        layout30.setOnClickListener(v -> {
            selectedSleepMinutes = 30;
            updateTimerIndicators(30);
        });

        layout45.setOnClickListener(v -> {
            selectedSleepMinutes = 45;
            updateTimerIndicators(45);
        });

        layout60.setOnClickListener(v -> {
            selectedSleepMinutes = 60;
            updateTimerIndicators(60);
        });
    }

    private void updateTimerIndicators(int minutes) {
        ind5.setVisibility(minutes == 5 ? View.VISIBLE : View.INVISIBLE);
        ind15.setVisibility(minutes == 15 ? View.VISIBLE : View.INVISIBLE);
        ind30.setVisibility(minutes == 30 ? View.VISIBLE : View.INVISIBLE);
        ind45.setVisibility(minutes == 45 ? View.VISIBLE : View.INVISIBLE);
        ind60.setVisibility(minutes == 60 ? View.VISIBLE : View.INVISIBLE);
    }

    private void setupLightMode() {
        cbLightMode.setChecked(settingsManager.isLightMode());
        cbLightMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            settingsManager.setLightMode(isChecked);
        });
    }

    private void setupButtonControls() {
        boolean enabled = settingsManager.isButtonControlEnabled();
        cbButtonControl.setChecked(enabled);
        updateButtonOptionsState(enabled);

        cbButtonControl.setOnCheckedChangeListener((buttonView, isChecked) -> {
            settingsManager.setButtonControlEnabled(isChecked);
            updateButtonOptionsState(isChecked);
        });

        selectedSingleTapAction = settingsManager.getButtonSingleTapAction();
        selectedDoubleTapAction = settingsManager.getButtonDoubleTapAction();
        selectedTripleTapAction = settingsManager.getButtonTripleTapAction();

        updateButtonActionLabels();

        layoutSingleTap.setOnClickListener(v -> showActionDialog(
                getString(R.string.pref_single_tap_title),
                selectedSingleTapAction,
                action -> {
                    selectedSingleTapAction = action;
                    settingsManager.setButtonSingleTapAction(action);
                    updateButtonActionLabels();
                }
        ));

        layoutDoubleTap.setOnClickListener(v -> showActionDialog(
                getString(R.string.pref_double_tap_title),
                selectedDoubleTapAction,
                action -> {
                    selectedDoubleTapAction = action;
                    settingsManager.setButtonDoubleTapAction(action);
                    updateButtonActionLabels();
                }
        ));

        layoutTripleTap.setOnClickListener(v -> showActionDialog(
                getString(R.string.pref_triple_tap_title),
                selectedTripleTapAction,
                action -> {
                    selectedTripleTapAction = action;
                    settingsManager.setButtonTripleTapAction(action);
                    updateButtonActionLabels();
                }
        ));
    }

    private void updateButtonOptionsState(boolean enabled) {
        layoutButtonOptions.setEnabled(enabled);
        layoutButtonOptions.setAlpha(enabled ? 1.0f : 0.5f);
        layoutSingleTap.setEnabled(enabled);
        layoutDoubleTap.setEnabled(enabled);
        layoutTripleTap.setEnabled(enabled);
    }

    private void updateButtonActionLabels() {
        tvSingleTapAction.setText(SettingsManager.getActionTitle(this, selectedSingleTapAction));
        tvDoubleTapAction.setText(SettingsManager.getActionTitle(this, selectedDoubleTapAction));
        tvTripleTapAction.setText(SettingsManager.getActionTitle(this, selectedTripleTapAction));
    }

    private interface OnActionSelectedListener {
        void onActionSelected(int action);
    }

    private void showActionDialog(String title, int currentAction, OnActionSelectedListener listener) {
        final int[] actionValues = new int[]{
                SettingsManager.ACTION_FORWARD_10S,
                SettingsManager.ACTION_REWIND_10S,
                SettingsManager.ACTION_NEXT_CHAPTER,
                SettingsManager.ACTION_PREV_CHAPTER,
                SettingsManager.ACTION_PLAY_PAUSE,
                SettingsManager.ACTION_PAUSE_ONLY,
                SettingsManager.ACTION_NONE
        };

        String[] actionLabels = new String[actionValues.length];
        int selectedIndex = 0;
        for (int i = 0; i < actionValues.length; i++) {
            actionLabels[i] = SettingsManager.getActionTitle(this, actionValues[i]);
            if (actionValues[i] == currentAction) {
                selectedIndex = i;
            }
        }

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(actionLabels, selectedIndex, (dialog, which) -> {
                    listener.onActionSelected(actionValues[which]);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void finishWithResult() {
        Intent data = new Intent();
        data.putExtra(EXTRA_FOLDER_CHANGED, folderChanged);
        setResult(RESULT_OK, data);
        finish();
    }

    @Override
    public void onBackPressed() {
        finishWithResult();
    }
}
