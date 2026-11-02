package com.audeyshelf.audiobook.ui.adapter;

import android.content.Context;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.audeyshelf.audiobook.R;
import com.audeyshelf.audiobook.model.Bookmark;
import com.audeyshelf.audiobook.util.TimeFormatter;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Adapter for bookmark list inside player bookmarks dialog.
 */
public class BookmarkAdapter extends BaseAdapter {
    private final Context context;
    private final LayoutInflater inflater;
    private List<Bookmark> bookmarks = new ArrayList<>();
    private final OnBookmarkDeleteListener deleteListener;

    public interface OnBookmarkDeleteListener {
        void onBookmarkDelete(Bookmark bookmark);
    }

    public BookmarkAdapter(Context context, List<Bookmark> bookmarks, OnBookmarkDeleteListener deleteListener) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.bookmarks = bookmarks != null ? new ArrayList<>(bookmarks) : new ArrayList<>();
        this.deleteListener = deleteListener;
    }

    public void setBookmarks(List<Bookmark> list) {
        this.bookmarks = list != null ? new ArrayList<>(list) : new ArrayList<>();
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return bookmarks.size();
    }

    @Override
    public Bookmark getItem(int position) {
        return bookmarks.get(position);
    }

    @Override
    public long getItemId(int position) {
        return bookmarks.get(position).getId();
    }

    static class ViewHolder {
        TextView tvTimestamp;
        TextView tvLabel;
        TextView tvDate;
        ImageView ivDelete;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_bookmark, parent, false);
            holder = new ViewHolder();
            holder.tvTimestamp = convertView.findViewById(R.id.tvBookmarkTime);
            holder.tvLabel = convertView.findViewById(R.id.tvBookmarkLabel);
            holder.tvDate = convertView.findViewById(R.id.tvBookmarkDate);
            holder.ivDelete = convertView.findViewById(R.id.ivDeleteBookmark);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        final Bookmark bookmark = getItem(position);
        holder.tvTimestamp.setText(TimeFormatter.formatMs(bookmark.getPositionMs()));

        String label = bookmark.getLabel();
        if (label != null && !label.trim().isEmpty()) {
            holder.tvLabel.setVisibility(View.VISIBLE);
            holder.tvLabel.setText(label.trim());
        } else {
            holder.tvLabel.setVisibility(View.GONE);
        }

        // Format created date
        long dateMs = bookmark.getCreatedAt() * 1000L;
        String dateStr = DateFormat.getDateFormat(context).format(new Date(dateMs));
        holder.tvDate.setText(dateStr);

        holder.ivDelete.setOnClickListener(v -> {
            if (deleteListener != null) {
                deleteListener.onBookmarkDelete(bookmark);
            }
        });

        return convertView;
    }
}
