package com.audeyshelf.audiobook.ui.adapter;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.audeyshelf.audiobook.R;
import com.audeyshelf.audiobook.model.Chapter;
import com.audeyshelf.audiobook.util.TimeFormatter;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for chapter list matching reference screenshots 9ad651e3.png and be6742db.png.
 */
public class ChapterAdapter extends BaseAdapter {
    private final Context context;
    private final LayoutInflater inflater;
    private List<Chapter> chapters = new ArrayList<>();
    private int currentChapterIndex = -1;

    public ChapterAdapter(Context context, List<Chapter> chapters) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.chapters = chapters != null ? new ArrayList<>(chapters) : new ArrayList<>();
    }

    public void setChapters(List<Chapter> chapters, int currentIndex) {
        this.chapters = chapters != null ? new ArrayList<>(chapters) : new ArrayList<>();
        this.currentChapterIndex = currentIndex;
        notifyDataSetChanged();
    }

    public void setCurrentChapterIndex(int index) {
        this.currentChapterIndex = index;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return chapters.size();
    }

    @Override
    public Chapter getItem(int position) {
        return chapters.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    static class ViewHolder {
        TextView tvTitle;
        TextView tvSubtitle;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_chapter_detail, parent, false);
            holder = new ViewHolder();
            holder.tvTitle = convertView.findViewById(R.id.tvChapterTitle);
            holder.tvSubtitle = convertView.findViewById(R.id.tvChapterSubtitle);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        Chapter chapter = getItem(position);
        holder.tvTitle.setText(chapter.getTitle());

        String durStr = chapter.getDurationMs() > 0 ? TimeFormatter.formatMs(chapter.getDurationMs()) : "";
        String sub = "Chapter " + (position + 1);
        if (!durStr.isEmpty()) {
            sub = durStr + " • " + sub;
        }
        holder.tvSubtitle.setText(sub);

        boolean isActive = (position == currentChapterIndex);
        if (isActive) {
            holder.tvTitle.setTextColor(ContextCompat.getColor(context, R.color.accent));
            holder.tvTitle.setTypeface(null, Typeface.BOLD);
            holder.tvSubtitle.setTextColor(ContextCompat.getColor(context, R.color.accent));
        } else {
            holder.tvTitle.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
            holder.tvTitle.setTypeface(null, Typeface.BOLD);
            holder.tvSubtitle.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
        }

        return convertView;
    }
}
