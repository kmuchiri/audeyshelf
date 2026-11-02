package com.audeyshelf.audiobook.ui.adapter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.audeyshelf.audiobook.R;
import com.audeyshelf.audiobook.data.ResumeManager;
import com.audeyshelf.audiobook.model.Audiobook;
import com.audeyshelf.audiobook.util.AudioMetadataHelper;
import com.audeyshelf.audiobook.util.ImageCache;
import com.audeyshelf.audiobook.util.TimeFormatter;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * List adapter for audiobooks showing cover art, title, total time left,
 * progress, and dedicated chapter list launcher icon.
 */
public class AudiobookAdapter extends BaseAdapter implements Filterable {

    public interface OnAudiobookListener {
        void onItemClick(Audiobook audiobook);
        void onChapterClick(Audiobook audiobook);
    }

    public interface LivePositionProvider {
        long getLivePosition(String filePath);
    }

    private final Context context;
    private final LayoutInflater inflater;
    private final ResumeManager resumeManager;
    private List<Audiobook> originalList = new ArrayList<>();
    private List<Audiobook> filteredList = new ArrayList<>();
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private OnAudiobookListener listener;
    private LivePositionProvider livePositionProvider;
    private boolean isServerViewMode = false;

    public AudiobookAdapter(Context context, List<Audiobook> books) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.resumeManager = new ResumeManager(context);
        setAudiobooks(books);
    }

    public void setOnAudiobookListener(OnAudiobookListener listener) {
        this.listener = listener;
    }

    public void setLivePositionProvider(LivePositionProvider livePositionProvider) {
        this.livePositionProvider = livePositionProvider;
    }

    public void setServerViewMode(boolean serverViewMode) {
        this.isServerViewMode = serverViewMode;
        notifyDataSetChanged();
    }

    public void setAudiobooks(List<Audiobook> books) {
        this.originalList = books != null ? new ArrayList<>(books) : new ArrayList<>();
        this.filteredList = new ArrayList<>(this.originalList);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return filteredList.size();
    }

    @Override
    public Audiobook getItem(int position) {
        return filteredList.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    static class ViewHolder {
        View layoutContent;
        ImageView ivCover;
        View layoutFallback;
        TextView tvFallbackTitle;
        TextView tvTitle;
        TextView tvTimeLeft;
        ImageView ivChapters;
        String boundPath;
        String boundCoverUrl;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_audiobook, parent, false);
            holder = new ViewHolder();
            holder.layoutContent = convertView.findViewById(R.id.layoutBookContent);
            holder.ivCover = convertView.findViewById(R.id.ivBookCover);
            holder.layoutFallback = convertView.findViewById(R.id.layoutBookCoverFallback);
            holder.tvFallbackTitle = convertView.findViewById(R.id.tvBookCoverFallback);
            holder.tvTitle = convertView.findViewById(R.id.tvBookTitle);
            holder.tvTimeLeft = convertView.findViewById(R.id.tvBookTimeLeft);
            holder.ivChapters = convertView.findViewById(R.id.ivBookChapters);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        final Audiobook book = getItem(position);
        holder.boundPath = book.getFilePath();
        holder.boundCoverUrl = book.getCoverUrl();

        // Title
        holder.tvTitle.setText(book.getTitle());

        // Determine playback position
        long currentPos = -1L;
        if (livePositionProvider != null && book.getFilePath() != null && !book.getFilePath().isEmpty()) {
            currentPos = livePositionProvider.getLivePosition(book.getFilePath());
        }
        if (currentPos < 0) {
            if (book.getFilePath() != null && !book.getFilePath().isEmpty() && resumeManager != null) {
                currentPos = resumeManager.getResumePosition(book.getFilePath());
            }
            if (currentPos < 0) {
                currentPos = book.getResumePositionMs();
            }
        }

        long totalDur = book.getDurationMs();
        String timeLeftStr = TimeFormatter.formatTimeLeft(currentPos, totalDur);
        holder.tvTimeLeft.setText(timeLeftStr);

        // Tint chapter list icon to theme secondary text color
        holder.ivChapters.setColorFilter(ContextCompat.getColor(context, R.color.text_secondary));

        // Click listeners
        View.OnClickListener itemClickListener = v -> {
            if (listener != null) {
                listener.onItemClick(book);
            }
        };
        convertView.setOnClickListener(itemClickListener);
        holder.layoutContent.setOnClickListener(itemClickListener);

        holder.ivChapters.setOnClickListener(v -> {
            if (listener != null) {
                listener.onChapterClick(book);
            }
        });

        // Cover art loading
        holder.ivCover.setImageResource(R.drawable.ic_book_placeholder);
        holder.layoutFallback.setVisibility(View.GONE);

        if (book.getCoverUrl() != null && !book.getCoverUrl().isEmpty()) {
            final String coverUrl = book.getCoverUrl();
            Bitmap cached = ImageCache.getInstance().get(coverUrl);
            if (cached != null) {
                holder.ivCover.setImageBitmap(cached);
                holder.layoutFallback.setVisibility(View.GONE);
            } else {
                imageExecutor.execute(() -> {
                    final Bitmap remoteCover = downloadCover(coverUrl);
                    if (remoteCover != null) {
                        ImageCache.getInstance().put(coverUrl, remoteCover);
                    }
                    mainHandler.post(() -> {
                        if (coverUrl.equals(holder.boundCoverUrl)) {
                            if (remoteCover != null) {
                                holder.ivCover.setImageBitmap(remoteCover);
                                holder.layoutFallback.setVisibility(View.GONE);
                            } else {
                                holder.layoutFallback.setVisibility(View.VISIBLE);
                                holder.tvFallbackTitle.setText(book.getTitle());
                            }
                        }
                    });
                });
            }
        } else {
            final String path = book.getFilePath();
            imageExecutor.execute(() -> {
                final Bitmap cover = AudioMetadataHelper.loadCoverArt(path, 120, 120);
                mainHandler.post(() -> {
                    if (path != null && path.equals(holder.boundPath)) {
                        if (cover != null) {
                            holder.ivCover.setImageBitmap(cover);
                            holder.layoutFallback.setVisibility(View.GONE);
                        } else {
                            holder.layoutFallback.setVisibility(View.VISIBLE);
                            holder.tvFallbackTitle.setText(book.getTitle());
                        }
                    }
                });
            });
        }

        return convertView;
    }

    private Bitmap downloadCover(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                try (InputStream is = conn.getInputStream()) {
                    return BitmapFactory.decodeStream(is);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    @Override
    public Filter getFilter() {
        return new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                FilterResults results = new FilterResults();
                if (constraint == null || constraint.length() == 0) {
                    results.values = originalList;
                    results.count = originalList.size();
                } else {
                    String query = constraint.toString().toLowerCase().trim();
                    List<Audiobook> matches = new ArrayList<>();
                    for (Audiobook b : originalList) {
                        if (b.getTitle().toLowerCase().contains(query) ||
                                b.getAuthor().toLowerCase().contains(query)) {
                            matches.add(b);
                        }
                    }
                    results.values = matches;
                    results.count = matches.size();
                }
                return results;
            }

            @SuppressWarnings("unchecked")
            @Override
            protected void publishResults(CharSequence constraint, FilterResults results) {
                filteredList = (List<Audiobook>) results.values;
                notifyDataSetChanged();
            }
        };
    }
}
