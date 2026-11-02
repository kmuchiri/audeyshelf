package com.audeyshelf.audiobook.ui;

import android.app.Dialog;
import android.content.Context;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.audeyshelf.audiobook.R;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Clean, lightweight, legacy-compatible directory picker dialog.
 * Allows navigating directories and picking an audiobook root folder on any Android version (API 16+).
 */
public class FolderPickerDialog {

    public interface OnFolderSelectedListener {
        void onFolderSelected(File folder);
    }

    public static void show(Context context, String initialPath, final OnFolderSelectedListener listener) {
        File startDir = null;
        if (initialPath != null) {
            File f = new File(initialPath);
            if (f.isDirectory() && f.canRead()) {
                startDir = f;
            }
        }
        if (startDir == null) {
            startDir = Environment.getExternalStorageDirectory();
            if (startDir == null || !startDir.canRead()) {
                startDir = new File("/sdcard");
            }
        }

        final File[] currentDir = new File[]{startDir};

        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_folder_picker, null);
        final TextView tvCurrentPath = dialogView.findViewById(R.id.tvPickerCurrentPath);
        final ListView lvFolders = dialogView.findViewById(R.id.lvPickerFolders);
        final Button btnSelect = dialogView.findViewById(R.id.btnPickerSelectCurrent);
        final Button btnCancel = dialogView.findViewById(R.id.btnPickerCancel);

        AlertDialog.Builder builder = new AlertDialog.Builder(context, R.style.Theme_Ember_Dialog);
        builder.setView(dialogView);
        final Dialog dialog = builder.create();

        final FolderItemAdapter adapter = new FolderItemAdapter(context, new ArrayList<>());
        lvFolders.setAdapter(adapter);

        Runnable refreshFolderList = new Runnable() {
            @Override
            public void run() {
                File dir = currentDir[0];
                if (dir == null || !dir.exists()) {
                    dir = Environment.getExternalStorageDirectory();
                    currentDir[0] = dir;
                }

                tvCurrentPath.setText(dir.getAbsolutePath());

                List<FolderItem> items = new ArrayList<>();

                // Add parent if not root
                File parent = dir.getParentFile();
                if (parent != null && parent.canRead()) {
                    items.add(new FolderItem(".. (" + context.getString(R.string.parent_folder) + ")", parent, true));
                }

                // Collect subdirectories
                File[] subDirs = dir.listFiles(new FileFilter() {
                    @Override
                    public boolean accept(File pathname) {
                        return pathname.isDirectory() && !pathname.getName().startsWith(".");
                    }
                });

                if (subDirs != null && subDirs.length > 0) {
                    List<File> sorted = Arrays.asList(subDirs);
                    Collections.sort(sorted, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                    for (File f : sorted) {
                        items.add(new FolderItem(f.getName(), f, false));
                    }
                }

                adapter.clear();
                adapter.addAll(items);
                adapter.notifyDataSetChanged();
                lvFolders.setSelection(0);
            }
        };

        lvFolders.setOnItemClickListener((parent, view, position, id) -> {
            FolderItem item = adapter.getItem(position);
            if (item != null && item.file.isDirectory()) {
                if (item.file.canRead()) {
                    currentDir[0] = item.file;
                    refreshFolderList.run();
                } else {
                    Toast.makeText(context, "Cannot access folder: Permission denied", Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnSelect.setOnClickListener(v -> {
            File selected = currentDir[0];
            if (selected != null && selected.isDirectory()) {
                if (listener != null) {
                    listener.onFolderSelected(selected);
                }
                dialog.dismiss();
            }
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        refreshFolderList.run();
        dialog.show();
    }

    private static class FolderItem {
        final String displayName;
        final File file;
        final boolean isParent;

        FolderItem(String displayName, File file, boolean isParent) {
            this.displayName = displayName;
            this.file = file;
            this.isParent = isParent;
        }
    }

    private static class FolderItemAdapter extends ArrayAdapter<FolderItem> {
        FolderItemAdapter(Context context, List<FolderItem> items) {
            super(context, R.layout.item_folder, items);
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_folder, parent, false);
            }

            FolderItem item = getItem(position);
            TextView tvName = convertView.findViewById(R.id.tvFolderName);
            ImageView ivIcon = convertView.findViewById(R.id.ivFolderIcon);

            if (item != null) {
                tvName.setText(item.displayName);
                ivIcon.setImageResource(item.isParent ? R.drawable.ic_arrow_back : R.drawable.ic_folder);
            }

            return convertView;
        }
    }
}
